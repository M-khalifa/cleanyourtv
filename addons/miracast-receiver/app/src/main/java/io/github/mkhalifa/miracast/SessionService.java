package io.github.mkhalifa.miracast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Surface;

import io.github.mkhalifa.miracast.mice.MdnsResponder;
import io.github.mkhalifa.miracast.mice.MiceServer;

/**
 * Foreground service: keeps the MS-MICE listeners (mDNS, TCP 7250) up while enabled and owns the WFD session.
 * Runs as specialUse when idle and mediaPlayback during a session.
 */
public final class SessionService extends Service implements MiceServer.Listener, WfdSession.Callback {
    public static final String ACTION_LISTEN = "io.github.mkhalifa.miracast.LISTEN";
    public static final String ACTION_START = "io.github.mkhalifa.miracast.START";
    public static final String ACTION_DISABLE = "io.github.mkhalifa.miracast.DISABLE";
    private static final String CHANNEL = "receiver";
    private static final int NOTIFICATION_ID = 1;
    private static final long P2P_CONNECT_MS = 10_000;
    private static final long MICE_CONNECT_MS = 5_000;

    public interface Ui {
        void onSessionState(String state);

        void onVideoSize(int width, int height);

        void onSessionEnded(String reason);
    }

    public final class Local extends Binder {
        public SessionService service() {
            return SessionService.this;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Local binder = new Local();
    private MdnsResponder mdns;
    private MiceServer mice;
    private WifiManager.MulticastLock multicastLock;
    private String miceHost = "";
    private WfdSession session;
    private MiceServer.Link sessionLink;
    private Ui ui;
    private Surface surface;
    private String lastState = "idle";

    /** Starts (or keeps) the listener; harmless when already running. */
    public static void listen(Context c) {
        start(c, new Intent(c, SessionService.class).setAction(ACTION_LISTEN));
    }

    public static void startSession(Context c, String source, int port, String via) {
        start(c, new Intent(c, SessionService.class).setAction(ACTION_START)
                .putExtra("source", source).putExtra("port", port).putExtra("via", via));
    }

    public static void disable(Context c) {
        start(c, new Intent(c, SessionService.class).setAction(ACTION_DISABLE));
    }

    private static void start(Context c, Intent i) {
        try {
            c.startForegroundService(i);
        } catch (RuntimeException e) {
            SessionLog.w("cannot start SessionService (" + i.getAction() + ")", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_LISTEN : intent.getAction();
        goForeground();
        Prefs prefs = new Prefs(this);
        if (ACTION_DISABLE.equals(action) || !prefs.enabled()) {
            stopCurrent("receiver turned off");
            stopListening();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startListening(prefs);
        HelperClient.get(this).bindIfAllowed();
        if (ACTION_START.equals(action)) {
            String source = intent.getStringExtra("source");
            int port = intent.getIntExtra("port", 7236);
            String via = intent.getStringExtra("via");
            if (source != null) beginSession(source, port, via == null ? "debug" : via, null);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopCurrent("service destroyed");
        stopListening();
        super.onDestroy();
    }

    // ---- listeners ----

    private void startListening(Prefs prefs) {
        String host = prefs.hostName();
        if (mice != null && host.equals(miceHost)) return;
        stopListening();
        miceHost = host;
        WifiManager wm = getSystemService(WifiManager.class);
        multicastLock = wm.createMulticastLock("miracast-mdns");
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();
        mice = new MiceServer(this);
        mice.start();
        mdns = new MdnsResponder(host, prefs.name(), MiceServer.PORT, prefs.containerId());
        mdns.start();
        main.removeCallbacks(readyCheck);
        main.postDelayed(readyCheck, 300);
    }

    private boolean advertised;

    private final Runnable readyCheck = new Runnable() {
        @Override
        public void run() {
            boolean ready = mice != null && mice.isListening() && mdns != null && mdns.isBound();
            if (ready != advertised) {
                advertised = ready;
                SessionLog.i(ready ? "MS-MICE ready as " + miceHost + ".local" : "MS-MICE listeners down");
                HelperClient.get(SessionService.this).setMiceReady(ready ? miceHost : "");
            }
            if (mice != null) main.postDelayed(this, ready ? 10_000 : 500);
        }
    };

    private void stopListening() {
        main.removeCallbacks(readyCheck);
        if (advertised) HelperClient.get(this).setMiceReady("");
        advertised = false;
        if (mice != null) mice.stop();
        if (mdns != null) mdns.stop();
        mice = null;
        mdns = null;
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        multicastLock = null;
    }

    // ---- sessions ----

    private void beginSession(String source, int port, String via, MiceServer.Link link) {
        if (session != null && !session.isEnded()) {
            if (session.source.equals(source) && session.port == port) {
                SessionLog.i("session to " + source + ":" + port + " already running");
                return;
            }
            stopCurrent("replaced by " + source);
        }
        sessionLink = link;
        lastState = "connecting to " + source + ":" + port;
        session = new WfdSession(source, port, via, link != null ? MICE_CONNECT_MS : P2P_CONNECT_MS, this);
        goForeground();
        HelperClient.get(this).setSessionActive(true);
        session.start();
        if (surface != null) session.setSurface(surface);
    }

    /** Back key or STOP_PROJECTION: ends the session gracefully. */
    public void stopCurrent(String reason) {
        WfdSession s = session;
        if (s == null) return;
        s.stop(reason);
        if ("p2p".equals(s.via)) HelperClient.get(this).removeGroup();
    }

    public void attach(Ui u) {
        ui = u;
        if (u != null) u.onSessionState(lastState);
    }

    public void setSurface(Surface s) {
        surface = s;
        if (session != null) session.setSurface(s);
    }

    /** OK on the remote during a session: switches frame pacing between smooth and instant. */
    public String togglePacing() {
        WfdSession s = session;
        return s == null ? null : s.togglePacing();
    }

    public boolean hasSession() {
        return session != null && !session.isEnded();
    }

    private void launchReceiver(String source, int port, String via) {
        Intent i = new Intent(this, ReceiverActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("source", source).putExtra("port", port).putExtra("via", via);
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(i);
                return;
            } catch (RuntimeException e) {
                SessionLog.w("startActivity failed", e);
            }
        }
        if (!HelperClient.get(this).launchReceiver(source, port, via)) {
            SessionLog.i("no overlay permission and no helper: trying startActivity anyway");
            startActivity(i);
        }
    }

    @Override
    public void onSourceReady(MiceServer.Link link, String sourceIp, int rtspPort, String friendlyName) {
        main.post(() -> {
            beginSession(sourceIp, rtspPort, "mice", link);
            launchReceiver(sourceIp, rtspPort, "mice");
        });
    }

    @Override
    public void onStopProjection(MiceServer.Link link, String reason) {
        main.post(() -> {
            if (link == sessionLink && session != null) {
                sessionLink = null;
                session.stop(reason);
            }
        });
    }

    @Override
    public void onSessionState(WfdSession s, String state) {
        main.post(() -> {
            if (s != session) return;
            lastState = state;
            if (state.startsWith("RTSP connected") && sessionLink != null) sessionLink.rtspEstablished();
            if (ui != null) ui.onSessionState(state);
        });
    }

    @Override
    public void onVideoSize(WfdSession s, int width, int height) {
        main.post(() -> {
            if (s == session && ui != null) ui.onVideoSize(width, height);
        });
    }

    @Override
    public void onSessionEnded(WfdSession s, String reason) {
        main.post(() -> {
            if (s != session) return;
            session = null;
            lastState = "ended: " + reason;
            MiceServer.Link link = sessionLink;
            sessionLink = null;
            if (link != null) link.sendStop();
            if (ui != null) ui.onSessionEnded(reason);
            HelperClient.get(SessionService.this).setSessionActive(false);
            goForeground();
        });
    }

    // ---- foreground ----

    private void goForeground() {
        boolean active = session != null && !session.isEnded();
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_cast)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(active ? getString(R.string.notif_session, session.source)
                        : getString(R.string.notif_ready, new Prefs(this).name()))
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, SettingsActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .setOngoing(true)
                .build();
        int type = active ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        try {
            startForeground(NOTIFICATION_ID, n, type);
        } catch (RuntimeException e) {
            SessionLog.w("startForeground failed (type " + type + ")", e);
        }
    }
}
