package io.droyagent.app;

import android.content.Context;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** One catalogue supplies the product controls and the model's registered tools. */
final class CapabilityCatalog {
    final JSONObject document;
    private final Map<String, JSONObject> tools = new LinkedHashMap<>();
    private final Map<String, JSONObject> groups = new LinkedHashMap<>();

    CapabilityCatalog(Context context) {
        try (InputStream input = context.getAssets().open("droy-capabilities.json")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int size;
            while ((size = input.read(buffer)) != -1) {
                if (bytes.size() + size > 131072) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("能力目录过大", "Capability catalog is too large"));
                bytes.write(buffer, 0, size);
            }
            document = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            JSONArray entries = document.getJSONArray("groups");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                if (groups.put(item.getString("id"), item) != null) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("重复能力类别", "Duplicate capability category"));
            }
            entries = document.getJSONArray("tools");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                if (tools.put(item.getString("action"), item) != null) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("重复工具", "Duplicate tool"));
                JSONArray permissions = item.getJSONArray("permissions");
                for (int p = 0; p < permissions.length(); p++) group(permissions.getString(p));
                JSONObject operations = item.has("operationPermissions") ? item.getJSONObject("operationPermissions") : null;
                if (operations != null) for (java.util.Iterator<String> keys = operations.keys(); keys.hasNext();) {
                    JSONArray required = operations.getJSONArray(keys.next());
                    for (int p = 0; p < required.length(); p++) group(required.getString(p));
                }
            }
        } catch (Exception error) { throw new IllegalStateException(io.droyagent.app.NativeLocale.text("手机能力目录无法加载", "Unable to load phone capability catalog"), error); }
    }

    JSONObject tool(String action) {
        JSONObject tool = tools.get(action);
        if (tool == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的工具", "Unsupported tool"));
        return tool;
    }
    JSONObject group(String id) {
        JSONObject group = groups.get(id);
        if (group == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的能力类别", "Unsupported capability category"));
        return localizedGroup(group);
    }
    JSONArray groups() {
        JSONArray result = new JSONArray();
        for (JSONObject group : groups.values()) result.put(localizedGroup(group));
        return result;
    }
    private static JSONObject localizedGroup(JSONObject source) {
        try {
            JSONObject result = new JSONObject(source.toString());
            String[] english = switch (source.getString("id")) {
                case "device" -> new String[]{"Phone status", "Phone status", "Battery, network and storage; excludes unique device identifiers."};
                case "apps" -> new String[]{"App information and permissions", "Phone status", "Query app names, versions and permissions, including apps without launcher icons."};
                case "observe" -> new String[]{"Read screen text", "Screen operations", "Read text from the current app screen."};
                case "diagnostics" -> new String[]{"Exit and battery diagnostics", "Phone status", "Requires desktop authorization; battery statistics also need usage access. Apps and statistics are not changed."};
                case "privacy" -> new String[]{"Microphone and camera access history", "Personal data", "Requires desktop authorization and usage access; queries only records retained by the system."};
                case "control" -> new String[]{"Control screen", "Screen operations", "Open apps, tap, swipe and type; enable screen reading separately."};
                case "screenshot" -> new String[]{"Screenshots", "Screen operations", "Send the screen image to the current model; requires screen reading and an image-capable model."};
                case "root" -> new String[]{"Root capabilities", "Screen operations", "Use supported screen, diagnostics and process operations, checking system authorization each time."};
                case "settingsRead" -> new String[]{"Read volume and brightness", "System settings", "Query supported settings only."};
                case "settingsWrite" -> new String[]{"Adjust volume and brightness", "System settings", "Save original values and verify results; check for later manual changes before restoring."};
                case "notifications" -> new String[]{"Read notifications", "Personal data", "Read current notifications for tasks."};
                case "calendar" -> new String[]{"Read calendar", "Personal data", "Read-only events for specified dates; requires system calendar permission."};
                case "contacts" -> new String[]{"Find contacts", "Personal data", "Return name matches; requires system contacts permission."};
                case "usage" -> new String[]{"App usage and network traffic", "Personal data", "Query app activity and network statistics; requires usage access."};
                case "files" -> new String[]{"Read selected files", "Selected files", "Access only files or directories authorized through the system picker."};
                case "fileExport" -> new String[]{"Export files", "Selected files", "Write to the selected directory without overwriting existing files."};
                case "browser" -> new String[]{"Read web pages", "Browser and computation", "Read built-in browser content in a session separate from external browsers."};
                case "browserControl" -> new String[]{"Control web pages", "Browser and computation", "Open, click and type in the built-in browser."};
                case "compute" -> new String[]{"Local data computation", "Browser and computation", "Process this operation’s data in a separate unprivileged process."};
                case "audioMicrophone" -> new String[]{"Record microphone", "Audio", "Requires microphone permission; stop and choose whether to save the recording."};
                case "audioPlayback" -> new String[]{"Record playback audio", "Audio", "Requires system capture authorization; some apps prohibit playback capture."};
                case "audioTranscription" -> new String[]{"Local transcription", "Audio", "Transcribe recordings or live audio on the phone."};
                case "memoryRead" -> new String[]{"Read app process data", "App process data", "Query only the task’s specified app and send relevant results to the model; requires Root."};
                case "memoryWrite" -> new String[]{"Change one process value", "App process data", "Change one located value and verify readback; requires process reading and Root. Values are not frozen."};
                default -> new String[]{source.getString("labelEn"), source.getString("sectionEn"), source.getString("descriptionEn")};
            };
            String[] fields = {"label", "section", "description"};
            for (int index = 0; index < fields.length; index++)
                result.put(fields[index], NativeLocale.text(source.getString(fields[index]), english[index]));
            return result;
        } catch (org.json.JSONException failure) { throw new IllegalStateException("Invalid capability text", failure); }
    }
    JSONArray tools() { return document.optJSONArray("tools"); }
}
