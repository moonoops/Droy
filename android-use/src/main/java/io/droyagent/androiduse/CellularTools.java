package io.droyagent.androiduse;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.SystemClock;
import android.telephony.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.json.JSONArray;
import org.json.JSONObject;

/** Framework radio facts; network quality indicators are not subscriber VIP classifications. */
public final class CellularTools {
    private final Context context;
    private final TelephonyManager telephony;
    private final SubscriptionManager subscriptions;

    public CellularTools(Context context) {
        this.context = context.getApplicationContext();
        telephony = this.context.getSystemService(TelephonyManager.class);
        subscriptions = this.context.getSystemService(SubscriptionManager.class);
    }

    public JSONObject capabilities() throws Exception {
        return new JSONObject().put("supported", telephony != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
                .put("phonePermission", granted(Manifest.permission.READ_PHONE_STATE))
                .put("cellPermission", granted(Manifest.permission.ACCESS_FINE_LOCATION))
                .put("locationEnabled", locationEnabled()).put("subscriberPriorityAvailable", false)
                .put("qosAccess", "not_exposed_by_public_framework");
    }

    public JSONObject execute(JSONObject args, String owner, Cancellation cancellation) throws Exception {
        cancellation.check();
        String operation = args.optString("operation", "status");
        if (operation.equals("status")) return capabilities().put("status", "queried");
        if (telephony == null || !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            throw error("unsupported", R.string.network_hardware_unavailable);
        require(Manifest.permission.READ_PHONE_STATE);
        return switch (operation) {
            case "query" -> query(args, cancellation);
            case "sample" -> sample(args, cancellation);
            default -> throw error("invalid_argument", R.string.network_invalid_operation);
        };
    }

    private JSONObject query(JSONObject args, Cancellation cancellation) throws Exception {
        require(Manifest.permission.READ_PHONE_STATE);
        JSONArray rows = new JSONArray();
        List<SubscriptionInfo> active = subscriptions == null ? null : subscriptions.getActiveSubscriptionInfoList();
        int selected = args.has("subscriptionId") ? HardwareSupport.integer(args, "subscriptionId", -1, 0, Integer.MAX_VALUE) : -1;
        TelephonyManager cellManager = telephony;
        if (active != null) for (SubscriptionInfo subscription : active) {
            if (selected >= 0 && selected != subscription.getSubscriptionId()) continue;
            cancellation.check();
            TelephonyManager manager = telephony.createForSubscriptionId(subscription.getSubscriptionId());
            if (selected >= 0 || subscription.getSubscriptionId() == SubscriptionManager.getDefaultDataSubscriptionId()) cellManager = manager;
            JSONObject row = new JSONObject().put("subscriptionId", subscription.getSubscriptionId())
                    .put("slotIndex", subscription.getSimSlotIndex()).put("carrier", subscription.getCarrierName())
                    .put("isDefaultData", subscription.getSubscriptionId() == SubscriptionManager.getDefaultDataSubscriptionId())
                    .put("dataNetworkType", manager.getDataNetworkType()).put("voiceNetworkType", manager.getVoiceNetworkType())
                    .put("networkOperator", manager.getNetworkOperator()).put("networkOperatorName", manager.getNetworkOperatorName())
                    .put("networkRoaming", manager.isNetworkRoaming()).put("dataState", manager.getDataState());
            ServiceState service = manager.getServiceState();
            row.put("serviceState", service == null ? JSONObject.NULL : new JSONObject().put("state", service.getState())
                    .put("roaming", service.getRoaming()).put("operatorAlphaLong", service.getOperatorAlphaLong()));
            SignalStrength strength = manager.getSignalStrength();
            JSONArray signals = new JSONArray();
            if (strength != null) for (CellSignalStrength value : strength.getCellSignalStrengths()) signals.put(signal(value));
            row.put("signals", signals).put("signalTimestampElapsedMs", strength == null ? JSONObject.NULL : strength.getTimestampMillis());
            rows.put(row);
        }
        if (selected >= 0 && rows.length() == 0) throw error("invalid_argument", R.string.network_invalid_argument);
        JSONObject result = new JSONObject().put("status", "queried").put("source", "android_telephony")
                .put("capturedAt", System.currentTimeMillis()).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("subscriptions", rows).put("subscriberPriority", JSONObject.NULL)
                .put("subscriberPriorityReason", "not_exposed_by_public_framework");
        if (args.optBoolean("includeCells", true)) {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) result.put("cells", new JSONObject().put("state", "permission_required")
                    .put("permission", Manifest.permission.ACCESS_FINE_LOCATION).put("results", new JSONArray()));
            else if (!locationEnabled()) result.put("cells", new JSONObject().put("state", "location_disabled").put("results", new JSONArray()));
            else result.put("cells", cells(cellManager, args, cancellation));
        }
        cancellation.check();
        return result;
    }

    private JSONObject cells(TelephonyManager manager, JSONObject args, Cancellation cancellation) throws Exception {
        int limit = HardwareSupport.integer(args, "limit", 40, 1, 100);
        List<CellInfo> values;
        String scope = "all_visible_radios", state = "cached";
        if (args.optBoolean("refresh", false)) {
            int timeout = HardwareSupport.integer(args, "timeoutMs", 2000, 100, 5000);
            CompletableFuture<List<CellInfo>> future = new CompletableFuture<>();
            manager.requestCellInfoUpdate(context.getMainExecutor(), new TelephonyManager.CellInfoCallback() {
                @Override public void onCellInfo(List<CellInfo> infos) { future.complete(infos); }
                @Override public void onError(int code, Throwable detail) { future.completeExceptionally(new RadioUpdateException(code)); }
            });
            long deadline = SystemClock.elapsedRealtime() + Math.min(timeout, cancellation.remainingMillis(timeout));
            try {
                while (!future.isDone() && SystemClock.elapsedRealtime() < deadline) {
                    cancellation.check();
                    try { future.get(50, TimeUnit.MILLISECONDS); } catch (TimeoutException waiting) { }
                }
                cancellation.check(); require(Manifest.permission.ACCESS_FINE_LOCATION);
                if (!future.isDone()) return new JSONObject().put("state", "update_timeout").put("results", new JSONArray());
                values = future.get(); scope = "requested_subscription"; state = "update_callback";
            } catch (java.util.concurrent.ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof RadioUpdateException failed) return new JSONObject().put("state", "update_failed")
                        .put("androidError", failed.code).put("results", new JSONArray());
                throw error;
            }
        } else values = manager.getAllCellInfo();
        JSONArray rows = new JSONArray();
        if (values != null) for (CellInfo cell : values) {
            cancellation.check();
            if (rows.length() >= limit) break;
            JSONObject row = new JSONObject().put("registered", cell.isRegistered()).put("connectionStatus", cell.getCellConnectionStatus())
                    .put("observedElapsedMs", cell.getTimestampMillis())
                    .put("ageMs", Math.max(0, SystemClock.elapsedRealtime() - cell.getTimestampMillis()));
            if (cell instanceof CellInfoLte lte) {
                CellIdentityLte identity = lte.getCellIdentity();
                row.put("technology", "lte").put("signal", signal(lte.getCellSignalStrength()))
                        .put("mcc", identity.getMccString()).put("mnc", identity.getMncString())
                        .put("ci", NetworkToolValues.identity(identity.getCi())).put("pci", NetworkToolValues.identity(identity.getPci()))
                        .put("tac", NetworkToolValues.identity(identity.getTac())).put("earfcn", NetworkToolValues.identity(identity.getEarfcn()))
                        .put("bandwidthKhz", NetworkToolValues.identity(identity.getBandwidth())).put("bands", new JSONArray(identity.getBands()));
            } else if (cell instanceof CellInfoNr nr) {
                CellIdentityNr identity = (CellIdentityNr) nr.getCellIdentity();
                row.put("technology", "nr").put("signal", signal(nr.getCellSignalStrength()))
                        .put("mcc", identity.getMccString()).put("mnc", identity.getMncString())
                        .put("nci", NetworkToolValues.identity(identity.getNci())).put("pci", NetworkToolValues.identity(identity.getPci()))
                        .put("tac", NetworkToolValues.identity(identity.getTac())).put("nrarfcn", NetworkToolValues.identity(identity.getNrarfcn()))
                        .put("bands", new JSONArray(identity.getBands()));
            } else if (cell instanceof CellInfoGsm gsm) {
                CellIdentityGsm identity = gsm.getCellIdentity();
                row.put("technology", "gsm").put("signal", signal(gsm.getCellSignalStrength()))
                        .put("mcc", identity.getMccString()).put("mnc", identity.getMncString())
                        .put("cid", NetworkToolValues.identity(identity.getCid())).put("lac", NetworkToolValues.identity(identity.getLac()))
                        .put("arfcn", NetworkToolValues.identity(identity.getArfcn()));
            } else if (cell instanceof CellInfoWcdma wcdma) {
                CellIdentityWcdma identity = wcdma.getCellIdentity();
                row.put("technology", "wcdma").put("signal", signal(wcdma.getCellSignalStrength()))
                        .put("mcc", identity.getMccString()).put("mnc", identity.getMncString())
                        .put("cid", NetworkToolValues.identity(identity.getCid())).put("lac", NetworkToolValues.identity(identity.getLac()))
                        .put("psc", NetworkToolValues.identity(identity.getPsc())).put("uarfcn", NetworkToolValues.identity(identity.getUarfcn()));
            } else row.put("technology", cell.getClass().getSimpleName()).put("detailsAvailable", false);
            rows.put(row);
        }
        return new JSONObject().put("state", values == null ? "unavailable" : state).put("scope", scope)
                .put("results", rows).put("truncated", values != null && values.size() > rows.length());
    }

    private static JSONObject signal(CellSignalStrength value) throws Exception {
        JSONObject result = new JSONObject().put("type", value.getClass().getSimpleName()).put("dbm", NetworkToolValues.radio(value.getDbm()))
                .put("level", value.getLevel()).put("asuRaw", value.getAsuLevel())
                .put("asu", NetworkToolValues.asu(value.getAsuLevel(), value instanceof CellSignalStrengthGsm));
        if (value instanceof CellSignalStrengthLte lte) result.put("rsrpDbm", NetworkToolValues.radio(lte.getRsrp()))
                .put("rsrqDb", NetworkToolValues.radio(lte.getRsrq())).put("rssnrDb", NetworkToolValues.radio(lte.getRssnr()))
                .put("rssiDbm", NetworkToolValues.radio(lte.getRssi())).put("cqi", NetworkToolValues.radio(lte.getCqi()))
                .put("timingAdvance", NetworkToolValues.radio(lte.getTimingAdvance()));
        if (value instanceof CellSignalStrengthNr nr) result.put("ssRsrpDbm", NetworkToolValues.radio(nr.getSsRsrp()))
                .put("ssRsrqDb", NetworkToolValues.radio(nr.getSsRsrq())).put("ssSinrDb", NetworkToolValues.radio(nr.getSsSinr()))
                .put("csiRsrpDbm", NetworkToolValues.radio(nr.getCsiRsrp())).put("csiRsrqDb", NetworkToolValues.radio(nr.getCsiRsrq()))
                .put("csiSinrDb", NetworkToolValues.radio(nr.getCsiSinr()));
        return result;
    }

    private JSONObject sample(JSONObject args, Cancellation cancellation) throws Exception {
        int duration = HardwareSupport.integer(args, "durationMs", 3000, 100, 10000);
        int interval = HardwareSupport.integer(args, "intervalMs", 1000, 250, 5000);
        JSONObject options = new JSONObject(args.toString()).put("refresh", false);
        JSONArray samples = new JSONArray();
        long deadline = SystemClock.elapsedRealtime() + Math.min(duration, cancellation.remainingMillis(duration));
        do {
            cancellation.check(); samples.put(query(options, cancellation));
            long next = Math.min(deadline, SystemClock.elapsedRealtime() + interval);
            while (SystemClock.elapsedRealtime() < next) {
                cancellation.check(); Thread.sleep(Math.max(1, Math.min(50, next - SystemClock.elapsedRealtime())));
            }
        } while (SystemClock.elapsedRealtime() < deadline);
        return new JSONObject().put("status", "sampled").put("samples", samples).put("count", samples.length());
    }

    public void closeOwner(String owner) { }
    public void close() { }
    private boolean locationEnabled() { LocationManager manager = context.getSystemService(LocationManager.class); return manager != null && manager.isLocationEnabled(); }
    private boolean granted(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private void require(String permission) throws ActionException { if (!granted(permission)) throw error("permission_required", R.string.network_permission_required); }
    private ActionException error(String code, int message) { return HardwareSupport.error(context, code, message); }
    private static final class RadioUpdateException extends Exception { final int code; RadioUpdateException(int code) { this.code = code; } }
}
