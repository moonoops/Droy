package io.droyagent.app;

import java.net.URI;
import org.json.JSONObject;

/** The UI bridge accepts messages only from this engine session's top-level document. */
final class NativeUiProtocol {
    private NativeUiProtocol() {}

    static boolean trustedOrigin(String origin, int port, boolean mainFrame) {
        if (!mainFrame || port <= 0 || port > 65535 || origin == null) return false;
        try {
            URI uri = URI.create(origin);
            String path = uri.getRawPath();
            return "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() == port
                && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && (path == null || path.isEmpty() || path.equals("/"));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    static boolean trustedMicrophoneRequest(String origin, int port, String[] resources) {
        return trustedOrigin(origin, port, true) && resources != null && resources.length == 1
            && "android.webkit.resource.AUDIO_CAPTURE".equals(resources[0]);
    }

    static boolean trustedCameraRequest(String origin, int port, String[] resources) {
        return trustedOrigin(origin, port, true) && resources != null && resources.length == 1
            && "android.webkit.resource.VIDEO_CAPTURE".equals(resources[0]);
    }

    static JSONObject parse(String data) throws Exception {
        if (data == null || data.length() > 16384) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("界面消息过大或无效", "UI message is invalid or too large"));
        JSONObject request = new JSONObject(data);
        Object id = request.opt("id");
        if (!(id instanceof String) || ((String) id).isEmpty() || ((String) id).length() > 128)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("界面消息编号无效", "Invalid UI message ID"));
        String action = request.getString("action");
        if (!action.matches("[A-Za-z]{1,64}")) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("界面操作无效", "Invalid UI action"));
        if (request.has("args") && !(request.opt("args") instanceof JSONObject))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("界面操作参数无效", "Invalid UI action arguments"));
        return request;
    }

    static boolean booleanArgument(JSONObject args, String key) throws Exception {
        Object value = args.get(key);
        if (!(value instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("此设置需要开关值", "This setting requires a boolean value"));
        return (Boolean) value;
    }

    static String memoryArchiveDownload(JSONObject args, int port) throws Exception {
        if (args.length()!=1 || !(args.opt("archiveId") instanceof String id) || !id.matches("[a-f0-9]{64}") || port<1 || port>65535)
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("记忆备份编号无效", "Invalid memory backup ID"));
        return "http://127.0.0.1:"+port+"/droy/memory/archive/download/"+id;
    }

    static String petDisplayArgument(JSONObject args) throws Exception {
        Object value = args.get("value");
        if (!(value instanceof String mode) || !java.util.Set.of("always", "adaptive", "off").contains(mode))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("宠物显示方式无效", "Invalid companion display mode"));
        return (String) value;
    }

    static String backgroundColorArgument(JSONObject args) throws Exception {
        Object value = args.get("backgroundColor");
        if (!(value instanceof String) || !((String) value).matches("#[0-9A-Fa-f]{6}"))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("主题背景颜色无效", "Invalid theme background color"));
        return ((String) value).toUpperCase(java.util.Locale.ROOT);
    }
}
