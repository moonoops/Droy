package io.droyagent.androiduse;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NodeActionsTest {
    @Test public void exposesOnlyActionsTheNodeActuallySupports() throws Exception {
        assertEquals(List.of("click", "long_click", "scroll_down"), NodeActions.supported(List.of(
                AccessibilityNodeInfo.ACTION_CLICK, AccessibilityNodeInfo.ACTION_LONG_CLICK,
                android.R.id.accessibilityActionScrollDown, 123456)));
        assertEquals(List.of(), NodeActions.supported(List.of(123456)));
    }

    @Test public void unsupportedOperationsCannotBecomeArbitraryAccessibilityActions() {
        for (Object value : new Object[]{"click", "focus", "set_text", "123456", 123456, null})
            assertThrows(ActionException.class, () -> NodeActions.operation(value));
    }

    @Test public void allAdvertisedOperationsHaveDistinctPublicActionIds() throws Exception {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        for (String operation : NodeActions.OPERATIONS) assertTrue(ids.add(NodeActions.id(operation)));
        assertEquals(7, ids.size());
    }
}
