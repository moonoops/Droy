package io.droyagent.app;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Availability is operation-specific; argument-dependent requirements remain explicit. */
final class HardwareToolState {
    private HardwareToolState() {}
    static JSONObject describe(JSONObject tool, JSONObject grants, boolean stopped, JSONObject hardware) throws Exception {
        String action = tool.getString("action");
        JSONObject nativeState = hardware.optJSONObject(action);
        JSONObject system = hardware.optJSONObject("permissions");
        if (system == null) system = new JSONObject();
        boolean supported = nativeState == null || nativeState.optBoolean("supported", true);
        JSONObject operations = new JSONObject();
        boolean any = false, anyProduct = false;
        JSONArray names = tool.getJSONObject("parameters").getJSONObject("operation").getJSONArray("enum");
        for (int index = 0; index < names.length(); index++) {
            String operation = names.getString(index);
            JSONObject args = new JSONObject().put("operation", operation);
            JSONObject selected = CapabilityAuthorization.forArguments(tool, args);
            JSONArray required = selected.getJSONArray("permissions"), reasons = new JSONArray();
            boolean cleanup = HardwareRuntime.cleanup(action, args);
            if (stopped && !cleanup) reasons.put("operations_paused");
            for (int permission = 0; permission < required.length(); permission++)
                if (!grants.optBoolean(required.getString(permission))) reasons.put("product_permission:" + required.getString(permission));
            boolean product = reasons.length() == 0;
            boolean local = action.equals("nfc_emulation") && !operation.equals("enable")
                || action.equals("nfc") && Set.of("credentials", "credential_add", "credential_delete").contains(operation)
                || action.equals("wifi") && Set.of("credentials", "credential_add", "credential_delete").contains(operation);
            if (!local && !cleanup && !Set.of("status", "list").contains(operation) && !supported) reasons.put("unsupported_hardware");
            if (!cleanup && !Set.of("status", "list").contains(operation)) {
                switch (action) {
                    case "location" -> {
                        if (!system.optBoolean("locationApproximate") && !system.optBoolean("locationPrecise")) reasons.put("location_permission_required");
                        if (!system.optBoolean("locationEnabled")) reasons.put("location_disabled");
                    }
                    case "bluetooth" -> {
                        if (!system.optBoolean(operation.equals("scan") ? "bluetoothScan" : "bluetoothConnect")) reasons.put("nearby_permission_required");
                        if (!system.optBoolean("bluetoothEnabled")) reasons.put("bluetooth_disabled");
                    }
                    case "nfc", "nfc_emulation" -> {
                        if (!local && !system.optBoolean("nfcEnabled")) reasons.put("nfc_disabled");
                    }
                    case "wifi" -> {
                        if (operation.equals("scan") && !system.optBoolean("wifiScan")) reasons.put("scan_location_required");
                    }
                    case "cellular" -> { if (!system.optBoolean("phoneState")) reasons.put("phone_permission_required"); }
                }
            }
            boolean available = reasons.length() == 0;
            operations.put(operation, new JSONObject().put("productAllowed", product).put("available", available).put("reasons", reasons)
                .put("checksArgumentsAtExecution", true));
            any |= available; anyProduct |= product;
        }
        return new JSONObject().put("action", action).put("name", tool.getString("name"))
            .put("available", any).put("productAllowed", anyProduct).put("operations", operations)
            .put("system", nativeState == null ? new JSONObject() : nativeState);
    }
}
