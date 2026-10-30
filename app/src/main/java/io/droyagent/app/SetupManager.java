package io.droyagent.app;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.webkit.WebView;
import androidx.webkit.WebViewFeature;
import io.droyagent.androiduse.DroyAccessibilityService;
import io.droyagent.androiduse.FileGrantStore;
import io.droyagent.androiduse.ShizukuAccess;
import io.droyagent.app.nativeui.permissions.NativeAuthorizationPolicy;
import io.droyagent.app.nativeui.permissions.NativePermissionPolicyStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Durable setup choices shared by the phone wizard and the local ADB installer. */
final class SetupManager {
    private static final int SCHEMA = 1;
    private static final String STORE = "installation";
    private static final Set<String> UPDATE_KEYS = Set.of("step", "source", "mode", "capabilities",
        "companionName", "userAddress", "personality", "shizukuChoice");
    private static final Set<String> ASSISTANT = Set.of("allowObserve", "allowControl", "allowApps");

    private SetupManager() {}

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
    }

    static synchronized void initialize(Context context) {
        SharedPreferences setup = store(context);
        int schema = setup.getInt("schemaVersion", 0);
        if (schema > SCHEMA) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("安装配置来自更新版本，请使用相应版本的 Droy", "Installation configuration is from a newer version. Please use the matching Droy version"));
        SharedPreferences product = ProductPermissions.preferences(context);
        if (schema == SCHEMA) {
            adoptDefaultDeny(context, product, setup.getString("source", "phone").equals("legacy"));
            return;
        }
        boolean legacy = !setup.getBoolean("initializing", false)
            && hasLegacyState(product.getAll().isEmpty(), hasFiles(new File(context.getFilesDir(), "dsh-home")));
        if (!legacy) {
            commit(setup.edit().putBoolean("initializing", true).putBoolean("nativeFirstUseEligible", true));
            SharedPreferences.Editor defaults = product.edit();
            for (String key : capabilityKeys(context)) defaults.putBoolean(key, false);
            defaults.putBoolean(ProductPermissions.STOPPED, true);
            defaults.putBoolean("notificationManagementEnabled", false);
            defaults.putBoolean(ProductPermissions.DEFAULT_DENY, true);
            commit(defaults);
        } else adoptDefaultDeny(context, product, true);
        commit(setup.edit().putInt("schemaVersion", SCHEMA).putBoolean("completed", legacy)
            .putInt("step", legacy ? 6 : 1).putString("source", legacy ? "legacy" : "phone")
            .putString("mode", legacy ? "custom" : "dialogue").putString("shizukuChoice", "skip")
            .putBoolean("modeSelected", legacy)
            .remove("initializing"));
    }

    private static void adoptDefaultDeny(Context context, SharedPreferences product, boolean legacy) {
        if (product.getBoolean(ProductPermissions.DEFAULT_DENY, false)) return;
        SharedPreferences.Editor migration = product.edit();
        for (String key : capabilityKeys(context)) {
            if (!product.contains(key)) migration.putBoolean(key, legacy && !ProductPermissions.isHardwareCapability(key));
        }
        // Snapshot old defaults once; future catalogue additions require consent.
        commit(migration.putBoolean(ProductPermissions.DEFAULT_DENY, true));
    }

    static boolean hasLegacyState(boolean productEmpty, boolean homeHasFiles) {
        return !productEmpty || homeHasFiles;
    }

    private static boolean hasFiles(File directory) { return hasFiles(directory, 5); }

    private static boolean hasFiles(File directory, int depth) {
        File[] entries = directory.listFiles();
        if (entries == null || depth < 0) return false;
        for (File entry : entries) if (entry.isFile() || entry.isDirectory() && hasFiles(entry, depth - 1)) return true;
        return false;
    }

    static Set<String> capabilityKeys(Context context) {
        Set<String> keys = new LinkedHashSet<>();
        JSONArray groups = new CapabilityCatalog(context).groups();
        for (int index = 0; index < groups.length(); index++)
            keys.add(groups.optJSONObject(index).optString("preference"));
        return keys;
    }

    static JSONObject validatedUpdate(JSONObject input, Set<String> knownCapabilities) throws Exception {
        if (input == null || input.toString().length() > 16384) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装配置无效或过大", "Invalid or oversized installation configuration"));
        for (String key : keys(input)) if (!UPDATE_KEYS.contains(key))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装配置包含不支持的字段", "Installation configuration contains unsupported fields"));
        JSONObject value = new JSONObject(input.toString());
        if (value.has("step")) {
            Object step = value.get("step");
            if (!(step instanceof Integer) || (Integer) step < 1 || (Integer) step > 6)
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装步骤应为 1–6", "Installation step must be 1–6"));
        }
        for (String key : new String[]{"source", "mode", "shizukuChoice"}) {
            if (!value.has(key)) continue;
            Object raw = value.get(key);
            Set<String> allowed = key.equals("source") ? Set.of("phone", "usb")
                : key.equals("mode") ? Set.of("dialogue", "assistant", "all", "custom") : Set.of("skip", "install", "use");
            if (!(raw instanceof String) || !allowed.contains(raw)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("安装选项无效", "Invalid installation option"));
        }
        for (String key : new String[]{"companionName", "userAddress", "personality"}) {
            if (!value.has(key)) continue;
            Object raw = value.get(key);
            if (!(raw instanceof String)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("个性化配置需要文字", "Personalization configuration requires text"));
            String text = ((String) raw).trim();
            int limit = key.equals("personality") ? 1000 : 40;
            if (text.codePointCount(0, text.length()) > limit || key.equals("companionName") && text.isEmpty()
                    || ((String) raw).codePoints().anyMatch(code -> Character.isISOControl(code)
                        && !(key.equals("personality") && (code == '\n' || code == '\t'))))
                throw new IllegalArgumentException(key.equals("personality") ? io.droyagent.app.NativeLocale.text("性格说明最多 1000 个字符", "Personality description must be at most 1,000 characters") : io.droyagent.app.NativeLocale.text("名称和称呼最多 40 个单行字符", "Names and forms of address must be at most 40 single-line characters"));
            value.put(key, text);
        }
        if (value.has("capabilities")) {
            JSONObject capabilities = value.optJSONObject("capabilities");
            if (capabilities == null) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("能力配置需要开关清单", "Capability configuration requires a switch list"));
            for (String key : keys(capabilities)) {
                if (!knownCapabilities.contains(key) || !(capabilities.get(key) instanceof Boolean))
                    throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("能力清单包含未知项目或无效开关", "The capability list contains unknown items or invalid switches"));
                if (value.optString("mode").equals("dialogue") && capabilities.getBoolean(key))
                    throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("仅对话模式不能同时开启手机能力", "Chat-only mode cannot enable phone capabilities"));
            }
        }
        return value;
    }

    static synchronized JSONObject update(Context context, JSONObject input) throws Exception {
        initialize(context);
        JSONObject value = validatedUpdate(input, capabilityKeys(context));
        SharedPreferences setup = store(context), product = ProductPermissions.preferences(context);
        SharedPreferences.Editor settings = product.edit(), installation = setup.edit();
        JSONObject capabilities = value.optJSONObject("capabilities");
        boolean capabilitiesChanged = false;
        if (capabilities != null) for (String key : keys(capabilities))
            capabilitiesChanged |= ProductPermissions.allowed(product, key) != capabilities.getBoolean(key);
        boolean choicesChanged = value.has("mode") || capabilitiesChanged;
        if (capabilities != null && !value.has("mode")) {
            boolean any = false, enabling = false;
            for (String key : capabilityKeys(context)) {
                boolean enabled = capabilities.has(key) ? capabilities.getBoolean(key) : ProductPermissions.allowed(product, key);
                any |= enabled;
                enabling |= capabilities.has(key) && enabled;
            }
            if (enabling || !any) {
                boolean stopped = !any;
                choicesChanged |= ProductPermissions.stopped(context) != stopped;
                settings.putBoolean(ProductPermissions.STOPPED, stopped);
            }
        }
        if (value.has("mode")) {
            String mode = value.getString("mode");
            installation.putString("mode", mode).putBoolean("modeSelected", true);
            if (!mode.equals("custom")) for (String key : capabilityKeys(context))
                settings.putBoolean(key, mode.equals("all") || mode.equals("assistant") && ASSISTANT.contains(key));
            settings.putBoolean(ProductPermissions.STOPPED, mode.equals("dialogue"));
        } else if (capabilitiesChanged) {
            installation.putString("mode", "custom").putBoolean("modeSelected", true);
        }
        if (capabilities != null) for (String key : keys(capabilities)) settings.putBoolean(key, capabilities.getBoolean(key));
        if (value.has("companionName")) {
            settings.putString("petName", value.getString("companionName"));
            if (!product.contains("assistantName")) settings.putString("assistantName", value.getString("companionName"));
        }
        if (value.has("userAddress")) settings.putString("userAddress", value.getString("userAddress"));
        if (value.has("personality")) settings.putString("companionPersonality", value.getString("personality"));
        if (value.has("step")) installation.putInt("step", value.getInt("step"));
        if (value.has("source")) installation.putString("source", value.getString("source"));
        if (value.has("shizukuChoice")) installation.putString("shizukuChoice", value.getString("shizukuChoice"));
        if (choicesChanged) installation.remove("deviceVerification");
        commit(settings);
        commit(installation);
        if (choicesChanged) {
            HardwareRuntime.onPermissionsChanged(context);
            RuntimeService.memoryPermissionsChanged(context);
            RuntimeService.applySavedOperationState();
        }
        return status(context);
    }

    static synchronized JSONObject recordModel(Context context, JSONObject input) throws Exception {
        initialize(context);
        if (!(input.opt("passed") instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("模型检查结果无效", "Invalid model check result"));
        boolean passed = input.getBoolean("passed");
        JSONObject receipt = new JSONObject().put("passed", passed).put("checkedAt", System.currentTimeMillis());
        if (passed) {
            String fingerprint = input.optString("configFingerprint");
            if (!validFingerprint(fingerprint)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("模型检查缺少配置摘要", "The model check is missing a configuration digest"));
            receipt.put("configFingerprint", fingerprint);
            for (String key : new String[]{"provider", "model"}) {
                Object raw = input.opt(key);
                if (!(raw instanceof String) || ((String) raw).isBlank() || ((String) raw).length() > 200)
                    throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("模型检查缺少提供方或模型", "The model check is missing a provider or model"));
                receipt.put(key, raw);
            }
        }
        receipt.put("toolsPassed", input.optBoolean("toolsPassed", input.optBoolean("toolCallingPassed", false)))
            .put("imageInput", input.optBoolean("imageInput", false));
        commit(store(context).edit().putString("modelVerification", receipt.toString()));
        return status(context);
    }

    static boolean validFingerprint(String value) { return value != null && value.matches("[a-f0-9]{64}"); }

    static synchronized JSONObject checkModelFingerprint(Context context, String fingerprint) throws Exception {
        initialize(context);
        if (!validFingerprint(fingerprint)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("模型配置摘要无效", "Invalid model configuration digest"));
        JSONObject receipt = receipt(store(context), "modelVerification");
        boolean matches = receipt.optBoolean("passed") && MessageDigest.isEqual(
            fingerprint.getBytes(StandardCharsets.US_ASCII), receipt.optString("configFingerprint").getBytes(StandardCharsets.US_ASCII));
        return new JSONObject().put("matches", matches);
    }

    static synchronized JSONObject recordDeviceVerification(Context context, JSONObject input) throws Exception {
        initialize(context);
        JSONObject value = mergeDeviceVerification(receipt(store(context), "deviceVerification"), choicesFingerprint(context), input, System.currentTimeMillis());
        commit(store(context).edit().putString("deviceVerification", value.toString()));
        return status(context);
    }

    static JSONObject mergeDeviceVerification(JSONObject previous, String fingerprint, JSONObject input, long now) throws Exception {
        if (!(input.opt("passed") instanceof Boolean)) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("手机操作检查结果无效", "Invalid phone operation check result"));
        String channel = input.optString("channel", "accessibility");
        if (!Set.of("accessibility", "shizuku", "root", "none").contains(channel))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("手机操作检查通道无效", "Invalid phone operation check channel"));
        if (input.has("rootVerified") && !(input.opt("rootVerified") instanceof Boolean))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("Root 检查结果无效", "Invalid Root check result"));
        boolean sameChoices = fingerprint.equals(previous.optString("choicesFingerprint"));
        boolean rootVerified = input.has("rootVerified") ? input.getBoolean("rootVerified") : sameChoices && previous.optBoolean("rootVerified");
        JSONObject value = new JSONObject().put("passed", input.getBoolean("passed")).put("channel", channel)
            .put("rootVerified", rootVerified).put("checkedAt", now).put("choicesFingerprint", fingerprint);
        if (rootVerified && sameChoices) value.put("rootCheckedAt", previous.optLong("rootCheckedAt", now));
        return value;
    }

    static synchronized JSONObject recordRootVerification(Context context, boolean verified) throws Exception {
        initialize(context);
        if (verified && (ProductPermissions.stopped(context)
                || !ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot")))
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("Root 增强能力已关闭或操作已暂停", "Root capabilities are disabled or operations are paused"));
        JSONObject value = mergeRootVerification(receipt(store(context), "deviceVerification"), choicesFingerprint(context), verified, System.currentTimeMillis());
        commit(store(context).edit().putString("deviceVerification", value.toString()));
        return status(context);
    }

    static JSONObject mergeRootVerification(JSONObject previous, String fingerprint, boolean verified, long now) throws Exception {
        boolean sameChoices = fingerprint.equals(previous.optString("choicesFingerprint"));
        return new JSONObject().put("passed", sameChoices && previous.optBoolean("passed"))
            .put("channel", sameChoices ? previous.optString("channel", "none") : "none")
            .put("checkedAt", sameChoices ? previous.optLong("checkedAt", now) : now)
            .put("rootVerified", verified).put("rootCheckedAt", now).put("choicesFingerprint", fingerprint);
    }

    static synchronized JSONObject uiStatus(Context context) throws Exception {
        initialize(context);
        SharedPreferences setup = store(context), product = ProductPermissions.preferences(context);
        if (!setup.getBoolean("completed", false)) return status(context);
        // Completed onboarding only needs identity and routing. Environment checks
        // remain in status()/verify(), rather than every ordinary settings write.
        return new JSONObject().put("schemaVersion", SCHEMA).put("completed", true).put("required", false)
            .put("step", setup.getInt("step", 6)).put("source", setup.getString("source", "phone"))
            .put("mode", setup.getString("mode", "dialogue")).put("modeSelected", setup.getBoolean("modeSelected", false))
            .put("identity", new JSONObject().put("companionName", product.getString("petName", "Droy"))
                .put("userAddress", product.getString("userAddress", ""))
                .put("personality", product.getString("companionPersonality", "")));
    }

    static synchronized JSONObject status(Context context) throws Exception {
        initialize(context);
        SharedPreferences setup = store(context), product = ProductPermissions.preferences(context);
        JSONObject capabilities = capabilities(context), model = receipt(setup, "modelVerification"), device = receipt(setup, "deviceVerification");
        model.remove("configFingerprint"); device.remove("choicesFingerprint");
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        JSONObject result = new JSONObject().put("schemaVersion", SCHEMA).put("completed", setup.getBoolean("completed", false))
            .put("required", !setup.getBoolean("completed", false)).put("step", setup.getInt("step", 1))
            .put("source", setup.getString("source", "phone")).put("mode", setup.getString("mode", "dialogue"))
            .put("modeSelected", setup.getBoolean("modeSelected", false))
            .put("toolsPaused", ProductPermissions.stopped(context))
            .put("shizukuChoice", setup.getString("shizukuChoice", "skip"))
            .put("installedVersion", installed.versionName).put("versionCode", installed.getLongVersionCode())
            .put("capabilities", capabilities).put("modelVerification", model).put("deviceVerification", device)
            .put("identity", new JSONObject().put("companionName", product.getString("petName", "Droy"))
                .put("userAddress", product.getString("userAddress", ""))
                .put("personality", product.getString("companionPersonality", "")));
        JSONObject environment = environment(context);
        result.put("environment", environment);
        JSONArray checks = checks(context, capabilities, model, environment);
        boolean canComplete = true;
        for (int index = 0; index < checks.length(); index++) {
            JSONObject check = checks.getJSONObject(index);
            if (check.optBoolean("required") && !check.optString("status").equals("passed")) canComplete = false;
        }
        return result.put("checks", checks).put("canComplete", canComplete);
    }

    static JSONObject verify(Context context) throws Exception { return status(context); }

    static JSONObject verifyFromInstaller(Context context) throws Exception {
        return RuntimeService.ready ? runtimeSetup("verify") : verify(context);
    }

    static JSONObject completeFromInstaller(Context context) throws Exception { return runtimeSetup("complete"); }

    private static JSONObject runtimeSetup(String operation) throws Exception {
        if (!RuntimeService.ready || RuntimeService.webPort < 1 || RuntimeService.webToken.length() < 32)
            throw new IllegalStateException(io.droyagent.app.NativeLocale.text("请先打开 Droy 并等待运行环境就绪，再完成安装检查", "Open Droy and wait for the runtime to become ready before completing installation checks"));
        // The runtime checks its live model settings before trusting saved proof.
        // Do not hold the setup lock while it calls the authenticated native bridge.
        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(
            "http://127.0.0.1:" + RuntimeService.webPort + "/droy/setup/" + operation).openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000); connection.setReadTimeout(10000);
            connection.setRequestProperty("Cookie", "droy_session=" + RuntimeService.webToken);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            try (var output = connection.getOutputStream()) { output.write(new byte[]{'{', '}'}); }
            if (connection.getResponseCode() != 200) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("安装检查尚未通过，请在手机查看未完成项", "Installation checks have not passed. Please review incomplete items on your phone"));
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (var input = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > 65536) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("安装检查结果过大", "Installation check result is too large"));
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!response.optBoolean("ok") || response.optJSONObject("state") == null)
                throw new IllegalStateException(io.droyagent.app.NativeLocale.text("安装检查没有返回有效结果", "Installation checks did not return a valid result"));
            JSONObject state = response.getJSONObject("state");
            if (operation.equals("complete") && !state.optBoolean("completed"))
                throw new IllegalStateException(io.droyagent.app.NativeLocale.text("手机尚未确认安装完成", "The phone has not confirmed installation completion"));
            return state;
        } finally { connection.disconnect(); }
    }

    static synchronized JSONObject complete(Context context) throws Exception {
        JSONObject current = verify(context);
        if (!current.getBoolean("canComplete")) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("安装检查尚未通过，请处理未完成项或关闭对应可选功能", "Installation checks have not passed. Address incomplete items or disable the corresponding optional features"));
        commit(store(context).edit().putBoolean("completed", true).putInt("step", 6).putLong("completedAt", System.currentTimeMillis()));
        return status(context);
    }

    static synchronized JSONObject nativeRouteState(Context context) throws Exception {
        initialize(context);
        SharedPreferences installation = store(context);
        NativeRuntimeHost.Snapshot runtime = NativeRuntimeHost.inspect(context);
        NativeAuthorizationPolicy policy = new NativePermissionPolicyStore(ProductPermissions.preferences(context)).selected();
        boolean completed = installation.getBoolean("completed", false);
        int step = installation.getInt("nativeWizardStep", completed ? 6 : 1);
        if (step < 1 || step > 6) throw new IllegalStateException(NativeLocale.text("原生安装进度无效", "Invalid native setup progress"));
        return new JSONObject().put("nativeSchemaVersion", 1).put("schemaVersion", installation.getInt("schemaVersion", SCHEMA))
            .put("completed", completed).put("required", !completed)
            .put("step", step)
            .put("legacyStep", installation.getInt("step", 1)).put("source", installation.getString("source", "phone"))
            .put("locale", runtime.locale)
            .put("environment", new JSONObject().put("ready", runtime.ready).put("active", runtime.active)
                .put("canStart", runtime.canStart).put("status", runtime.status))
            .put("permissionPolicy", new JSONObject().put("selected", policy != null)
                .put("mode", policy == null ? JSONObject.NULL : policy.getId()))
            .put("acknowledgements", receipt(installation, "nativeAcknowledgements"));
    }

    static synchronized JSONObject nativeStep(Context context, int step) throws Exception {
        initialize(context);
        if (step < 1 || step > 6) throw new IllegalArgumentException(NativeLocale.text("安装步骤应为 1–6", "Setup step must be 1–6"));
        commit(store(context).edit().putInt("nativeWizardStep", step));
        return nativeRouteState(context);
    }

    static synchronized JSONObject nativeAcknowledge(Context context, String key, boolean acknowledged) throws Exception {
        initialize(context);
        if (key == null || !Set.of("welcome", "accessibility", "root", "backgroundRuntime", "powerManagement").contains(key))
            throw new IllegalArgumentException(NativeLocale.text("安装确认项目无效", "Invalid setup acknowledgement"));
        JSONObject values = receipt(store(context), "nativeAcknowledgements").put(key, acknowledged);
        commit(store(context).edit().putString("nativeAcknowledgements", values.toString()));
        return nativeRouteState(context);
    }

    static synchronized JSONObject nativeStatus(Context context, JSONObject input) throws Exception {
        initialize(context);
        validateNativeFacts(input);
        JSONObject result = nativeRouteState(context);
        JSONObject model = receipt(store(context), "modelVerification");
        String fingerprint = input.isNull("configFingerprint") ? null : input.getString("configFingerprint");
        String profileFingerprint = input.getString("profileFingerprint");
        boolean profileReady = input.getBoolean("profileReady");
        NativeAuthorizationPolicy policy = new NativePermissionPolicyStore(ProductPermissions.preferences(context)).selected();
        JSONObject checked = nativeChecks(model, fingerprint, profileReady, profileFingerprint,
            receipt(store(context), "nativeUserConfirmation"), policy == null ? null : policy.getId(), RuntimeService.ready);
        JSONObject publicModel = new JSONObject(model.toString());
        publicModel.remove("configFingerprint");
        publicModel.put("passed", nativeModelMatches(model, fingerprint))
            .put("toolsPassed", nativeModelMatches(model, fingerprint) && Boolean.TRUE.equals(model.opt("toolsPassed")));
        return result.put("checks", checked.getJSONArray("checks")).put("canComplete", checked.getBoolean("canComplete"))
            .put("modelVerification", publicModel).put("identity", checked.getJSONObject("identity"));
    }

    static synchronized JSONObject nativeAcknowledgeIdentity(Context context, JSONObject input) throws Exception {
        initialize(context);
        if (input == null || !keys(input).equals(Set.of("operation", "profileFingerprint", "profileReady"))
                || !"nativeAcknowledgeIdentity".equals(input.opt("operation")) || !Boolean.TRUE.equals(input.opt("profileReady"))
                || !(input.opt("profileFingerprint") instanceof String) || !validFingerprint(input.getString("profileFingerprint")))
            throw new IllegalArgumentException(NativeLocale.text("用户资料确认无效", "Invalid user profile confirmation"));
        commit(store(context).edit().putString("nativeUserConfirmation", new JSONObject()
            .put("profileFingerprint", input.getString("profileFingerprint")).put("confirmedAt", System.currentTimeMillis()).toString()));
        return nativeRouteState(context);
    }

    static synchronized JSONObject nativeComplete(Context context, JSONObject input) throws Exception {
        JSONObject current = nativeStatus(context, input);
        if (current.getBoolean("completed")) return current;
        if (!current.getBoolean("canComplete")) throw new IllegalStateException(NativeLocale.text("安装条件尚未完成", "Setup requirements are incomplete"));
        SharedPreferences installation = store(context);
        SharedPreferences.Editor editor = installation.edit().putBoolean("completed", true).putInt("nativeWizardStep", 6)
            .putLong("nativeCompletedAt", System.currentTimeMillis());
        if (!installation.contains("completedAt")) editor.putLong("completedAt", System.currentTimeMillis());
        commit(editor);
        return nativeStatus(context, input);
    }

    private static void validateNativeFacts(JSONObject input) throws Exception {
        if (input == null || !keys(input).equals(Set.of("operation", "configFingerprint", "profileFingerprint", "profileReady"))
                || !Set.of("nativeStatus", "nativeComplete").contains(input.opt("operation"))
                || !(input.opt("profileReady") instanceof Boolean) || !(input.opt("profileFingerprint") instanceof String)
                || !validFingerprint(input.getString("profileFingerprint")) || !input.has("configFingerprint")
                || !input.isNull("configFingerprint") && (!(input.opt("configFingerprint") instanceof String) || !validFingerprint(input.getString("configFingerprint"))))
            throw new IllegalArgumentException(NativeLocale.text("原生安装检查数据无效", "Invalid native setup verification data"));
    }

    static boolean nativeModelMatches(JSONObject model, String fingerprint) {
        String saved = model.optString("configFingerprint");
        return Boolean.TRUE.equals(model.opt("passed")) && model.opt("configFingerprint") instanceof String
            && validFingerprint(fingerprint) && validFingerprint(saved)
            && MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII), saved.getBytes(StandardCharsets.US_ASCII));
    }

    static JSONObject nativeChecks(JSONObject model, String fingerprint, boolean profileReady, String profileFingerprint,
                                   JSONObject confirmation, String permissionPolicy, boolean runtimeReady) throws Exception {
        boolean modelPassed = nativeModelMatches(model, fingerprint);
        boolean toolsPassed = modelPassed && Boolean.TRUE.equals(model.opt("toolsPassed"));
        String confirmed = confirmation.optString("profileFingerprint");
        boolean identityConfirmed = profileReady && confirmation.opt("profileFingerprint") instanceof String
            && validFingerprint(profileFingerprint) && validFingerprint(confirmed)
            && MessageDigest.isEqual(profileFingerprint.getBytes(StandardCharsets.US_ASCII), confirmed.getBytes(StandardCharsets.US_ASCII));
        boolean policySelected = "batch".equals(permissionPolicy) || "on_first_use".equals(permissionPolicy);
        JSONArray checks = new JSONArray();
        for (Object[] check : new Object[][]{{"runtime", runtimeReady}, {"model", modelPassed}, {"modelTools", toolsPassed},
                {"identity", identityConfirmed}, {"permissionPolicy", policySelected}})
            checks.put(new JSONObject().put("id", check[0]).put("required", true).put("passed", check[1]));
        return new JSONObject().put("checks", checks)
            .put("canComplete", runtimeReady && modelPassed && toolsPassed && identityConfirmed && policySelected)
            .put("identity", new JSONObject().put("saved", profileReady).put("confirmed", identityConfirmed));
    }

    private static JSONObject capabilities(Context context) throws Exception {
        JSONObject result = new JSONObject();
        SharedPreferences product = ProductPermissions.preferences(context);
        for (String key : capabilityKeys(context)) result.put(key, ProductPermissions.allowed(product, key));
        return result;
    }

    private static String choicesFingerprint(Context context) throws Exception {
        StringBuilder value = new StringBuilder();
        SharedPreferences product = ProductPermissions.preferences(context);
        for (String key : capabilityKeys(context)) value.append(key).append('=').append(ProductPermissions.allowed(product, key)).append(';');
        value.append("toolsPaused=").append(ProductPermissions.stopped(context));
        return hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }

    private static JSONObject environment(Context context) throws Exception {
        JSONObject shizuku = ShizukuAccess.get(context).status();
        try { context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0); shizuku.put("installed", true); }
        catch (PackageManager.NameNotFoundException absent) { shizuku.put("installed", false); }
        boolean rootService = shizuku.optInt("uid", -1) == 0;
        shizuku.put("requiresRootOptIn", rootService && !ProductPermissions.allowed(ProductPermissions.preferences(context), "allowRoot"));
        shizuku.put("available", shizuku.optBoolean("running") && shizuku.optBoolean("authorized")
            && !shizuku.optBoolean("requiresRootOptIn"));
        PackageInfo web = WebView.getCurrentWebViewPackage();
        JSONObject webView = new JSONObject().put("installed", web != null).put("supported", web != null
            && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER));
        if (web != null) webView.put("packageName", web.packageName).put("version", web.versionName);
        return new JSONObject().put("androidApi", Build.VERSION.SDK_INT).put("abi", new JSONArray(Build.SUPPORTED_ABIS))
            .put("webView", webView).put("freeBytes", context.getFilesDir().getUsableSpace())
            .put("developerOptions", Settings.Global.getInt(context.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1)
            .put("usbDebugging", Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1)
            .put("shizuku", shizuku);
    }

    private static JSONArray checks(Context context, JSONObject capabilities, JSONObject model, JSONObject environment) throws Exception {
        JSONArray checks = new JSONArray();
        add(checks, "runtime", io.droyagent.app.NativeLocale.text("本地运行环境", "Local runtime"), true, RuntimeService.ready, RuntimeService.status, null);
        add(checks, "webView", io.droyagent.app.NativeLocale.text("系统 WebView", "System WebView"), true, environment.getJSONObject("webView").optBoolean("supported"),
            io.droyagent.app.NativeLocale.text("系统浏览组件需要支持对话与安全设置接口", "The system browser component must support chat and secure settings interfaces"), "webView");
        add(checks, "model", io.droyagent.app.NativeLocale.text("模型真实回复", "Actual model reply"), true, model.optBoolean("passed"), io.droyagent.app.NativeLocale.text("在模型页保存配置并测试连接", "Save configuration and test the connection on the Models page"), null);
        add(checks, "mode", io.droyagent.app.NativeLocale.text("使用方式", "Usage mode"), true, store(context).getBoolean("modeSelected", false), io.droyagent.app.NativeLocale.text("在欢迎页选择使用方式", "Choose a usage mode on the welcome page"), null);
        boolean any = false;
        for (String key : keys(capabilities)) any |= capabilities.getBoolean(key);
        if (any) add(checks, "modelTools", io.droyagent.app.NativeLocale.text("模型工具调用", "Model tool calling"), true, model.optBoolean("toolsPassed"), io.droyagent.app.NativeLocale.text("所选手机能力需要模型通过安全工具调用测试", "Selected phone capabilities require the model to pass the safe tool-calling test"), null);
        boolean screen = capabilities.optBoolean("allowObserve") || capabilities.optBoolean("allowControl") || capabilities.optBoolean("allowScreenshot");
        if (any) add(checks, "operations", io.droyagent.app.NativeLocale.text("手机能力运行状态", "Phone capability runtime state"), true, !ProductPermissions.stopped(context), io.droyagent.app.NativeLocale.text("在权限页恢复 Android Use 操作，或选择仅对话", "Resume Android Use operations on the Permissions page or select chat-only"), null);
        if (screen) {
            add(checks, "accessibility", io.droyagent.app.NativeLocale.text("无障碍实际连接", "Accessibility connection"), true, DroyAccessibilityService.isConnected(context), io.droyagent.app.NativeLocale.text("请开启无障碍并返回 Droy 核实连接", "Enable accessibility, then return to Droy to verify the connection"), "accessibility");
        }
        if (capabilities.optBoolean("allowControl")) {
            JSONObject device = receipt(store(context), "deviceVerification");
            boolean verified = device.optBoolean("passed") && choicesFingerprint(context).equals(device.optString("choicesFingerprint"));
            add(checks, "device", io.droyagent.app.NativeLocale.text("手机操作练习", "Phone operation practice"), true, verified, io.droyagent.app.NativeLocale.text("在 Droy 的本地练习页验证观察、点击和结果回读", "Verify observation, tapping, and result readback on Droy's local practice page"), null);
        }
        if (capabilities.optBoolean("allowScreenshot")) {
            add(checks, "modelImage", io.droyagent.app.NativeLocale.text("模型图片输入", "Model image input"), true, model.optBoolean("imageInput"), io.droyagent.app.NativeLocale.text("选择支持图片的模型，或关闭截图功能", "Choose a model that supports images or disable screenshots"), null);
            add(checks, "screenshotObserve", io.droyagent.app.NativeLocale.text("截图所需页面读取", "Page reading required for screenshots"), true, capabilities.optBoolean("allowObserve"), io.droyagent.app.NativeLocale.text("截图需要同时允许读取应用页面", "Screenshots also require permission to read app pages"), null);
        }
        permission(checks, context, capabilities, "allowCalendar", "calendar", io.droyagent.app.NativeLocale.text("日历授权", "Calendar permission"), Manifest.permission.READ_CALENDAR);
        permission(checks, context, capabilities, "allowContacts", "contacts", io.droyagent.app.NativeLocale.text("联系人授权", "Contacts permission"), Manifest.permission.READ_CONTACTS);
        permission(checks, context, capabilities, "allowAudioMicrophone", "microphone", io.droyagent.app.NativeLocale.text("麦克风授权", "Microphone permission"), Manifest.permission.RECORD_AUDIO);
        JSONObject hardwarePermissions = HardwarePermissionState.status(context);
        JSONObject hardware = hardwarePermissions.getJSONObject("hardware");
        if (capabilities.optBoolean("allowLocationRead")) {
            add(checks, "location", NativeLocale.text("位置访问", "Location access"), true,
                hardwarePermissions.optBoolean("locationApproximate") || hardwarePermissions.optBoolean("locationPrecise"),
                NativeLocale.text("大致位置可用于普通定位；需要精确定位时再授予精确位置。", "Approximate location supports general requests. Allow precise location when needed."), "location");
            add(checks, "locationSettings", NativeLocale.text("系统定位", "System location"), true,
                hardwarePermissions.optBoolean("locationEnabled"),
                NativeLocale.text("在系统设置中开启定位。", "Turn on location in system settings."), "locationSettings");
        }
        if ((capabilities.optBoolean("allowBluetoothRead") || capabilities.optBoolean("allowBluetoothControl")) && hardware.optBoolean("bluetooth"))
            add(checks, "bluetooth", NativeLocale.text("蓝牙权限", "Bluetooth permissions"), true,
                hardwarePermissions.optBoolean("bluetoothScan") && hardwarePermissions.optBoolean("bluetoothConnect"),
                NativeLocale.text("扫描和连接需要附近设备权限；Android 11 扫描还需要精确位置和系统定位。", "Scanning and connecting need nearby device permissions. On Android 11, scanning also needs precise location and system location."), "bluetooth");
        if (capabilities.optBoolean("allowWifiRead") && hardware.optBoolean("wifi"))
            add(checks, "wifiScan", NativeLocale.text("Wi-Fi 扫描权限", "Wi-Fi scanning permission"), false,
                hardwarePermissions.optBoolean("wifiScan"),
                NativeLocale.text("查询周边网络还需要精确位置权限和系统定位；基本连接状态可单独使用。", "Nearby network scans also need precise location and system location. Basic connection status is available separately."), "wifiScan");
        if (capabilities.optBoolean("allowWifiControl") && hardware.optBoolean("wifi"))
            add(checks, "nearbyWifi", NativeLocale.text("附近 Wi-Fi 权限", "Nearby Wi-Fi permission"), true,
                hardwarePermissions.optBoolean("nearbyWifi"),
                NativeLocale.text("按系统版本授予附近 Wi-Fi 或位置权限；连接时可能需要系统确认。", "Allow nearby Wi-Fi or location permission for this Android version. Connecting may require system confirmation."), "nearbyWifi");
        if (capabilities.optBoolean("allowCellularRead") && hardware.optBoolean("telephony")) {
            permission(checks, context, capabilities, "allowCellularRead", "phoneState", NativeLocale.text("电话状态授权", "Phone state permission"), Manifest.permission.READ_PHONE_STATE);
            add(checks, "cellLocation", NativeLocale.text("小区位置权限", "Cell location permission"), false,
                hardwarePermissions.optBoolean("locationPrecise"),
                NativeLocale.text("服务小区和邻区信息需要精确位置；其他网络指标按实际系统授权返回。", "Serving and neighboring cell details need precise location. Other network metrics follow actual system permissions."), "locationPrecise");
        }
        if (capabilities.optBoolean("allowActivitySensors") && hardware.optBoolean("stepCounter"))
            add(checks, "activityRecognition", NativeLocale.text("计步授权", "Step recognition permission"), true,
                hardwarePermissions.optBoolean("activityRecognition"),
                NativeLocale.text("计步需要活动识别权限；其他传感器单独检查。", "Step data needs activity recognition permission. Other sensors are checked separately."), "activityRecognition");
        if (capabilities.optBoolean("allowBodySensors") && hardware.optBoolean("heartRate"))
            add(checks, "bodySensors", NativeLocale.text("心率传感器授权", "Heart rate sensor permission"), true,
                hardwarePermissions.optBoolean("bodySensors"),
                NativeLocale.text("仅在你开启身体传感器能力后按任务读取；不会因此开启持续记录。", "Read for a task only after you enable body sensor access. This does not start continuous recording."), "bodySensors");
        if ((capabilities.optBoolean("allowNfcRead") || capabilities.optBoolean("allowNfcWrite") || capabilities.optBoolean("allowNfcEmulation")) && hardware.optBoolean("nfc"))
            add(checks, "nfcSettings", NativeLocale.text("NFC 开关", "NFC"), false,
                hardwarePermissions.optBoolean("nfcEnabled"),
                NativeLocale.text("使用读卡或卡模拟时需开启 NFC；读卡需要前台贴卡界面。", "Enable NFC for reading or emulation. Reading requires the foreground card screen."), "nfcSettings");
        if (capabilities.optBoolean("allowNotifications")) add(checks, "notificationAccess", io.droyagent.app.NativeLocale.text("通知服务连接", "Notification service connection"), true,
            DroyNotificationService.status(context).optBoolean("available"), io.droyagent.app.NativeLocale.text("请允许通知访问并等待服务连接", "Allow notification access and wait for the service to connect"), "notificationAccess");
        if (capabilities.optBoolean("allowUsage") || capabilities.optBoolean("allowPrivacyUsage") || capabilities.optBoolean("allowDiagnostics")) {
            AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
            boolean granted = appOps != null && appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
            add(checks, "usage", io.droyagent.app.NativeLocale.text("使用情况访问", "Usage access"), true, granted, io.droyagent.app.NativeLocale.text("在系统设置中允许使用情况访问", "Allow usage access in system settings"), "usage");
        }
        if (capabilities.optBoolean("allowPrivacyUsage") || capabilities.optBoolean("allowDiagnostics")) add(checks, "diagnostics", io.droyagent.app.NativeLocale.text("跨应用诊断授权", "Cross-app diagnostics permission"), true,
            context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED,
            io.droyagent.app.NativeLocale.text("需要 USB 安装工具配置系统诊断权限，或关闭相关诊断能力", "Use the USB installation tool to configure system diagnostics permission or disable the related diagnostics capabilities"), null);
        if (capabilities.optBoolean("allowSettingsWrite")) add(checks, "brightness", io.droyagent.app.NativeLocale.text("修改系统设置", "Modify system settings"), true, Settings.System.canWrite(context),
            io.droyagent.app.NativeLocale.text("请允许修改亮度等系统设置", "Allow modification of system settings such as brightness"), "brightness");
        if (capabilities.optBoolean("allowFiles") || capabilities.optBoolean("allowFileExport")) {
            JSONArray grants = new FileGrantStore(context).listGrants();
            boolean readable = false, writable = false;
            for (int index = 0; index < grants.length(); index++) {
                JSONObject grant = grants.getJSONObject(index);
                boolean available = grant.optBoolean("available", true);
                readable |= available && grant.optBoolean("readable");
                writable |= available && grant.optBoolean("writable") && grant.optString("kind").equals("directory");
            }
            if (capabilities.optBoolean("allowFiles")) add(checks, "files", io.droyagent.app.NativeLocale.text("选定文件授权", "Selected file permission"), true, readable, io.droyagent.app.NativeLocale.text("通过系统选择器选择允许读取的来源", "Use the system picker to select sources that may be read"), "file");
            if (capabilities.optBoolean("allowFileExport")) add(checks, "fileExport", io.droyagent.app.NativeLocale.text("导出目录授权", "Export directory permission"), true, writable, io.droyagent.app.NativeLocale.text("选择可写入的导出目录", "Choose a writable export directory"), "directory");
        }
        if (capabilities.optBoolean("allowRoot") || capabilities.optBoolean("allowMemoryRead") || capabilities.optBoolean("allowMemoryWrite")) {
            JSONObject device = receipt(store(context), "deviceVerification");
            add(checks, "root", io.droyagent.app.NativeLocale.text("Root 实际授权", "Actual Root authorization"), true, capabilities.optBoolean("allowRoot") && device.optBoolean("rootVerified")
                && choicesFingerprint(context).equals(device.optString("choicesFingerprint")), io.droyagent.app.NativeLocale.text("需要在手机允许 Root 并验证通道，或关闭 Root 与进程数据能力", "Allow Root on the phone and verify the channel, or disable Root and process data capabilities"), null);
        }
        if (capabilities.optBoolean("allowMemoryRead") || capabilities.optBoolean("allowMemoryWrite")) add(checks, "memoryPrerequisites", io.droyagent.app.NativeLocale.text("进程数据能力依赖", "Process data dependencies"), true,
            capabilities.optBoolean("allowApps") && capabilities.optBoolean("allowMemoryRead") && capabilities.optBoolean("allowRoot"),
            io.droyagent.app.NativeLocale.text("进程数据能力需要应用查询、进程读取和 Root 授权", "Process data capabilities require app queries, process reading, and Root authorization"), null);
        if (capabilities.optBoolean("allowAudioTranscription")) add(checks, "transcription", io.droyagent.app.NativeLocale.text("本地转写模型", "Local transcription model"), true,
            AudioTranscription.get(context).uiStatus().optBoolean("ready"), io.droyagent.app.NativeLocale.text("等待内置识别模型初始化完成", "Wait for the built-in recognition model to initialize"), null);
        String shizukuChoice = store(context).getString("shizukuChoice", "skip");
        if (!shizukuChoice.equals("skip")) add(checks, "shizuku", io.droyagent.app.NativeLocale.text("Shizuku 通道", "Shizuku channel"), true,
            environment.getJSONObject("shizuku").optBoolean("available"), io.droyagent.app.NativeLocale.text("需要安装、启动并允许 Droy 使用 Shizuku；也可跳过增强组件", "Install, start, and allow Droy to use Shizuku, or skip this optional component"), "shizuku");
        if (capabilities.optBoolean("allowAudioPlayback")) add(checks, "playback", io.droyagent.app.NativeLocale.text("播放音频采集", "Playback audio capture"), false, false,
            io.droyagent.app.NativeLocale.text("系统捕获授权在每次采集时申请", "System capture authorization is requested for each capture"), null);
        return checks;
    }

    private static void permission(JSONArray checks, Context context, JSONObject capabilities, String capability,
                                   String name, String label, String permission) throws Exception {
        if (capabilities.optBoolean(capability)) add(checks, name, label, true,
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED, io.droyagent.app.NativeLocale.text("在系统弹窗中允许此功能，或关闭对应能力", "Allow this feature in the system prompt or disable the corresponding capability"), name);
    }

    private static void add(JSONArray checks, String id, String label, boolean required, boolean passed,
                            String detail, String permission) throws Exception {
        JSONObject check = new JSONObject().put("id", id).put("label", label).put("required", required)
            .put("status", passed ? "passed" : required ? "pending" : "skipped").put("detail", detail == null ? "" : detail);
        if (permission != null) check.put("permission", permission);
        checks.put(check);
    }

    private static JSONObject receipt(SharedPreferences setup, String key) throws Exception {
        return new JSONObject(setup.getString(key, "{}"));
    }

    private static Set<String> keys(JSONObject value) {
        Set<String> keys = new LinkedHashSet<>();
        for (java.util.Iterator<String> iterator = value.keys(); iterator.hasNext();) keys.add(iterator.next());
        return keys;
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) throw new IllegalStateException(io.droyagent.app.NativeLocale.text("安装配置未保存，请重试", "Installation configuration was not saved. Please retry"));
    }
}
