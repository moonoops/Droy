package io.droyagent.app;

/** Notification preferences gate alerts, never message reception or task execution. */
final class NotificationDeliveryPolicy {
    private NotificationDeliveryPolicy() {}

    static boolean inheritedEnabled(boolean legacyPluginEnabled, boolean legacyLocalEnabled) {
        return legacyPluginEnabled && legacyLocalEnabled;
    }

    static String messageSuppression(boolean enabled, boolean systemEnabled, boolean channelEnabled, boolean conversationVisible) {
        if (!enabled) return "messages_disabled";
        if (!systemEnabled || !channelEnabled) return "system_disabled";
        return conversationVisible ? "conversation_visible" : null;
    }

    static boolean taskAllowed(boolean enabled, boolean providerActive) {
        return enabled && providerActive;
    }
}
