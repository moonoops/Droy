package io.droyagent.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads existing native resources. Opening a destination is a separate Activity operation. */
public final class NativeExternalNavigationHost {
    private NativeExternalNavigationHost() {}

    public static boolean pendingAudio(Context context, String id) throws Exception {
        JSONObject pending = AudioCapture.get(context).pendingAuthorization();
        return pending != null && id.equals(pending.optString("captureId"))
            && "awaiting_user".equals(pending.optString("state"))
            && AudioCapture.get(context).ownerForUi(id).equals(pending.getString("owner"));
    }

    public static boolean browserExists(Context context, String id) throws Exception {
        BrowserService.validateId(id);
        JSONObject result = new BrowserSession(context).executeRemote("browser_status", new JSONObject(), () -> {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        });
        JSONArray tabs = result.getJSONArray("tabs");
        for (int index = 0; index < tabs.length(); index++)
            if (id.equals(tabs.getJSONObject(index).getString("sessionId"))) return true;
        return false;
    }

    public static boolean wakeAvailable(Context context, long id) { return VoiceWake.get(context).validWakeEvent(id); }
}
