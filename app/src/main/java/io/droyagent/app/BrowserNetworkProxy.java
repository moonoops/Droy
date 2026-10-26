package io.droyagent.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/** HTTP forward/CONNECT proxy: every TCP destination is validated and connected by its vetted IP. */
final class BrowserNetworkProxy implements Closeable {
    interface Resolver { InetAddress[] resolve(String host) throws IOException; }
    interface Connector { Socket connect(InetAddress address, int port) throws IOException; }
    private final Resolver resolver;
    private final Connector connector;
    private final ServerSocket server;
    private final ExecutorService clients = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "droy-browser-proxy"); thread.setDaemon(true); return thread; });
    private final Semaphore slots = new Semaphore(16);
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger rejectedRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger acceptedRequests = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger connectedDestinations = new java.util.concurrent.atomic.AtomicInteger();
    private volatile String lastRejection = "none";

    BrowserNetworkProxy() throws IOException { this(InetAddress::getAllByName); }

    BrowserNetworkProxy(Resolver resolver) throws IOException {
        this(resolver, (address, port) -> {
            Socket socket = new Socket();
            try { socket.connect(new InetSocketAddress(address, port), 8000); socket.setSoTimeout(45000); return socket; }
            catch (IOException e) { socket.close(); throw e; }
        });
    }

    // Injection is package-private and used only by deterministic network tests.
    BrowserNetworkProxy(Resolver resolver, Connector connector) throws IOException {
        this.resolver = resolver; this.connector = connector;
        server = new ServerSocket(); server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16);
        Thread acceptor = new Thread(this::accept, "droy-browser-proxy-accept"); acceptor.setDaemon(true); acceptor.start();
    }
    int port() { return server.getLocalPort(); }
    int rejectedRequests() { return rejectedRequests.get(); }
    int acceptedRequests() { return acceptedRequests.get(); }
    int connectedDestinations() { return connectedDestinations.get(); }
    String lastRejection() { return lastRejection; }

    private void accept() {
        while (!closed) {
            try {
                Socket client = server.accept();
                if (!slots.tryAcquire()) { client.close(); continue; }
                sockets.add(client); acceptedRequests.incrementAndGet();
                clients.execute(() -> { try { serve(client); } finally { sockets.remove(client); closeSocket(client); slots.release(); } });
            } catch (IOException e) { if (!closed) close(); }
            catch (java.util.concurrent.RejectedExecutionException stopping) { if (!closed) close(); }
        }
    }

    private void serve(Socket client) {
        boolean responseStarted = false;
        String stage = "request_headers";
        try {
            client.setSoTimeout(45000);
            InputStream incoming = new BufferedInputStream(client.getInputStream());
            List<String> lines = headers(incoming);
            if (lines.isEmpty()) return;
            String[] first = lines.get(0).split(" ", -1);
            if (first.length != 3 || !first[0].matches("[A-Z]{1,20}") || !(first[2].equals("HTTP/1.1") || first[2].equals("HTTP/1.0"))) throw new IOException("Invalid request");
            stage = "target_parse";
            boolean tunnel = first[0].equals("CONNECT");
            URI target = tunnel ? BrowserSession.UrlPolicy.parse("https://" + first[1]) : parseForward(first[1]);
            if (target.getRawFragment() != null || (tunnel && (target.getPort() < 1 || target.getRawQuery() != null
                || (target.getRawPath() != null && !target.getRawPath().isEmpty())))) throw new IOException("Invalid proxy target");
            int port = target.getPort() >= 0 ? target.getPort() : tunnel ? 443 : 80;
            stage = "forward_headers";
            List<String> forwarded = tunnel ? Collections.emptyList() : forwardedHeaders(lines, target);
            // Reject the entire DNS answer if any member is private. Connect never resolves a second time.
            stage = "dns_resolve";
            InetAddress[] addresses = resolver.resolve(unbracket(target.getHost()));
            stage = "address_check";
            if (addresses.length == 0 || addresses.length > 32) throw new IOException("Invalid DNS answer");
            for (InetAddress address : addresses) if (!BrowserSession.UrlPolicy.isPublicAddress(address)) throw new SecurityException("Non-public destination");
            stage = "connect";
            Socket remote = connect(addresses, port);
            connectedDestinations.incrementAndGet(); sockets.add(remote);
            try {
                stage = "request_forward";
                OutputStream upstream = remote.getOutputStream(), downstream = client.getOutputStream();
                if (tunnel) {
                    downstream.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); downstream.flush();
                } else {
                    String path = target.getRawPath(); if (path == null || path.isEmpty()) path = "/";
                    if (target.getRawQuery() != null) path += "?" + target.getRawQuery();
                    StringBuilder header = new StringBuilder(first[0]).append(' ').append(path).append(" HTTP/1.1\r\n");
                    for (String line : forwarded) header.append(line).append("\r\n");
                    header.append("\r\n"); upstream.write(header.toString().getBytes(StandardCharsets.ISO_8859_1)); upstream.flush();
                }
                responseStarted = true; stage = "relay";
                Thread upload = new Thread(() -> {
                    try { copy(incoming, remote.getOutputStream()); }
                    catch (IOException ended) { /* Closing either end terminates the tunnel. */ }
                    finally { try { remote.shutdownOutput(); } catch (IOException ended) { } }
                }, "droy-browser-upload"); upload.setDaemon(true); upload.start();
                copy(remote.getInputStream(), downstream);
            } finally { sockets.remove(remote); closeSocket(remote); }
        } catch (Exception rejected) {
            rejectedRequests.incrementAndGet();
            lastRejection = stage + ":" + rejected.getClass().getSimpleName() + ":" + safeReason(rejected.getMessage());
            if (!responseStarted) try {
                byte[] body = "Droy blocked this browser destination or the destination was unavailable.".getBytes(StandardCharsets.UTF_8);
                String response = "HTTP/1.1 403 Forbidden\r\nConnection: close\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + body.length + "\r\n\r\n";
                client.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII)); client.getOutputStream().write(body); client.getOutputStream().flush();
            } catch (IOException disconnected) { }
        }
    }

    private static String safeReason(String message) {
        if (message == null) return "no_detail";
        // Diagnostics are restricted to our fixed validation labels; network exception messages may contain hosts.
        switch (message) {
            case "Invalid request": case "Invalid proxy target": case "Invalid DNS answer":
            case "Non-public destination": case "Invalid header": case "Ambiguous body size":
            case "Unsupported body encoding": case "Ambiguous body framing": case "Truncated header":
            case "Invalid control character": case "Header too large": case "HTTPS requires CONNECT":
            case "No public destination connected": case "Browser proxy closed":
                return message;
            case "网页地址无效或过长": case "Invalid or oversized web address": return "invalid_url";
            case "浏览器仅允许不含登录凭据的 HTTP/HTTPS 网页地址": case "The browser accepts only HTTP/HTTPS URLs without login credentials": return "unsupported_url_shape";
            case "浏览器不允许访问本机或内网地址": case "The browser cannot access local or private network addresses": return "non_public_url";
            default: return "detail_withheld";
        }
    }

    private Socket connect(InetAddress[] addresses, int port) throws IOException {
        IOException last = new IOException("No public destination connected");
        for (int index = 0; index < Math.min(addresses.length, 4); index++) {
            if (closed) throw new IOException("Browser proxy closed");
            try { return connector.connect(addresses[index], port); } catch (IOException unavailable) { last = unavailable; }
        }
        throw last;
    }

    private static URI parseForward(String value) throws Exception {
        URI parsed = new URI(value);
        if ("ws".equalsIgnoreCase(parsed.getScheme())) value = "http" + value.substring(2);
        URI target = BrowserSession.UrlPolicy.parse(value);
        if (!"http".equalsIgnoreCase(target.getScheme())) throw new IOException("HTTPS requires CONNECT");
        return target;
    }

    private static List<String> forwardedHeaders(List<String> lines, URI target) throws IOException {
        List<String> result = new ArrayList<>();
        String length = null, transfer = null;
        boolean upgrade = false;
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index); int colon = line.indexOf(':');
            if (colon <= 0 || !line.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) throw new IOException("Invalid header");
            String key = line.substring(0, colon).toLowerCase(Locale.ROOT), value = line.substring(colon + 1).trim();
            if (key.equals("content-length")) { if (length != null || !value.matches("[0-9]{1,12}")) throw new IOException("Ambiguous body size"); length = value; }
            if (key.equals("transfer-encoding")) { if (transfer != null || !value.equalsIgnoreCase("chunked")) throw new IOException("Unsupported body encoding"); transfer = value; }
            if (key.equals("upgrade")) upgrade = value.equalsIgnoreCase("websocket");
            if (!key.equals("host") && !key.equals("connection") && !key.equals("proxy-connection") && !key.equals("proxy-authorization")) result.add(line);
        }
        if (length != null && transfer != null) throw new IOException("Ambiguous body framing");
        result.add("Host: " + target.getRawAuthority());
        result.add("Connection: " + (upgrade ? "Upgrade" : "close"));
        return result;
    }

    private static List<String> headers(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int matched = 0;
        while (bytes.size() < 32768) {
            int next = input.read(); if (next == -1) { if (bytes.size() == 0) return Collections.emptyList(); throw new IOException("Truncated header"); }
            if (next == 0 || (next < 32 && next != 9 && next != 10 && next != 13)) throw new IOException("Invalid control character");
            bytes.write(next);
            int expected = (matched == 0 || matched == 2) ? 13 : 10;
            matched = next == expected ? matched + 1 : next == 13 ? 1 : 0;
            if (matched == 4) {
                String all = bytes.toString(StandardCharsets.ISO_8859_1.name());
                return java.util.Arrays.asList(all.substring(0, all.length() - 4).split("\r\n", -1));
            }
        }
        throw new IOException("Header too large");
    }
    private static String unbracket(String host) { return host.startsWith("[") ? host.substring(1, host.length() - 1) : host; }
    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[16384]; int count;
        while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); output.flush(); }
    }
    private static void closeSocket(Socket socket) { try { socket.close(); } catch (IOException ignored) { } }
    @Override public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) { }
        for (Socket socket : sockets) closeSocket(socket);
        clients.shutdownNow();
    }
}
