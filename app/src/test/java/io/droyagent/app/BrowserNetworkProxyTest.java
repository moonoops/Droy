package io.droyagent.app;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class BrowserNetworkProxyTest {
    private static final byte[] PUBLIC = {(byte)93, (byte)184, (byte)216, 34};

    @Test public void privateHttpAndTlsWebSocketDestinationsNeverConnect() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (BrowserNetworkProxy proxy = new BrowserNetworkProxy(InetAddress::getAllByName, (address, port) -> {
            connections.incrementAndGet(); throw new AssertionError("Private destination connected");
        })) {
            for (String request : new String[]{
                "GET http://127.0.0.1:8787/private HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
                "GET ws://127.0.0.1:8787/socket HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\n\r\n",
                "CONNECT 127.0.0.1:8787 HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n",
                "CONNECT [::1]:8787 HTTP/1.1\r\nHost: [::1]\r\n\r\n"}) {
                assertTrue(exchange(proxy.port(), request).startsWith("HTTP/1.1 403"));
            }
            assertEquals(0, connections.get());
            assertEquals(4, proxy.rejectedRequests());
        }
    }

    @Test public void dnsRebindingIsRecheckedAndConnectedAddressIsPinned() throws Exception {
        AtomicInteger resolutions = new AtomicInteger(), connections = new AtomicInteger();
        try (ServerSocket origin = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             BrowserNetworkProxy proxy = new BrowserNetworkProxy(host -> new InetAddress[]{resolutions.getAndIncrement() == 0
                 ? InetAddress.getByAddress(host, PUBLIC) : InetAddress.getByName("127.0.0.1")}, (address, port) -> {
                     assertArrayEquals(PUBLIC, address.getAddress()); connections.incrementAndGet();
                     return new Socket("127.0.0.1", origin.getLocalPort());
                 })) {
            Thread fixture = new Thread(() -> {
                try (Socket socket = origin.accept()) {
                    readHeaders(socket.getInputStream());
                    socket.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: http://rebind.example/second\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) { throw new RuntimeException(e); }
            }); fixture.setDaemon(true); fixture.start();
            assertTrue(exchange(proxy.port(), "GET http://rebind.example/first HTTP/1.1\r\nHost: rebind.example\r\n\r\n").startsWith("HTTP/1.1 302"));
            assertTrue(exchange(proxy.port(), "GET http://rebind.example/second HTTP/1.1\r\nHost: rebind.example\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertEquals(2, resolutions.get()); assertEquals(1, connections.get());
            fixture.join(2000); assertFalse(fixture.isAlive());
        }
    }

    @Test public void mixedPublicPrivateDnsAnswerFailsBeforeAnyConnection() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (BrowserNetworkProxy proxy = new BrowserNetworkProxy(host -> new InetAddress[]{InetAddress.getByAddress(PUBLIC), InetAddress.getByName("10.0.0.1")}, (address, port) -> {
            connections.incrementAndGet(); throw new AssertionError("Mixed DNS answer connected");
        })) {
            assertTrue(exchange(proxy.port(), "CONNECT public.example:443 HTTP/1.1\r\nHost: public.example\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertEquals(0, connections.get());
        }
    }

    @Test public void publicConnectTunnelPreservesTlsBytesForWebViewVerification() throws Exception {
        try (ServerSocket origin = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             BrowserNetworkProxy proxy = new BrowserNetworkProxy(host -> new InetAddress[]{InetAddress.getByAddress(PUBLIC)}, (address, port) -> new Socket("127.0.0.1", origin.getLocalPort()))) {
            Thread fixture = new Thread(() -> {
                try (Socket socket = origin.accept()) { byte[] ping = new byte[4]; new java.io.DataInputStream(socket.getInputStream()).readFully(ping); socket.getOutputStream().write(ping); }
                catch (Exception e) { throw new RuntimeException(e); }
            }); fixture.setDaemon(true); fixture.start();
            try (Socket client = new Socket("127.0.0.1", proxy.port())) {
                client.setSoTimeout(3000);
                client.getOutputStream().write("CONNECT public.example:443 HTTP/1.1\r\nHost: public.example\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertTrue(readHeaders(client.getInputStream()).startsWith("HTTP/1.1 200"));
                client.getOutputStream().write(new byte[]{22, 3, 3, 1});
                byte[] received = new byte[4]; new java.io.DataInputStream(client.getInputStream()).readFully(received);
                assertArrayEquals(new byte[]{22, 3, 3, 1}, received);
            }
            fixture.join(2000); assertFalse(fixture.isAlive());
        }
    }

    @Test public void hostHeaderCannotOverrideValidatedAuthorityOrLeakProxyCredentials() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> originHeaders = new java.util.concurrent.atomic.AtomicReference<>();
        try (ServerSocket origin = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
             BrowserNetworkProxy proxy = new BrowserNetworkProxy(host -> new InetAddress[]{InetAddress.getByAddress(PUBLIC)}, (address, port) -> {
                 assertArrayEquals(PUBLIC, address.getAddress()); return new Socket("127.0.0.1", origin.getLocalPort());
             })) {
            Thread fixture = new Thread(() -> {
                try (Socket socket = origin.accept()) {
                    originHeaders.set(readHeaders(socket.getInputStream()));
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes(StandardCharsets.US_ASCII));
                } catch (Exception e) { throw new RuntimeException(e); }
            }); fixture.setDaemon(true); fixture.start();
            assertTrue(exchange(proxy.port(), "GET http://public.example/path?q=hello HTTP/1.1\r\nHost: 127.0.0.1:8787\r\nProxy-Authorization: synthetic-test-value\r\n\r\n").startsWith("HTTP/1.1 200"));
            fixture.join(2000);
            assertTrue(originHeaders.get().contains("GET /path?q=hello HTTP/1.1"));
            assertTrue(originHeaders.get().contains("Host: public.example\r\n"));
            assertFalse(originHeaders.get().contains("127.0.0.1"));
            assertFalse(originHeaders.get().contains("synthetic-test-value"));
        }
    }

    @Test public void malformedConnectAuthorityIsRejectedBeforeDnsOrSocket() throws Exception {
        AtomicInteger dns = new AtomicInteger();
        try (BrowserNetworkProxy proxy = new BrowserNetworkProxy(host -> { dns.incrementAndGet(); return new InetAddress[]{InetAddress.getByAddress(PUBLIC)}; }, (address, port) -> { throw new AssertionError("Malformed CONNECT connected"); })) {
            for (String authority : new String[]{"public.example:443/path", "public.example:443?ignored", "public.example", "public.example:443@127.0.0.1:443", "public.example:443#fragment"})
                assertTrue(exchange(proxy.port(), "CONNECT " + authority + " HTTP/1.1\r\nHost: public.example\r\n\r\n").startsWith("HTTP/1.1 403"));
            assertEquals(0, dns.get());
        }
    }

    private static String exchange(int port, String request) throws Exception {
        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setSoTimeout(5000); client.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[1024]; int count;
            while ((count = client.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, count);
            return bytes.toString(StandardCharsets.ISO_8859_1.name());
        }
    }
    private static String readHeaders(InputStream input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int last = 0;
        while (bytes.size() < 32768) { int next = input.read(); if (next == -1) break; bytes.write(next); last = (last << 8) | next; if (last == 0x0d0a0d0a) break; }
        return bytes.toString(StandardCharsets.ISO_8859_1.name());
    }
}
