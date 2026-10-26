package io.droyagent.app;

import android.app.NotificationChannel;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fixed channel operations run under the user's Root grant, outside the application UID. */
public final class NotificationChannelShell {
    private NotificationChannelShell() {}

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("root_required");
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = System.in.read(buffer)) != -1) {
                if (input.size() + count > 4 * 1024 * 1024) throw new IllegalArgumentException("request_too_large");
                input.write(buffer, 0, count);
            }
            JSONObject request = new JSONObject(input.toString(StandardCharsets.UTF_8.name()));
            Object manager = manager();
            JSONObject result;
            switch (request.getString("operation")) {
                case "read": result = read(manager, request.getJSONArray("packages")); break;
                case "apply": result = apply(manager, request.getJSONArray("changes"),
                        request.optJSONArray("packageChanges") == null ? new JSONArray() : request.getJSONArray("packageChanges")); break;
                default: throw new IllegalArgumentException("unknown_operation");
            }
            System.out.println(result);
            System.out.flush();
            // Binder's thread pool otherwise keeps app_process alive after main returns.
            System.exit(0);
        } catch (Exception failure) {
            System.out.println("{\"ok\":false,\"error\":\"channel_control_failed\"}");
            System.out.flush();
            System.exit(1);
        }
    }

    private static Object manager() throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "notification");
        return Class.forName("android.app.INotificationManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static List<?> channels(Object manager, String packageName, int uid) throws Exception {
        Object slice = Class.forName("android.app.INotificationManager")
                .getMethod("getNotificationChannelsForPackage", String.class, int.class, boolean.class)
                .invoke(manager, packageName, uid, false);
        return (List<?>) slice.getClass().getMethod("getList").invoke(slice);
    }

    private static JSONObject read(Object manager, JSONArray packages) throws Exception {
        JSONArray result = new JSONArray(), failed = new JSONArray();
        for (int i = 0; i < packages.length(); i++) {
            JSONObject app = packages.getJSONObject(i);
            String name = app.getString("packageName"); int uid = app.getInt("uid");
            checkPackage(name, uid);
            try {
                JSONArray items = new JSONArray();
                for (Object item : channels(manager, name, uid)) {
                    NotificationChannel channel = (NotificationChannel) item;
                    items.put(new JSONObject().put("id", channel.getId()).put("parcel", encode(channel))
                            .put("state", state(channel)));
                }
                result.put(new JSONObject().put("packageName", name).put("uid", uid).put("enabled", enabled(manager, name, uid)).put("channels", items));
            } catch (ReflectiveOperationException failure) { failed.put(name); }
        }
        return new JSONObject().put("ok", true).put("packages", result).put("failed", failed);
    }

    private static JSONObject apply(Object manager, JSONArray changes, JSONArray packageChanges) throws Exception {
        Method update = Class.forName("android.app.INotificationManager").getMethod("updateNotificationChannelForPackage",
                String.class, int.class, NotificationChannel.class);
        JSONArray applied = new JSONArray(), skipped = new JSONArray(), packagesApplied = new JSONArray();
        for (int i = 0; i < packageChanges.length(); i++) {
            JSONObject change = packageChanges.getJSONObject(i);
            if (!change.getBoolean("enabled") && setEnabled(manager, change)) packagesApplied.put(change.getString("packageName"));
        }
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.getJSONObject(i);
            String name = change.getString("packageName"), id = change.getString("id");
            int uid = change.getInt("uid"); checkPackage(name, uid);
            NotificationChannel current = null;
            for (Object item : channels(manager, name, uid))
                if (((NotificationChannel) item).getId().equals(id)) { current = (NotificationChannel) item; break; }
            String key = name + "\n" + id;
            if (current == null || !sameState(state(current), change.getJSONObject("expected"))) {
                skipped.put(key); continue;
            }
            NotificationChannel desired = decode(change.getString("desired"));
            if (!desired.getId().equals(id)) throw new IllegalArgumentException("channel_id_changed");
            current.setImportance(desired.getImportance()); current.setSound(desired.getSound(), desired.getAudioAttributes());
            current.enableVibration(desired.shouldVibrate());
            if (current.canBubble() != desired.canBubble()) current.setAllowBubbles(desired.canBubble());
            update.invoke(manager, name, uid, current);
            boolean verified = false;
            for (Object item : channels(manager, name, uid))
                if (((NotificationChannel) item).getId().equals(id)) {
                    verified = sameState(state((NotificationChannel) item), state(desired)); break;
                }
            if (verified) applied.put(key); else skipped.put(key);
        }
        for (int i = 0; i < packageChanges.length(); i++) {
            JSONObject change = packageChanges.getJSONObject(i);
            if (change.getBoolean("enabled") && setEnabled(manager, change)) packagesApplied.put(change.getString("packageName"));
        }
        return new JSONObject().put("ok", true).put("applied", applied).put("skipped", skipped).put("packagesApplied", packagesApplied);
    }

    private static boolean enabled(Object manager, String name, int uid) throws Exception {
        return (Boolean) Class.forName("android.app.INotificationManager").getMethod("areNotificationsEnabledForPackage", String.class, int.class)
                .invoke(manager, name, uid);
    }

    private static boolean setEnabled(Object manager, JSONObject change) throws Exception {
        String name = change.getString("packageName"); int uid = change.getInt("uid"); checkPackage(name, uid);
        if (enabled(manager, name, uid) != change.getBoolean("expected")) return false;
        if (change.optBoolean("requireQuiet")) for (Object item : channels(manager, name, uid))
            if (!quiet(state((NotificationChannel) item))) return false;
        boolean desired = change.getBoolean("enabled");
        Class.forName("android.app.INotificationManager").getMethod("setNotificationsEnabledForPackage", String.class, int.class, boolean.class)
                .invoke(manager, name, uid, desired);
        return enabled(manager, name, uid) == desired;
    }

    private static boolean quiet(JSONObject state) {
        int importance = state.optInt("importance", -1);
        return importance == 0 || importance > 0 && importance <= 2 && state.isNull("sound")
                && !state.optBoolean("vibration") && !state.optBoolean("bubbles");
    }

    static JSONObject state(NotificationChannel channel) throws Exception {
        return new JSONObject().put("importance", channel.getImportance())
                .put("sound", channel.getSound() == null ? JSONObject.NULL : channel.getSound().toString())
                .put("vibration", channel.shouldVibrate()).put("bubbles", channel.canBubble());
    }

    static boolean sameState(JSONObject first, JSONObject second) {
        for (String key : new String[]{"importance", "sound", "vibration", "bubbles"})
            if (!java.util.Objects.equals(first.opt(key), second.opt(key))) return false;
        return true;
    }

    static String encode(NotificationChannel channel) {
        Parcel parcel = Parcel.obtain();
        try { channel.writeToParcel(parcel, 0); return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP); }
        finally { parcel.recycle(); }
    }

    static NotificationChannel decode(String encoded) {
        byte[] bytes = Base64.decode(encoded, Base64.DEFAULT); Parcel parcel = Parcel.obtain();
        try { parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0); return NotificationChannel.CREATOR.createFromParcel(parcel); }
        finally { parcel.recycle(); }
    }

    private static void checkPackage(String name, int uid) {
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+") || uid < 10000
                || DroyPackages.contains(name) || name.equals("com.android.systemui") || name.equals("com.android.phone"))
            throw new SecurityException("protected_package");
    }
}
