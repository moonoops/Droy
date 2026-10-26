package io.droyagent.app;

import java.util.function.Predicate;
import java.util.function.Function;
import org.json.JSONArray;
import org.json.JSONObject;

final class CapabilityAuthorization {
    private CapabilityAuthorization() {}

    static String operationKey(JSONObject tool, JSONObject args) throws org.json.JSONException {
        if (!tool.has("operationPermissions")) return null;
        Object selected = args.opt("operation");
        if (!(selected instanceof String) || ((String) selected).isBlank())
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("必须指定有效的操作", "A valid operation must be specified"));
        String operation = (String) selected;
        if (tool.optString("action").equals("process_memory")
                && !java.util.Set.of("query", "open", "regions", "status", "close").contains(operation))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的操作", "Unsupported operation"));
        if (operation.equals("open")) {
            Object mode = args.has("mode") ? args.opt("mode") : "read_only";
            if (!(mode instanceof String) || (!mode.equals("read_only") && !mode.equals("read_write")))
                throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("mode 必须为 read_only 或 read_write", "mode must be read_only or read_write"));
            operation += "_" + mode;
        }
        if (!tool.getJSONObject("operationPermissions").has(operation))
            throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持的操作", "Unsupported operation"));
        return operation;
    }

    static JSONObject forArguments(JSONObject tool, JSONObject args) throws org.json.JSONException {
        String operation = operationKey(tool, args);
        if (operation == null) return tool;
        JSONArray required = new JSONArray(tool.getJSONArray("permissions").toString());
        JSONArray specific = tool.getJSONObject("operationPermissions").getJSONArray(operation);
        for (int i = 0; i < specific.length(); i++) required.put(specific.getString(i));
        return new JSONObject(tool.toString()).put("permissions", required);
    }

    static void check(JSONObject tool, Function<String, JSONObject> groups, Predicate<String> enabled,
                      boolean rootRequested) throws org.json.JSONException {
        if (rootRequested && !enabled.test("allowRoot")) throw new SecurityException(io.droyagent.app.NativeLocale.text("Root 通道未获授权或已撤销", "Root access was not granted or has been revoked"));
        JSONArray permissions = tool.getJSONArray("permissions");
        for (int i = 0; i < permissions.length(); i++) {
            JSONObject group = groups.apply(permissions.getString(i));
            if (!enabled.test(group.getString("preference")))
                throw new SecurityException(io.droyagent.app.NativeLocale.text("请先在权限设置中允许：", "Please allow this in Permissions first: ") + group.getString("label"));
        }
    }
}
