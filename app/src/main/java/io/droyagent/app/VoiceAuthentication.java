package io.droyagent.app;

import android.app.Activity;
import android.content.Intent;
import java.util.function.Consumer;

/** Kept for old clients; this release does not open voiceprint enrollment. */
final class VoiceAuthentication {
    VoiceAuthentication(Activity activity) {}
    void authenticate(Runnable success, Consumer<String> error) { error.accept(io.droyagent.app.NativeLocale.text("声纹功能暂未开放", "Voiceprint is not available yet")); }
    boolean onActivityResult(int request, int result, Intent data) { return false; }
    void cancel() {}
}
