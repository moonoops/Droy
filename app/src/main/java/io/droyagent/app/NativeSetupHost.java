package io.droyagent.app;

import android.content.Context;
import org.json.JSONObject;

/** Local routing and acknowledgements only; completion stays behind the live setup service. */
public final class NativeSetupHost {
    private NativeSetupHost() {}

    public static JSONObject routeState(Context context) throws Exception {
        return SetupManager.nativeRouteState(context);
    }

    public static JSONObject setStep(Context context, int step) throws Exception {
        return SetupManager.nativeStep(context, step);
    }

    public static JSONObject acknowledge(Context context, String key, boolean acknowledged) throws Exception {
        return SetupManager.nativeAcknowledge(context, key, acknowledged);
    }
}
