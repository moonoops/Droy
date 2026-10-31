package io.droyagent.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Facts and the existing authorization Activity; this class never grants capture permission. */
public final class NativeStandardVoiceHost {
    private NativeStandardVoiceHost() {}

    public static boolean screenAvailable(Context context) { return VoiceWake.screenAvailable(context); }
    public static boolean foreground() { return NativeRuntimeHost.isVisible() || AudioPermissionActivity.isVisible(); }
    public static boolean productAccessGranted(Context context) {
        android.content.SharedPreferences product = ProductPermissions.preferences(context);
        return ProductPermissions.allowed(product, "allowAudioMicrophone") && ProductPermissions.allowed(product, "allowAudioTranscription");
    }

    public static void stopAfterOwnerDestroyed(Context context, String runtimeIdentity, String operationId) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            if (!runtimeIdentity.equals(NativeRuntimeHost.runtimeIdentity())) return;
            try { VoiceRuntime.request(app, new JSONObject().put("operation", "stop").put("voiceOperationId", operationId)); }
            catch (VoiceSessionOwnership.Failure foreign) { /* Another owner keeps its capture and output. */ }
            catch (Exception failure) { android.util.Log.w("DroyVoice", "Native voice owner cleanup could not be confirmed"); }
        }, "droy-native-voice-owner-close").start();
    }

    public static void openAudioPermission(Activity activity, String captureId) throws Exception {
        if (activity.isFinishing() || activity.isDestroyed() || !NativeRuntimeHost.isVisible())
            throw new IllegalStateException("native_standard_voice_screen_unavailable");
        JSONObject pending = AudioCapture.get(activity).pendingAuthorization();
        if (pending == null || !captureId.equals(pending.optString("captureId")))
            throw new IllegalStateException("native_standard_voice_owner");
        activity.startActivity(new Intent(activity, AudioPermissionActivity.class).putExtra("captureId", captureId));
    }
}
