package io.github.mkhalifa.miracast.mice;

import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.Collections;

import io.github.mkhalifa.miracast.SessionLog;

/** mDNS responder on wlan0 for `<host>.local` (A) and `<instance>._display._tcp.local` (PTR/SRV/TXT). */
public final class MdnsResponder {
    private static final String GROUP = "224.0.0.251";
    private static final String GROUP6 = "ff02::fb";
    private static final int PORT = 5353;
    private static final int SO_REUSEPORT = 15;

    private final String host;
    private final String instance;
    private final String containerId;
    private final int servicePort;
    private volatile boolean running;
    private volatile boolean bound;
    private MulticastSocket socket;
    private Thread thread;

    public MdnsResponder(String host, String instance, int servicePort, String containerId) {
        this.host = host;
        this.instance = instance.replace('.', ' ');
        this.servicePort = servicePort;
        this.containerId = containerId;
    }

    public boolean isBound() {
        return bound;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "mdns");
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        MulticastSocket s = socket;
        if (s != null) s.close();
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                serve();
            } catch (IOException | RuntimeException e) {
                bound = false;
                if (!running) break;
                SessionLog.w("mDNS responder error, retrying in 5 s", e);
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException ie) {
                    SessionLog.w("mDNS retry wait interrupted", ie);
                    break;
                }
            }
        }
        bound = false;
    }

    private void serve() throws IOException {
        NetworkInterface wlan = NetworkInterface.getByName("wlan0");
        byte[] ip = wlanIpv4(wlan);
        if (wlan == null || ip == null) throw new IOException("wlan0 has no IPv4 address");
        InetAddress group = InetAddress.getByName(GROUP);
        InetAddress group6 = InetAddress.getByName(GROUP6);
        try (MulticastSocket s = new MulticastSocket(null)) {
            socket = s;
            s.setReuseAddress(true);
            setReusePort(s);
            s.bind(new InetSocketAddress(PORT));
            s.setNetworkInterface(wlan);
            s.joinGroup(new InetSocketAddress(group, PORT), wlan);
            try {
                s.joinGroup(new InetSocketAddress(group6, PORT), wlan);
            } catch (IOException e) {
                SessionLog.w("mDNS IPv6 group join failed", e);
            }
            s.setTimeToLive(255);
            s.setSoTimeout(30_000);
            bound = true;
            MdnsCodec.Zone zone = new MdnsCodec.Zone(host, instance, ip, servicePort, containerId);
            SessionLog.i("mDNS responder up: " + host + ".local -> " + InetAddress.getByAddress(ip).getHostAddress()
                    + ", " + instance + "._display._tcp port " + servicePort);
            for (int i = 0; i < 3; i++) send(s, MdnsCodec.announce(zone), group, PORT);
            byte[] buf = new byte[9000];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    p.setLength(buf.length);
                    s.receive(p);
                } catch (SocketTimeoutException e) {
                    byte[] now = wlanIpv4(NetworkInterface.getByName("wlan0"));
                    if (!Arrays.equals(now, ip)) throw new IOException("wlan0 address changed");
                    continue;
                }
                MdnsCodec.Query q = MdnsCodec.parseQuery(p.getData(), p.getLength());
                if (q == null) continue;
                boolean legacy = p.getPort() != PORT;
                byte[] reply = MdnsCodec.answer(q, zone, legacy);
                if (reply == null) continue;
                SessionLog.i("mDNS answer to " + p.getAddress().getHostAddress() + ":" + p.getPort() + " for "
                        + q.questions.get(0).name + (legacy ? " (legacy unicast)" : q.wantsUnicast() ? " (QU)" : ""));
                if (legacy) {
                    send(s, reply, p.getAddress(), p.getPort());
                    continue;
                }
                // Direct reply to the querier plus a multicast in the query's address family.
                send(s, reply, p.getAddress(), p.getPort());
                send(s, reply, p.getAddress() instanceof Inet6Address ? group6 : group, PORT);
            }
        } finally {
            bound = false;
            socket = null;
        }
    }

    private static void send(MulticastSocket s, byte[] b, InetAddress to, int port) {
        try {
            s.send(new DatagramPacket(b, b.length, to, port));
        } catch (IOException e) {
            SessionLog.w("mDNS send to " + to.getHostAddress() + " failed", e);
        }
    }

    /** SO_REUSEPORT lets us share 5353 with the system's own mDNS sockets. */
    private static void setReusePort(MulticastSocket s) {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.fromDatagramSocket(s)) {
            Os.setsockoptInt(pfd.getFileDescriptor(), OsConstants.SOL_SOCKET, SO_REUSEPORT, 1);
        } catch (Exception e) {
            SessionLog.w("SO_REUSEPORT not set", e);
        }
    }

    static byte[] wlanIpv4(NetworkInterface ni) {
        if (ni == null) return null;
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) return a.getAddress();
        }
        return null;
    }
}
