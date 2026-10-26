package io.droyagent.app;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;

/** Validation shared by the WebView's user-operated file transfer controls. */
final class ChatFilePolicy {
    private ChatFilePolicy() {}

    static boolean isLocalDownload(String address, int port) {
        try {
            URI uri = new URI(address);
            return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost())
                && uri.getPort() == port && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (Exception malformed) { return false; }
    }

    static boolean isExternalLink(String address) {
        try {
            URI uri = new URI(address);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (Exception malformed) { return false; }
    }

    static boolean isContentUri(String address) {
        try {
            URI uri = new URI(address);
            return "content".equals(uri.getScheme()) && uri.getRawAuthority() != null
                && !uri.getRawAuthority().trim().isEmpty() && uri.getRawAuthority().indexOf('@') < 0
                && uri.getFragment() == null;
        } catch (Exception malformed) { return false; }
    }

    static String mimeType(String value) {
        if (value == null) return "application/octet-stream";
        String mime = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return mime.matches("[a-z0-9!#$&^_.+\\-]+/[a-z0-9!#$&^_.+\\-]+") ? mime : "application/octet-stream";
    }

    static String[] acceptedMimeTypes(String[] values) {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        if (values != null) for (String value : values) {
            if (value == null) continue;
            for (String part : value.split(",")) {
                String mime = part.trim().toLowerCase(Locale.ROOT);
                if (mime.matches("(?:[a-z0-9!#$&^_.+\\-]+|\\*)/(?:[a-z0-9!#$&^_.+\\-]+|\\*)")) types.add(mime);
            }
        }
        return types.toArray(new String[0]);
    }

    static String filename(String suggested) {
        if (suggested == null) return "dsh-download";
        String name = suggested.replaceAll("[\\\\/\\p{Cntrl}]", "_").trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "dsh-download";
        return name.length() <= 180 ? name : name.substring(0, 180);
    }
}
