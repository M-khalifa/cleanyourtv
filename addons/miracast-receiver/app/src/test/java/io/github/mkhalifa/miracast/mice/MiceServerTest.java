package io.github.mkhalifa.miracast.mice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public class MiceServerTest {
    @Test(timeout = 10_000)
    public void tricklingClientIsCutAtTheEstablishmentDeadline() throws Exception {
        AtomicReference<String> reason = new AtomicReference<>();
        CountDownLatch ended = new CountDownLatch(1);
        MiceServer server = new MiceServer(new MiceServer.Listener() {
            @Override
            public void onSourceReady(MiceServer.Link link, String sourceIp, int rtspPort, String friendlyName) {
            }

            @Override
            public void onStopProjection(MiceServer.Link link, String why) {
                reason.set(why);
                ended.countDown();
            }
        }, 400);
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
             Socket accepted = ss.accept()) {
            MiceServer.Link link = server.new Link(accepted);
            long start = System.currentTimeMillis();
            Thread t = new Thread(link::serve);
            t.start();
            OutputStream out = client.getOutputStream();
            out.write(new byte[]{0, 100});
            out.flush();
            for (int i = 0; i < 30 && ended.getCount() > 0; i++) {
                Thread.sleep(100);
                try {
                    out.write(1);
                    out.flush();
                } catch (IOException e) {
                    break;
                }
            }
            assertTrue("link ended", ended.await(2, TimeUnit.SECONDS));
            assertEquals("session establishment timeout", reason.get());
            assertTrue("ended near the deadline", System.currentTimeMillis() - start < 2_000);
        }
    }
}
