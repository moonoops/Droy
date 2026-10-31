package io.droyagent.app;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public final class CompanionRuntimeActivityTest {
    @Test public void aBusyHostRefusesIdleShutdownAndOnlyAValidExitReceiptIsAccepted() throws Exception {
        AtomicInteger status = new AtomicInteger(409);
        AtomicReference<String> response = new AtomicReference<>("{\"ok\":false,\"code\":\"DROY_RUNTIME_BUSY\"}");
        AtomicReference<String> request = new AtomicReference<>(), cookie = new AtomicReference<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        ServerSocket server = new ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"));
        Thread responder = new Thread(() -> {
            try {
                for (int round = 0; round < 3; round++) try (java.net.Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    assertEquals("POST /droy/shutdown HTTP/1.1", input.readLine());
                    int length = 0;
                    for (String line; (line = input.readLine()) != null && !line.isEmpty();) {
                        String lower = line.toLowerCase(java.util.Locale.ROOT);
                        if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                        if (lower.startsWith("cookie:")) cookie.set(line.substring(line.indexOf(':') + 1).trim());
                    }
                    char[] body = new char[length];
                    int read = 0;
                    while (read < length) { int count = input.read(body, read, length - read); if (count < 0) break; read += count; }
                    request.set(new String(body));
                    byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 " + status.get() + " Fixture\r\nContent-Length: " + bytes.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
                }
            } catch (Throwable error) { serverFailure.set(error); }
        }, "companion-idle-test");
        responder.setDaemon(true); responder.start();
        try {
            int port = server.getLocalPort();
            assertFalse(CompanionRuntimeActivity.requestIdleShutdown(port, "fictional-idle-fixture"));
            assertTrue(new JSONObject(request.get()).getBoolean("ifIdle"));
            assertEquals("droy_session=fictional-idle-fixture", cookie.get());
            status.set(200); response.set("{\"ok\":true,\"code\":\"DROY_SHUTDOWN_READY\"}");
            assertTrue(CompanionRuntimeActivity.requestIdleShutdown(port, "fictional-idle-fixture"));
            response.set("{\"ok\":true}");
            try {
                CompanionRuntimeActivity.requestIdleShutdown(port, "fictional-idle-fixture");
                fail("A missing exit receipt must not authorize a forced stop");
            } catch (JSONException expected) { }
        } finally { server.close(); responder.join(3000); }
        if (serverFailure.get() != null) throw new AssertionError(serverFailure.get());
    }

    @Test public void onlyAnExplicitIdleObservationAllowsBackgroundRelease() throws Exception {
        assertFalse(CompanionRuntimeActivity.decode(new JSONObject().put("busy", false)));
        assertTrue(CompanionRuntimeActivity.decode(new JSONObject().put("busy", true)));
        for (Object value : new Object[] { JSONObject.NULL, "false", 0 }) {
            try {
                CompanionRuntimeActivity.decode(new JSONObject().put("busy", value));
                fail("Invalid activity must not be accepted as idle");
            } catch (JSONException expected) { }
        }
        try {
            CompanionRuntimeActivity.decode(new JSONObject());
            fail("An absent activity observation must not be accepted as idle");
        } catch (JSONException expected) { }
    }
}
