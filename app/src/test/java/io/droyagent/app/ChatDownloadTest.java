package io.droyagent.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatDownloadTest {
    private ServerSocket server;
    private Thread serving;
    private volatile Handler handler = (headers, output) -> { throw new IOException("Unexpected request"); };
    private int port;
    private String origin;

    @Before public void start() throws Exception {
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        port = server.getLocalPort(); origin = "http://127.0.0.1:" + port;
        serving = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    handler.respond(headers(socket.getInputStream()), socket.getOutputStream());
                } catch (IOException error) { if (!server.isClosed()) throw new RuntimeException(error); }
            }
        });
        serving.setDaemon(true); serving.start();
    }

    @After public void stop() throws Exception { server.close(); serving.join(3000); assertFalse(serving.isAlive()); }

    private interface Handler { void respond(String headers, java.io.OutputStream output) throws IOException; }

    private static String headers(InputStream input) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (result.size() < 16384) {
            int next = input.read();
            if (next < 0) throw new IOException("Missing request headers");
            result.write(next);
            String value = result.toString(StandardCharsets.US_ASCII.name());
            if (value.endsWith("\r\n\r\n")) return value;
        }
        throw new IOException("Request headers too large");
    }

    private static void response(java.io.OutputStream output, String status, String extra, int length, byte[] bytes) throws IOException {
        output.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: " + length + "\r\n" + extra + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
    }

    @Test public void streamsTheAuthenticatedResponse() throws Exception {
        byte[] content = "session archive fixture".repeat(10000).getBytes(StandardCharsets.UTF_8);
        AtomicReference<String> request = new AtomicReference<>();
        handler = (headers, output) -> { request.set(headers); response(output, "200 OK", "", content.length, content); };
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(content.length, ChatDownload.copy(origin + "/export", port, "droy_session=fictional", output, () -> true));
        assertTrue(request.get().contains("Cookie: droy_session=fictional\r\n"));
        assertArrayEquals(content, output.toByteArray());
    }

    @Test public void neverFollowsRedirectsWithRuntimeCookies() {
        AtomicInteger redirected = new AtomicInteger();
        handler = (headers, output) -> {
            if (!headers.startsWith("GET /redirect ")) redirected.incrementAndGet();
            response(output, "302 Found", "Location: " + origin + "/other\r\n", 0, new byte[0]);
        };
        IOException failure = assertThrows(IOException.class, () -> ChatDownload.copy(origin + "/redirect", port,
            "droy_session=fictional", new ByteArrayOutputStream(), () -> true));
        assertTrue(failure.getMessage().contains("302"));
        assertEquals(0, redirected.get());
    }

    @Test public void rejectsStaleSessionAndDifferentAuthorityBeforeAnyRequest() {
        AtomicInteger requests = new AtomicInteger();
        handler = (headers, output) -> { requests.incrementAndGet(); response(output, "200 OK", "", 0, new byte[0]); };
        assertThrows(IOException.class, () -> ChatDownload.copy(origin + "/export", port,
            "fictional", new ByteArrayOutputStream(), () -> false));
        assertThrows(IOException.class, () -> ChatDownload.copy(origin + "/export", port + 1,
            "fictional", new ByteArrayOutputStream(), () -> true));
        assertEquals(0, requests.get());
    }

    @Test public void rejectsPartialArchivesAndAuthenticationFailures() {
        handler = (headers, output) -> {
            if (headers.startsWith("GET /partial ")) response(output, "200 OK", "", 20, new byte[]{1, 2, 3});
            else response(output, "401 Unauthorized", "", 0, new byte[0]);
        };
        assertThrows(IOException.class, () -> ChatDownload.copy(origin + "/partial", port,
            "fictional", new ByteArrayOutputStream(), () -> true));
        IOException failure = assertThrows(IOException.class, () -> ChatDownload.copy(origin + "/denied", port,
            "fictional", new ByteArrayOutputStream(), () -> true));
        assertTrue(failure.getMessage().contains("401"));
    }
}
