package io.github.mkhalifa.miracast.helper;

import android.content.Context;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.mkhalifa.miracast.mice.WscVendorElement;

/**
 * Shell-uid Wi-Fi Direct sink: WFD info, autonomous group, wildcard approver, MS-MICE vendor element,
 * join detection and receiver launch. All state lives on one HandlerThread.
 */
public final class HelperCore {
    public static final int VERSION = 5;
    public static final String APP = "io.github.mkhalifa.miracast";
    static final String DEFAULT_NAME = "KhalifaTV";
    static final int CONTROL_PORT = 7236;
    static final int MICE_PORT = 7250;
    private static final long ENSURE_MS = 5_000;
    private static final long POLL_MS = 500;
    private static final long NEIGH_TIMEOUT_MS = 20_000;
    private static final long REVIVE_MS = 30_000;
    private static final long REARM_MS = 30_000;

    private static HelperCore instance;

    private final HandlerThread thread = new HandlerThread("MiracastHelper");
    private final Handler h;
    private final RemoteCallbackList<IHelperCallback> callbacks = new RemoteCallbackList<>();
    private Context ctx;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel ch;

    private boolean enabled = true;
    private String name;
    private String miceHost = "";
    private int miceCapability = WscVendorElement.CAPABILITY_DEFAULT;

    private boolean p2pEnabled;
    private boolean wfdSet;
    private boolean wfdPending;
    private String nameApplied;
    private String vendorApplied;
    private boolean vendorPending;
    private String vendorResult = "not tried";
    private Boolean vendorSupported;
    private boolean approverAttached;
    private boolean approverPending;
    private boolean createPending;
    private boolean groupMode;
    private boolean sessionActive;
    private boolean removePending;
    private boolean cleanupDone;
    private long lastRearm;
    private int createFailures;
    private long nextCreateAt;
    private int groupMissing;
    private String groupIface;
    private boolean groupUp;
    /** A reset hid whether the group still exists; the next poll that finds none runs the group-down cleanup. */
    private boolean groupStale;
    private final Set<String> clients = new HashSet<>();
    private final Map<String, Long> pendingJoins = new HashMap<>();
    private final Set<String> launchedIps = new HashSet<>();
    private String lastJoin = "none";
    private long lastRevive;
    private String lastError = "";

    public static synchronized HelperCore get() {
        if (instance == null) instance = new HelperCore();
        return instance;
    }

    private HelperCore() {
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(this::init);
    }

    private void init() {
        try {
            ctx = ShellContext.create();
            p2p = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
            HelperLog.i("helper v" + VERSION + " uid=" + android.os.Process.myUid() + " pid=" + android.os.Process.myPid());
            if (name == null) name = DEFAULT_NAME;
            run("appops", "set", APP, "SYSTEM_ALERT_WINDOW", "allow");
        } catch (Throwable t) {
            lastError = "init: " + t;
            HelperLog.w("init failed", t);
        }
        h.post(ensureTask);
        h.post(this::poll);
    }

    private final Runnable ensureTask = this::ensureLoop;

    private void ensureLoop() {
        ensure();
        h.postDelayed(ensureTask, ENSURE_MS);
    }

    // ---- public API (any thread) ----

    public void setEnabled(boolean on) {
        h.post(() -> {
            if (enabled == on) return;
            enabled = on;
            HelperLog.i("enabled=" + on);
            if (!on) shutdownP2p();
            ensure();
        });
    }

    public void setName(String n) {
        h.post(() -> {
            if (n != null && !n.trim().isEmpty()) name = n.trim();
            nameApplied = null;
            ensure();
        });
    }

    public void setMice(String host, int capability) {
        h.post(() -> {
            miceHost = host == null ? "" : host;
            miceCapability = capability;
            HelperLog.i("mice host=" + miceHost + " capability=0x" + Integer.toHexString(capability));
            applyVendorElements();
        });
    }

    /** Group mode keeps an autonomous group up; listen mode (default) only advertises via P2P discovery. */
    public void setGroupMode(boolean on) {
        h.post(() -> {
            if (groupMode == on) return;
            groupMode = on;
            cleanupDone = false;
            HelperLog.i("group mode=" + on);
            if (!on) removeApprover();
            ensure();
        });
    }

    /** While a session runs, discovery re-arm pauses so the radio stays on the session. */
    public void setSessionActive(boolean active) {
        h.post(() -> {
            sessionActive = active;
            HelperLog.i("session active=" + active);
            if (!active) lastRearm = 0;
            ensure();
        });
    }

    public void register(IHelperCallback cb) {
        if (cb != null) callbacks.register(cb);
        h.post(this::broadcastStatus);
    }

    public void removeGroup() {
        h.post(() -> {
            if (p2p == null || ch == null) return;
            HelperLog.i("removeGroup requested");
            removeApprover();
            p2p.removeGroup(ch, listener("removeGroup", null));
            nextCreateAt = System.currentTimeMillis() + 2_000;
        });
    }

    public void launchReceiver(String source, int port, String via) {
        h.post(() -> launch(source, port, via));
    }

    public void shutdown() {
        HelperLog.i("helper shutting down");
        h.post(() -> {
            shutdownP2p();
            thread.quitSafely();
        });
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("helper=v").append(VERSION).append(" uid=").append(android.os.Process.myUid()).append('\n');
        sb.append("enabled=").append(enabled).append(" p2p=").append(p2pEnabled)
                .append(" mode=").append(groupMode ? "group" : "listen").append(" session=").append(sessionActive).append('\n');
        sb.append("name=").append(name).append(" applied=").append(nameApplied).append('\n');
        sb.append("wfd=").append(wfdSet).append('\n');
        sb.append("group=").append(groupUp ? groupIface : "down").append(" clients=").append(clients.size()).append('\n');
        sb.append("approver=").append(approverAttached).append('\n');
        sb.append("vendorSupported=").append(vendorSupported).append(" vendor=").append(vendorResult).append('\n');
        sb.append("mice=").append(miceHost.isEmpty() ? "off" : miceHost).append(" cap=0x")
                .append(Integer.toHexString(miceCapability)).append(" 7250=").append(isListening(MICE_PORT)).append('\n');
        sb.append("lastJoin=").append(lastJoin).append('\n');
        if (!lastError.isEmpty()) sb.append("error=").append(lastError).append('\n');
        return sb.toString();
    }

    // ---- state machine (handler thread) ----

    private void ensure() {
        if (p2p == null) return;
        if (!enabled) return;
        if (ch == null) {
            ch = p2p.initialize(ctx, thread.getLooper(), () -> {
                HelperLog.i("p2p channel disconnected");
                ch = null;
                resetFlags();
            });
            resetFlags();
            HelperLog.i("p2p channel initialized");
        }
        final WifiP2pManager.Channel c = ch;
        p2p.requestP2pState(c, state -> {
            boolean on = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
            if (on != p2pEnabled) HelperLog.i("p2p state " + (on ? "enabled" : "disabled"));
            if (!on && p2pEnabled) resetFlags();
            p2pEnabled = on;
            if (on && c == ch) ensureEnabled();
        });
        reviveApp();
    }

    private void ensureEnabled() {
        if (!wfdSet && !wfdPending) setWfdInfo();
        if (name != null && !name.equals(nameApplied)) setDeviceName();
        applyVendorElements();
        if (groupMode) {
            if (!groupUp && !createPending && groupMissing >= 2 && System.currentTimeMillis() >= nextCreateAt) createGroup();
            if (groupUp && !approverAttached && !approverPending) addApprover();
            return;
        }
        if (!cleanupDone && groupUp && !removePending && clients.isEmpty() && !sessionActive) {
            removePending = true;
            removeApprover();
            p2p.removeGroup(ch, listener("removeGroup (listen mode)", ok -> {
                removePending = false;
                cleanupDone = ok;
            }));
            return;
        }
        if (!groupUp && groupMissing >= 2) cleanupDone = true;
        long t = System.currentTimeMillis();
        if (!groupUp && !sessionActive && t - lastRearm >= REARM_MS) {
            lastRearm = t;
            p2p.startListening(ch, listener("listen", null));
            p2p.discoverPeers(ch, listener("discoverPeers", null));
        }
    }

    private void resetFlags() {
        wfdSet = false;
        wfdPending = false;
        nameApplied = null;
        vendorApplied = null;
        vendorPending = false;
        approverAttached = false;
        approverPending = false;
        createPending = false;
        removePending = false;
        cleanupDone = false;
        lastRearm = 0;
        groupStale = groupStale || groupUp;
        groupUp = false;
        groupMissing = 0;
        clients.clear();
        pendingJoins.clear();
        launchedIps.clear();
    }

    private void shutdownP2p() {
        if (p2p != null && ch != null) {
            removeApprover();
            p2p.removeGroup(ch, listener("removeGroup (disable)", null));
            if (vendorApplied != null && !vendorApplied.isEmpty()) {
                try {
                    p2p.setVendorElements(ch, Collections.emptyList(), listener("clear vendor elements", null));
                } catch (RuntimeException e) {
                    HelperLog.w("clear vendor elements failed", e);
                }
            }
            WifiP2pWfdInfo off = new WifiP2pWfdInfo();
            off.setEnabled(false);
            p2p.setWfdInfo(ch, off, listener("setWfdInfo off", null));
            ch.close();
        }
        ch = null;
        resetFlags();
        broadcastStatus();
    }

    private void setWfdInfo() {
        WifiP2pWfdInfo wfd = new WifiP2pWfdInfo();
        wfd.setEnabled(true);
        wfd.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_PRIMARY_SINK);
        wfd.setSessionAvailable(true);
        wfd.setControlPort(CONTROL_PORT);
        wfd.setMaxThroughput(50);
        wfdPending = true;
        p2p.setWfdInfo(ch, wfd, listener("setWfdInfo", ok -> {
            wfdPending = false;
            wfdSet = ok;
        }));
    }

    private void setDeviceName() {
        final String n = name;
        nameApplied = n;
        try {
            WifiP2pManager.class.getMethod("setDeviceName", WifiP2pManager.Channel.class, String.class,
                    WifiP2pManager.ActionListener.class).invoke(p2p, ch, n, listener("setDeviceName " + n, null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            HelperLog.w("setDeviceName unavailable", e);
        }
    }

    private void applyVendorElements() {
        if (p2p == null || ch == null || !p2pEnabled || vendorPending) return;
        if (vendorSupported == null) {
            try {
                vendorSupported = p2p.isSetVendorElementsSupported();
            } catch (RuntimeException e) {
                vendorSupported = false;
                HelperLog.w("isSetVendorElementsSupported threw", e);
            }
            HelperLog.i("isSetVendorElementsSupported=" + vendorSupported);
        }
        if (!vendorSupported) {
            vendorResult = "unsupported";
            return;
        }
        String ip = miceHost.isEmpty() ? null : wlanIpv4();
        boolean listening = !miceHost.isEmpty() && isListening(MICE_PORT);
        final String want = (listening && ip != null)
                ? miceHost + "|" + ip + "|" + miceCapability : "";
        if (want.equals(vendorApplied)) return;
        List<ScanResult.InformationElement> ies = new ArrayList<>();
        if (!want.isEmpty()) {
            byte[] body = WscVendorElement.body(miceHost, ip, miceCapability);
            ies.add(new ScanResult.InformationElement(WscVendorElement.ELEMENT_ID, 0, body));
            HelperLog.i("vendor element " + hex(body));
        }
        vendorPending = true;
        try {
            p2p.setVendorElements(ch, ies, listener("setVendorElements " + (want.isEmpty() ? "(clear)" : want), ok -> {
                vendorPending = false;
                vendorResult = (ok ? "OK " : "FAILED ") + (want.isEmpty() ? "cleared" : want);
                if (ok) vendorApplied = want;
            }));
        } catch (RuntimeException e) {
            vendorPending = false;
            vendorResult = "threw " + e;
            vendorApplied = want;
            HelperLog.w("setVendorElements threw", e);
        }
    }

    private void createGroup() {
        createPending = true;
        p2p.createGroup(ch, listener("createGroup", ok -> {
            createPending = false;
            if (ok) {
                createFailures = 0;
                vendorApplied = null;
            } else {
                createFailures++;
                nextCreateAt = System.currentTimeMillis() + Math.min(30_000, 2_000L * createFailures);
            }
        }));
    }

    private void addApprover() {
        approverPending = true;
        p2p.addExternalApprover(ch, MacAddress.BROADCAST_ADDRESS, new WifiP2pManager.ExternalApproverRequestListener() {
            @Override
            public void onAttached(MacAddress a) {
                approverPending = false;
                approverAttached = true;
                HelperLog.i("approver attached " + a);
            }

            @Override
            public void onDetached(MacAddress a, int reason) {
                approverPending = false;
                approverAttached = false;
                HelperLog.i("approver detached " + a + " reason=" + reason);
                h.postDelayed(() -> {
                    if (groupMode && groupUp && !approverAttached && !approverPending && ch != null) addApprover();
                }, 300);
            }

            @Override
            public void onPinGenerated(MacAddress a, String pin) {
                HelperLog.i("approver pin for " + a + ": " + pin);
            }

            @Override
            public void onConnectionRequested(int type, WifiP2pConfig cfg, WifiP2pDevice dev) {
                HelperLog.i("connection request type=" + type + " from " + dev.deviceName + "/" + dev.deviceAddress);
                p2p.setConnectionRequestResult(ch, MacAddress.fromString(dev.deviceAddress),
                        WifiP2pManager.CONNECTION_REQUEST_ACCEPT, listener("accept " + dev.deviceAddress, null));
            }
        });
    }

    private void removeApprover() {
        if (p2p == null || ch == null || (!approverAttached && !approverPending)) return;
        p2p.removeExternalApprover(ch, MacAddress.BROADCAST_ADDRESS, listener("removeExternalApprover", null));
        approverAttached = false;
        approverPending = false;
    }

    private void poll() {
        h.postDelayed(this::poll, POLL_MS);
        if (p2p == null || ch == null || !enabled || !p2pEnabled) return;
        final WifiP2pManager.Channel c = ch;
        p2p.requestGroupInfo(c, g -> {
            if (c != ch) return;
            onGroup(g);
        });
        checkJoins();
    }

    private void onGroup(WifiP2pGroup g) {
        boolean up = g != null && g.isGroupOwner();
        if (g != null && !g.isGroupOwner()) HelperLog.i("in a group as client, ignoring: " + g.getNetworkName());
        boolean lostDuringReset = groupStale && !up;
        groupStale = false;
        if (up != groupUp || lostDuringReset) {
            HelperLog.i(up ? "group up iface=" + g.getInterface() + " ssid=" + g.getNetworkName() : "group down");
            groupUp = up;
            groupIface = up ? g.getInterface() : null;
            if (!up) {
                approverAttached = false;
                approverPending = false;
                clients.clear();
                pendingJoins.clear();
                launchedIps.clear();
                if (sessionActive && !groupMode) {
                    // A Wi-Fi Direct session cannot outlive its group; the app may have died without saying so.
                    HelperLog.i("group gone: session over, discovery resumes");
                    sessionActive = false;
                    lastRearm = 0;
                }
            } else {
                vendorApplied = null;
                applyVendorElements();
                if (groupMode && !approverAttached && !approverPending) addApprover();
            }
            broadcastStatus();
        }
        groupMissing = up ? 0 : groupMissing + 1;
        if (!up) return;
        Set<String> now = new HashSet<>();
        for (WifiP2pDevice d : g.getClientList()) now.add(d.deviceAddress);
        for (String a : now) {
            if (!clients.contains(a)) {
                HelperLog.i("client joined " + a);
                pendingJoins.put(a, System.currentTimeMillis());
            }
        }
        for (String a : clients) {
            if (!now.contains(a)) {
                HelperLog.i("client left " + a);
                pendingJoins.remove(a);
            }
        }
        if (now.isEmpty()) launchedIps.clear();
        clients.clear();
        clients.addAll(now);
    }

    private void checkJoins() {
        if (pendingJoins.isEmpty() || groupIface == null) return;
        long t = System.currentTimeMillis();
        List<String> live = IpNeigh.liveIpv4(run("ip", "neigh", "show", "dev", groupIface));
        String fresh = null;
        for (String ip : live) {
            if (!launchedIps.contains(ip)) {
                fresh = ip;
                break;
            }
        }
        if (fresh != null) {
            String client = pendingJoins.keySet().iterator().next();
            pendingJoins.remove(client);
            launchedIps.add(fresh);
            lastJoin = client + " " + fresh + " at " + t;
            HelperLog.i("source " + client + " has address " + fresh);
            launch(fresh, CONTROL_PORT, "p2p");
            int n = callbacks.beginBroadcast();
            for (int i = 0; i < n; i++) {
                try {
                    callbacks.getBroadcastItem(i).onSourceJoined(fresh, CONTROL_PORT);
                } catch (RemoteException e) {
                    HelperLog.w("callback onSourceJoined failed", e);
                }
            }
            callbacks.finishBroadcast();
            broadcastStatus();
            return;
        }
        pendingJoins.entrySet().removeIf(e -> {
            boolean expired = t - e.getValue() > NEIGH_TIMEOUT_MS;
            if (expired) HelperLog.i("no address for " + e.getKey() + " within " + NEIGH_TIMEOUT_MS + " ms");
            return expired;
        });
    }

    private void launch(String source, int port, String via) {
        HelperLog.i("launching receiver for " + source + ":" + port + " via " + via);
        String out = run("am", "start", "-n", APP + "/.ReceiverActivity",
                "--es", "source", source, "--ei", "port", Integer.toString(port), "--es", "via", via);
        HelperLog.i("am start: " + out.trim().replace('\n', ' '));
    }

    /** Restarts the app's listener when the MS-MICE port is advertised but nothing listens on it. */
    private void reviveApp() {
        if (miceHost.isEmpty()) return;
        long t = System.currentTimeMillis();
        if (t - lastRevive < REVIVE_MS || isListening(MICE_PORT)) return;
        lastRevive = t;
        HelperLog.i("port " + MICE_PORT + " closed, starting the app's listener");
        String out = run("am", "start-foreground-service", "-n", APP + "/.SessionService", "-a", APP + ".LISTEN");
        HelperLog.i("am start-foreground-service: " + out.trim().replace('\n', ' '));
    }

    private void broadcastStatus() {
        String s = status();
        int n = callbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                callbacks.getBroadcastItem(i).onStatus(s);
            } catch (RemoteException e) {
                HelperLog.w("callback onStatus failed", e);
            }
        }
        callbacks.finishBroadcast();
    }

    // ---- utilities ----

    private interface Done {
        void done(boolean ok);
    }

    private WifiP2pManager.ActionListener listener(String step, Done done) {
        return new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                HelperLog.i(step + ": OK");
                if (done != null) done.done(true);
                broadcastStatus();
            }

            @Override
            public void onFailure(int reason) {
                HelperLog.i(step + ": FAILED reason=" + reason);
                lastError = step + " reason=" + reason;
                if (done != null) done.done(false);
                broadcastStatus();
            }
        };
    }

    static String wlanIpv4() {
        try {
            NetworkInterface ni = NetworkInterface.getByName("wlan0");
            if (ni == null || !ni.isUp()) return null;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address) return a.getHostAddress();
            }
        } catch (IOException e) {
            HelperLog.w("wlan0 address lookup failed", e);
        }
        return null;
    }

    /** True when /proc/net/tcp or tcp6 has a socket in LISTEN state on the port. */
    static boolean isListening(int port) {
        String hex = String.format(Locale.ROOT, ":%04X", port);
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            File file = new File(f);
            if (!file.canRead()) continue;
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.trim().split("\\s+");
                    if (p.length > 3 && p[1].toUpperCase(Locale.ROOT).endsWith(hex) && p[3].equals("0A")) return true;
                }
            } catch (IOException e) {
                HelperLog.w("read " + f + " failed", e);
            }
        }
        return false;
    }

    static String run(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out;
            try (InputStream in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            p.waitFor();
            return out;
        } catch (IOException e) {
            HelperLog.w("run " + String.join(" ", cmd) + " failed", e);
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            HelperLog.w("run " + String.join(" ", cmd) + " interrupted", e);
            return "";
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x & 0xFF));
        return sb.toString();
    }
}
