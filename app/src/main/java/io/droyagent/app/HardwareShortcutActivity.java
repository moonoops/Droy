package io.droyagent.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.TextView;
import io.droyagent.androiduse.Cancellation;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Pinned entries refer only to saved data or explicit device commands. */
public final class HardwareShortcutActivity extends Activity {
    private static SharedPreferences records(Context context) {
        return context.getSharedPreferences("device-shortcuts", Context.MODE_PRIVATE);
    }
    static JSONObject request(Context context, JSONObject args, Cancellation cancellation) throws Exception {
        String operation = args.getString("operation");
        SharedPreferences preferences = records(context);
        ShortcutManager manager = context.getSystemService(ShortcutManager.class);
        if (operation.equals("list")) {
            JSONArray rows = new JSONArray();
            for (String key : preferences.getAll().keySet()) {
                JSONObject record = new JSONObject(preferences.getString(key, "{}"));
                rows.put(new JSONObject().put("shortcutId", key).put("label", record.getString("label")).put("target", record.getString("target")));
            }
            return new JSONObject().put("shortcuts", rows);
        }
        if (operation.equals("delete")) {
            String id = args.getString("shortcutId");
            if (!preferences.contains(id)) throw new IllegalArgumentException("shortcutId");
            cancellation.dispatched();
            if (!preferences.edit().remove(id).commit()) throw new IllegalStateException(NativeLocale.text("快捷方式未删除", "Shortcut was not deleted"));
            if (manager != null) manager.disableShortcuts(java.util.List.of(id));
            return new JSONObject().put("status", "deleted").put("shortcutId", id);
        }
        if (!operation.equals("create")) throw new IllegalArgumentException("operation");
        if (manager == null || !manager.isRequestPinShortcutSupported())
            throw new IllegalStateException(NativeLocale.text("当前桌面不支持固定快捷方式", "The current launcher does not support pinned shortcuts"));
        if (preferences.getAll().size() >= 32) throw new IllegalStateException(NativeLocale.text("请先删除旧快捷方式", "Delete an existing shortcut first"));
        String target = args.getString("target"), label = args.getString("label").strip();
        if (!Set.of("infrared", "nfc_emulation", "device_data").contains(target) || label.isBlank() || label.length() > 80)
            throw new IllegalArgumentException("target/label");
        check(context, target);
        JSONObject command = validateCommand(context, target, args.getJSONObject("command"), cancellation);
        String id = UUID.randomUUID().toString();
        JSONObject record = new JSONObject().put("target", target).put("label", label).put("command", command);
        Intent intent = new Intent(context, HardwareShortcutActivity.class).setAction("io.droyagent.OPEN_DEVICE_SHORTCUT").putExtra("shortcutId", id);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(context, id).setShortLabel(label)
            .setIcon(Icon.createWithResource(context, R.drawable.droy_brand_launcher)).setIntent(intent).build();
        cancellation.check();
        if (!preferences.edit().putString(id, record.toString()).commit()) throw new IllegalStateException(NativeLocale.text("快捷方式未保存", "Shortcut was not saved"));
        cancellation.dispatched();
        boolean requested = manager.requestPinShortcut(shortcut, null);
        if (!requested) preferences.edit().remove(id).commit();
        return new JSONObject().put("status", requested ? "confirmation_requested" : "rejected")
            .put("shortcutId", id).put("pinnedVerified", false);
    }
    static JSONObject validateCommand(Context context, String target, JSONObject source, Cancellation cancellation) throws Exception {
        JSONObject command = new JSONObject();
        Set<String> keys = switch (target) {
            case "infrared" -> Set.of("carrierHz", "patternMicros", "dataRef", "repeat", "intervalMs");
            case "nfc_emulation" -> Set.of("profileId");
            case "device_data" -> Set.of("dataRef", "offset", "limit");
            default -> throw new IllegalArgumentException("target");
        };
        for (String key : keys) if (source.has(key)) command.put(key, source.get(key));
        if (command.has("dataRef")) {
            if (!ProductPermissions.allowed(ProductPermissions.preferences(context), "allowDeviceDataRead"))
                throw new SecurityException(NativeLocale.text("请允许读取已保存设备数据", "Allow reading saved device data"));
            JSONObject saved = new io.droyagent.androiduse.DeviceDataStore(context).execute(new JSONObject().put("operation", "read")
                .put("dataRef", command.getString("dataRef")).put("limit", 1), "shortcut-validation", cancellation);
            if (!saved.optBoolean("persistent")) throw new IllegalArgumentException(NativeLocale.text("快捷方式需要永久保存的数据", "Shortcuts require saved data"));
        }
        if (target.equals("nfc_emulation")) new HceProfiles(context).load(command.getString("profileId"));
        if (target.equals("infrared")) {
            if (!command.has("carrierHz")) throw new IllegalArgumentException("carrierHz");
            io.droyagent.androiduse.HardwareSupport.integer(command, "carrierHz", -1, 1000, 1000000);
            int repeat = io.droyagent.androiduse.HardwareSupport.integer(command, "repeat", 1, 1, 5);
            int interval = io.droyagent.androiduse.HardwareSupport.integer(command, "intervalMs", 100, 0, 2000);
            if (command.has("patternMicros") == command.has("dataRef")) throw new IllegalArgumentException("patternMicros/dataRef");
            JSONArray pattern = command.has("patternMicros") ? command.getJSONArray("patternMicros") : new JSONArray(new String(
                new io.droyagent.androiduse.DeviceDataStore(context).readPayload(command, "shortcut-validation", 65536), java.nio.charset.StandardCharsets.UTF_8));
            int[] durations = io.droyagent.androiduse.InfraredTools.waveform(pattern);
            long micros = 0;
            for (int duration : durations) micros += duration;
            if (micros >= 2000000 || (micros * repeat + 999) / 1000 + (long) interval * (repeat - 1) > 10000)
                throw new IllegalArgumentException("patternMicros/repeat/intervalMs");
        }
        if (target.equals("device_data") && !command.has("dataRef")) throw new IllegalArgumentException("dataRef");
        command.put("operation", target.equals("infrared") ? "transmit" : target.equals("nfc_emulation") ? "enable" : "read");
        return command;
    }
    private static void check(Context context, String target) {
        String preference = switch (target) {
            case "infrared" -> "allowInfrared";
            case "nfc_emulation" -> "allowNfcEmulation";
            case "device_data" -> "allowDeviceDataRead";
            default -> throw new IllegalArgumentException("target");
        };
        if (ProductPermissions.stopped(context) || !ProductPermissions.allowed(ProductPermissions.preferences(context), preference))
            throw new SecurityException(NativeLocale.text("该手机能力已暂停或未获授权", "This device capability is paused or not authorized"));
    }
    @Override public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        android.widget.LinearLayout content = NfcUi.body(this);
        TextView text = NfcUi.text(this, NativeLocale.text("正在打开设备快捷方式…", "Opening device shortcut…"), 16);
        content.addView(text); NfcUi.show(this, content);
        String id = getIntent().getStringExtra("shortcutId");
        new Thread(() -> {
            try {
                if (id == null || !records(this).contains(id)) throw new IllegalArgumentException(NativeLocale.text("快捷方式已删除", "Shortcut was deleted"));
                JSONObject record = new JSONObject(records(this).getString(id, "{}"));
                String target = record.getString("target");
                Cancellation active = () -> {
                    if (isFinishing() || isDestroyed() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    check(this, target);
                };
                active.check();
                JSONObject result = HardwareRuntime.get(this).execute(target, record.getJSONObject("command"), "shortcut:" + id, active);
                String detail = switch (target) {
                    case "infrared" -> NativeLocale.text("红外信号已发射，请核实设备响应。", "Infrared signal sent. Check the appliance response.");
                    case "nfc_emulation" -> NativeLocale.text("模拟配置已启用，请贴近读卡器验证。", "Emulation profile enabled. Hold the phone near the reader to test it.");
                    default -> NativeLocale.text("数据预览（十六进制）：\n", "Data preview (hexadecimal):\n") + result.optString("hex");
                };
                runOnUiThread(() -> text.setText(record.optString("label") + "\n\n" + detail));
            } catch (Exception failure) {
                String message = failure.getMessage();
                runOnUiThread(() -> text.setText(NativeLocale.text("快捷方式执行失败：", "Shortcut failed: ") + (message == null ? failure.getClass().getSimpleName() : message)));
            }
        }, "device-shortcut").start();
    }
}
