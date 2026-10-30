package io.droyagent.app;

import android.content.Context;
import android.content.Intent;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

/** Native presentation uses the existing service lifecycle without exposing its credentials. */
public final class NativeRuntimeHost {
    private static final AtomicInteger visible = new AtomicInteger();
    private static volatile boolean companionMaintenance;
    private static final NativeConversationVisibility conversations = new NativeConversationVisibility();
    private static final NativeConversationVisibility companions = new NativeConversationVisibility();
    private NativeRuntimeHost() {}

    public static final class Snapshot {
        public final boolean ready, active, canStart;
        public final String status, locale;
        private Snapshot(Context context) {
            ready = RuntimeService.ready;
            active = RuntimeService.isStartingOrRunning();
            canStart = RuntimeService.canStart();
            status = NativeLocale.generatedStatus(RuntimeService.status);
            locale = NativeLocale.active();
        }
    }

    public static Snapshot inspect(Context context) { return new Snapshot(context); }

    public static boolean companionMaintenanceActive() { return companionMaintenance; }
    static synchronized void companionMaintenance(boolean active) { companionMaintenance = active; }
    static synchronized boolean claimCompanionMaintenance() {
        if (companionMaintenance) return false;
        companionMaintenance = true; return true;
    }

    public static String runtimeIdentity() {
        return RuntimeService.ready ? RuntimeService.runtimeIdentity : "";
    }

    /** Mirror a language already accepted by the shared DSH settings service. */
    public static void adoptLocale(Context context, String language) {
        NativeLocale.adopt(language);
        RuntimeService.refreshExecutionProgress();
        AudioCaptureService.refreshLocale(context);
        ProactiveNotifications.refreshLocale(context);
        io.droyagent.app.history.HistoryLocationService.refreshLocale(context);
        PetOverlay.refresh(context);
    }

    public static void presentConversation(Context context, Object owner, String sessionId, boolean presented) {
        String current = conversations.update(owner, sessionId, presented);
        PetOverlay.conversation(context, current == null ? "" : current, current != null);
    }

    public static void clearCompanionNotifications(Context context, String companionId) throws Exception {
        ProactiveNotifications.get(context).clearCompanion(companionId);
    }

    public static void presentCompanion(Object owner, String companionId, boolean presented) {
        companions.update(owner, companionId, presented);
    }

    static boolean isCompanionVisible(String companionId) {
        return isVisible() && companionId.equals(companions.current());
    }

    static boolean isConversationVisible(String sessionId) {
        return isVisible() && sessionId.equals(conversations.current());
    }

    public static void backgroundWorkChanged() { RuntimeService.refreshBackgroundPolicy(); }

    public static boolean isFilePickerOpen() {
        return ChatFileTransfers.isPickerOpen() || io.droyagent.app.media.MediaInputController.isPickerOpen()
            || io.droyagent.app.nativeui.media.NativeMediaViewModel.isPickerOpen()
            || io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationMediaCoordinator.isPickerOpen();
    }

    public static boolean hasMediaRuntimeWork() {
        return ChatFileTransfers.hasRuntimeWork() || io.droyagent.app.media.MediaInputController.isPickerOpen()
            || io.droyagent.app.nativeui.media.NativeMediaViewModel.hasRuntimeWork()
            || io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationMediaCoordinator.hasRuntimeWork();
    }

    public static void start(Context context) {
        if (context != null && NativeCompanionMemoryPurge.pending(context)) {
            NativeCompanionMemoryPurge.resume(context); return;
        }
        if (!RuntimeService.canStart()) return;
        if (NativeCompanionDeletion.pending(context)) { companionMaintenance = true; return; }
        context.startForegroundService(new Intent(context, RuntimeService.class));
    }

    public static void stop(Context context) { RuntimeService.requestStop(context); }

    public static void resumeNotificationManagement(Context context) {
        BackgroundRuntime.startedByUser(context);
        RuntimeService.resumeOperations(context);
        start(context);
        NotificationManagement.get(context).rescan();
    }

    public static void onStarted(Context context) {
        visible.incrementAndGet();
        SetupManager.initialize(context);
        // Native onResume owns its one foreground start attempt, after setup initialization.
        BackgroundRuntime.activityStarted(context, false);
    }

    public static void onStopped() {
        if (visible.get() > 0) {
            visible.decrementAndGet();
            BackgroundRuntime.activityStopped();
        }
    }

    static boolean isVisible() { return visible.get() > 0; }

    public static Endpoint connection() {
        int port = RuntimeService.webPort;
        String token = RuntimeService.webToken;
        if (!RuntimeService.ready || port < 1 || port > 65535 || token.length() < 32) return null;
        return new Endpoint(port, token);
    }

    static boolean allowsPath(String path) {
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.indexOf('\\') >= 0
                || path.codePoints().anyMatch(Character::isISOControl)) return false;
        try {
            java.net.URI uri = new java.net.URI(path);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getFragment() != null) return false;
            for (String segment : uri.getPath().replace('\\', '/').split("/", -1))
                if (segment.equals(".") || segment.equals("..") || segment.codePoints().anyMatch(Character::isISOControl)) return false;
        } catch (java.net.URISyntaxException invalid) { return false; }
        String petId = "(?:default|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12})";
        return path.startsWith("/droy/native/")
            || path.equals("/droy/companions") || path.startsWith("/droy/companions/") || path.startsWith("/droy/companions?")
            || path.equals("/droy/setup/status") || path.equals("/droy/setup/model-test")
            || path.equals("/droy/setup/native/status") || path.equals("/droy/setup/native/identity-confirm") || path.equals("/droy/setup/native/complete")
            || path.equals("/droy/notify/plugin") || path.equals("/droy/pets") || path.equals("/droy/voice")
            || path.matches("/droy/pets/(?:create|retry|generate)")
            || path.matches("/droy/pets/status\\?id=" + petId)
            || path.matches("/droy/pets/asset\\?id=" + petId + "&file=(?:pet\\.json|[A-Za-z0-9_-]+\\.png)");
    }

    public static final class Endpoint {
        private final int port;
        private final String token;
        private Endpoint(int port, String token) { this.port = port; this.token = token; }

        public boolean isCurrent() {
            return RuntimeService.ready && RuntimeService.webPort == port && token.equals(RuntimeService.webToken);
        }

        public HttpURLConnection open(String path) throws IOException {
            if (!isCurrent()) throw new IOException("RUNTIME_CONNECTION_CHANGED");
            if (!allowsPath(path))
                throw new IllegalArgumentException("INVALID_NATIVE_API_PATH");
            HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            // Setup owns one 90-second deadline for its text and tool-protocol checks.
            connection.setReadTimeout(path.equals("/droy/setup/model-test") ? 95000
                : path.equals("/droy/native/speech/transcribe") ? 180000 : 30000);
            connection.setRequestProperty("Cookie", "droy_session=" + token);
            connection.setRequestProperty("Accept", "application/json");
            return connection;
        }
    }
}
