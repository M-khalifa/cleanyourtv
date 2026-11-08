package io.github.mkhalifa.miracast;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.mkhalifa.miracast.helper.HelperCore;
import io.github.mkhalifa.miracast.helper.HelperService;
import io.github.mkhalifa.miracast.helper.IHelperCallback;
import io.github.mkhalifa.miracast.helper.IMiracastHelper;
import rikka.shizuku.Shizuku;

/** App side of the helper: binds the Shizuku UserService and pushes settings to it. */
public final class HelperClient {
    public interface Listener {
        void onHelperStatus(String status);
    }

    private static HelperClient instance;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Shizuku.UserServiceArgs args;
    private volatile IMiracastHelper helper;
    private volatile String status = "helper not connected";
    private volatile String miceHost = "";
    private volatile boolean sessionActive;
    private boolean binding;

    public static synchronized HelperClient get(Context c) {
        if (instance == null) instance = new HelperClient(c.getApplicationContext());
        return instance;
    }

    private HelperClient(Context c) {
        ctx = c;
        args = new Shizuku.UserServiceArgs(new ComponentName(c.getPackageName(), HelperService.class.getName()))
                .daemon(true)
                .processNameSuffix("helper")
                .tag("miracast-helper")
                .debuggable(BuildConfig.DEBUG)
                .version(HelperCore.VERSION);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            SessionLog.i("Shizuku binder received");
            main.post(this::bindIfAllowed);
            SessionService.listen(ctx);
        });
        Shizuku.addBinderDeadListener(() -> {
            SessionLog.i("Shizuku binder died");
            helper = null;
            binding = false;
            setStatus("Shizuku stopped");
        });
        Shizuku.addRequestPermissionResultListener((code, result) -> {
            SessionLog.i("Shizuku permission result " + result);
            if (result == PackageManager.PERMISSION_GRANTED) main.post(this::bindIfAllowed);
        });
    }

    public void addListener(Listener l) {
        listeners.add(l);
        l.onHelperStatus(status);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public String status() {
        return status;
    }

    public boolean connected() {
        return helper != null;
    }

    public static boolean shizukuRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException e) {
            SessionLog.w("Shizuku ping failed", e);
            return false;
        }
    }

    public static boolean shizukuGranted() {
        try {
            return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            SessionLog.w("Shizuku permission check failed", e);
            return false;
        }
    }

    public void requestPermission() {
        try {
            if (Shizuku.pingBinder()) Shizuku.requestPermission(1);
        } catch (RuntimeException e) {
            SessionLog.w("Shizuku permission request failed", e);
        }
    }

    /** Binds the helper when Shizuku runs and has granted us; safe to call repeatedly. */
    public void bindIfAllowed() {
        if (helper != null || binding) return;
        if (!shizukuGranted()) {
            setStatus(shizukuRunning() ? "Shizuku permission not granted" : "Shizuku not running");
            return;
        }
        binding = true;
        try {
            Shizuku.bindUserService(args, conn);
            setStatus("starting helper");
        } catch (RuntimeException e) {
            binding = false;
            SessionLog.w("bindUserService failed", e);
            setStatus("helper start failed: " + e);
        }
    }

    /** Stops the helper process (Settings "off"). */
    public void stopHelper() {
        try {
            if (shizukuRunning()) Shizuku.unbindUserService(args, conn, true);
        } catch (RuntimeException e) {
            SessionLog.w("unbindUserService failed", e);
        }
        helper = null;
        binding = false;
        setStatus("helper stopped");
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            if (binder == null || !binder.pingBinder()) {
                setStatus("helper binder invalid");
                return;
            }
            helper = IMiracastHelper.Stub.asInterface(binder);
            SessionLog.i("helper connected");
            try {
                helper.register(callback);
            } catch (RemoteException e) {
                SessionLog.w("helper register failed", e);
            }
            pushConfig();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binding = false;
            helper = null;
            setStatus("helper disconnected");
        }
    };

    private final IHelperCallback callback = new IHelperCallback.Stub() {
        @Override
        public void onStatus(String s) {
            setStatus(s);
        }

        @Override
        public void onSourceJoined(String ip, int port) {
            SessionLog.i("helper: Wi-Fi Direct source " + ip + ":" + port);
        }
    };

    /** Sends name, enabled flag and the MS-MICE host (empty until our listeners are up). */
    public void pushConfig() {
        IMiracastHelper h = helper;
        if (h == null) return;
        Prefs p = new Prefs(ctx);
        try {
            h.setName(p.name());
            h.setEnabled(p.enabled());
            h.setMice(miceHost, p.capability());
            h.setGroupMode(p.groupMode());
            h.setSessionActive(sessionActive);
        } catch (RemoteException e) {
            SessionLog.w("helper config push failed", e);
        }
    }

    /** Called by the listener once mDNS and TCP 7250 are up (host) or down (empty). */
    public void setMiceReady(String host) {
        miceHost = host == null ? "" : host;
        pushConfig();
    }

    /** Pauses the helper's discovery re-arm while a session runs. */
    public void setSessionActive(boolean active) {
        sessionActive = active;
        IMiracastHelper h = helper;
        if (h == null) return;
        try {
            h.setSessionActive(active);
        } catch (RemoteException e) {
            SessionLog.w("helper setSessionActive failed", e);
        }
    }

    public void removeGroup() {
        IMiracastHelper h = helper;
        if (h == null) return;
        try {
            h.removeGroup();
        } catch (RemoteException e) {
            SessionLog.w("helper removeGroup failed", e);
        }
    }

    /** Asks the shell-uid helper to start an activity when we may not start one from the background. */
    public boolean launchReceiver(String source, int port, String via) {
        IMiracastHelper h = helper;
        if (h == null) return false;
        try {
            h.launchReceiver(source, port, via);
            return true;
        } catch (RemoteException e) {
            SessionLog.w("helper launchReceiver failed", e);
            return false;
        }
    }

    private void setStatus(String s) {
        status = s;
        for (Listener l : listeners) main.post(() -> l.onHelperStatus(s));
    }
}
