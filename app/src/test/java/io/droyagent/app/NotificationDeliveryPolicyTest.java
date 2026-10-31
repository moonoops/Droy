package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationDeliveryPolicyTest {
    @Test public void messageAndTaskSwitchesAreIndependent() {
        for (boolean messages : new boolean[]{false, true}) {
            for (boolean tasks : new boolean[]{false, true}) {
                assertEquals(messages, NotificationDeliveryPolicy.messageSuppression(messages, true, true, false) == null);
                assertEquals(tasks, NotificationDeliveryPolicy.taskAllowed(tasks, true));
            }
        }
        assertNull(NotificationDeliveryPolicy.messageSuppression(true, true, true, false));
        assertFalse(NotificationDeliveryPolicy.taskAllowed(true, false));
    }

    @Test public void systemAndVisibleConversationSuppressOnlyTheAlert() {
        assertEquals("system_disabled", NotificationDeliveryPolicy.messageSuppression(true, false, true, false));
        assertEquals("system_disabled", NotificationDeliveryPolicy.messageSuppression(true, true, false, false));
        assertEquals("conversation_visible", NotificationDeliveryPolicy.messageSuppression(true, true, true, true));
        assertNull(NotificationDeliveryPolicy.messageSuppression(true, true, true, false));
    }

    @Test public void migrationPreservesEitherLegacyOffChoice() {
        assertTrue(NotificationDeliveryPolicy.inheritedEnabled(true, true));
        assertFalse(NotificationDeliveryPolicy.inheritedEnabled(false, true));
        assertFalse(NotificationDeliveryPolicy.inheritedEnabled(true, false));
        assertFalse(NotificationDeliveryPolicy.inheritedEnabled(false, false));
    }
}
