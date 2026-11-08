package io.github.mkhalifa.miracast.mice;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

import io.github.mkhalifa.miracast.SessionLog;

/** MS-MICE control channel on TCP 7250: SOURCE_READY starts a session, STOP_PROJECTION ends it. */
public final class MiceServer {
    public static final int PORT = 7250;
    /** Session Establishment Timer without PIN entry (MS-MICE 3.1.2). */
    private static final int ESTABLISH_MS = 30_000;

    public interface Listener {
        void onSourceReady(Link link, String sourceIp, int rtspPort, String friendlyName);

        void onStopProjection(Link link, String reason);
    }

    /** One accepted 7250 connection. */
    public final class Link {
        private final Socket socket;
        private volatile byte[] sourceId;
        private volatile byte[] friendlyNameRaw;
        private volatile boolean closed;
        private boolean established;

        Link(Socket socket) {
            this.socket = socket;
        }

        public String remote() {
            return socket.getInetAddress().getHostAddress();
        }

        /** RTSP is up: the establishment timer no longer applies. */
        public synchronized void rtspEstablished() {
            established = true;
            try {
                socket.setSoTimeout(0);
            } catch (IOException e) {
                SessionLog.w("MICE clear timeout failed", e);
            }
        }

        /** Sink-initiated graceful stop (MS-MICE 3.1.4), then close. */
        public void sendStop() {
            if (closed) return;
            byte[] msg = MiceMessage.stopProjection(friendlyNameRaw, sourceId).toBytes();
            new Thread(() -> {
                try {
                    OutputStream out = socket.getOutputStream();
                    out.write(msg);
                    out.flush();
                    SessionLog.i("MICE sent STOP_PROJECTION " + MiceMessage.hex(msg));
                } catch (IOException e) {
                    SessionLog.w("MICE STOP_PROJECTION send failed", e);
                }
                close();
            }, "mice-stop").start();
        }

        public void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException e) {
                SessionLog.w("MICE close failed", e);
            }
        }

        boolean alive() {
            return !closed && !socket.isClosed();
        }

        /** Before each read: the timeout is what is left of the establishment deadline. */
        private synchronized void armTimeout(long deadline) throws IOException {
            if (established) return;
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new SocketTimeoutException("establishment deadline passed");
            socket.setSoTimeout((int) left);
        }

        void serve() {
            String peer = remote();
            String endReason = "connection closed";
            long deadline = System.currentTimeMillis() + establishMs;
            try {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(new FilterInputStream(socket.getInputStream()) {
                    @Override
                    public int read() throws IOException {
                        armTimeout(deadline);
                        return super.read();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        armTimeout(deadline);
                        return super.read(b, off, len);
                    }
                });
                while (!closed) {
                    int size = in.readUnsignedShort();
                    if (size < 4 || size > 65535) throw new IOException("bad MICE size " + size);
                    byte[] b = new byte[size];
                    b[0] = (byte) (size >> 8);
                    b[1] = (byte) size;
                    in.readFully(b, 2, size - 2);
                    MiceMessage m;
                    try {
                        m = MiceMessage.parse(b, size);
                    } catch (MiceMessage.FormatException e) {
                        SessionLog.w("MICE bad message from " + peer + ": " + MiceMessage.hex(b), e);
                        endReason = "bad message";
                        break;
                    }
                    SessionLog.i("MICE <<< cmd=" + m.command + " ver=" + m.version + " " + MiceMessage.hex(b));
                    if (m.command == MiceMessage.SOURCE_READY) {
                        sourceId = m.value(MiceMessage.TLV_SOURCE_ID);
                        friendlyNameRaw = m.value(MiceMessage.TLV_FRIENDLY_NAME);
                        int port = m.rtspPort();
                        String name = m.friendlyName();
                        SessionLog.i("MICE SOURCE_READY from " + peer + " name=" + name + " rtsp=" + port
                                + " sourceId=" + MiceMessage.hex(sourceId));
                        if (port <= 0) {
                            endReason = "SOURCE_READY without RTSP port";
                            break;
                        }
                        listener.onSourceReady(this, peer, port, name);
                    } else if (m.command == MiceMessage.STOP_PROJECTION) {
                        endReason = "STOP_PROJECTION from source";
                        break;
                    } else {
                        endReason = "unsupported command " + m.command;
                        break;
                    }
                }
            } catch (SocketTimeoutException e) {
                endReason = "session establishment timeout";
            } catch (EOFException e) {
                endReason = "source closed 7250";
            } catch (IOException e) {
                if (!closed) SessionLog.w("MICE link " + peer + " error", e);
                endReason = closed ? "closed by sink" : "I/O error";
            }
            SessionLog.i("MICE link " + peer + " ended: " + endReason);
            boolean wasClosed = closed;
            close();
            if (!wasClosed) listener.onStopProjection(this, endReason);
        }
    }

    private final Listener listener;
    private final int establishMs;
    private volatile boolean running;
    private volatile ServerSocket server;
    private volatile Link active;

    public MiceServer(Listener listener) {
        this(listener, ESTABLISH_MS);
    }

    MiceServer(Listener listener, int establishMs) {
        this.listener = listener;
        this.establishMs = establishMs;
    }

    public boolean isListening() {
        ServerSocket s = server;
        return s != null && s.isBound() && !s.isClosed();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        new Thread(this::acceptLoop, "mice-accept").start();
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = server;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                SessionLog.w("MICE server close failed", e);
            }
        }
        Link l = active;
        if (l != null) l.close();
    }

    private void acceptLoop() {
        while (running) {
            try (ServerSocket s = new ServerSocket()) {
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(PORT));
                server = s;
                SessionLog.i("MICE listening on TCP " + PORT);
                while (running) {
                    Socket c = s.accept();
                    Link current = active;
                    if (current != null && current.alive()) {
                        SessionLog.i("MICE rejecting second connection from " + c.getInetAddress().getHostAddress());
                        c.close();
                        continue;
                    }
                    Link link = new Link(c);
                    active = link;
                    SessionLog.i("MICE connection from " + link.remote());
                    new Thread(link::serve, "mice-link").start();
                }
            } catch (IOException e) {
                server = null;
                if (!running) break;
                SessionLog.w("MICE server error, retrying in 3 s", e);
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ie) {
                    SessionLog.w("MICE retry wait interrupted", ie);
                    break;
                }
            }
        }
        server = null;
    }
}
