package io.droyagent.androiduse;

import org.junit.Test;
import static org.junit.Assert.*;

public final class InputNodeOcclusionTest {
    private final InputNodeOcclusion.Bounds screen = bounds(0, 0, 1280, 2772);
    private final InputNodeOcclusion.Bounds content = bounds(0, 152, 1280, 2693);
    private final InputNodeOcclusion.Bounds navigation = bounds(0, 2693, 1280, 2772);
    private final InputNodeOcclusion.Bounds status = bounds(0, 0, 1280, 152);
    private final InputNodeOcclusion.Bounds editor = bounds(154, 2560, 998, 2700);

    @Test public void aKnownEditorMayExtendPastTheContentEdgeIntoTheNavigationBar() {
        assertTrue(allow(true, true, true, screen, content, editor, navigation));
        assertTrue(allow(true, true, true, screen, content, bounds(154, 2560, 998, 2740), navigation));
    }

    @Test public void aPassiveNavigationWindowMayExtendInsideItsDeclaredInsetWithoutHidingTheEditorCenter() {
        InputNodeOcclusion.Bounds declaredContent = bounds(0, 152, 1280, 2720);
        assertTrue(allow(true, true, true, screen, declaredContent, editor, navigation));
        assertFalse(allow(true, true, true, screen, declaredContent, bounds(154, 2680, 998, 2770), navigation));
    }

    @Test public void aSystemWindowMustCoverADeclaredBarAcrossTheWholeScreenEdge() {
        assertFalse(allow(true, true, true, screen, content, editor, bounds(100, 2693, 1180, 2772)));
        assertFalse(allow(true, true, true, screen, content, editor, bounds(0, 2693, 1280, 2750)));
        assertFalse(allow(true, true, true, screen, screen, editor, navigation));
        assertFalse(allow(true, true, true, screen, content, editor, screen));
    }

    @Test public void focusedAndActiveSystemWindowsRemainBlocking() {
        assertFalse(InputNodeOcclusion.mayIgnoreSystemBar(true, true, true, true, false, screen, content, editor, navigation));
        assertFalse(InputNodeOcclusion.mayIgnoreSystemBar(true, true, true, false, true, screen, content, editor, navigation));
    }

    @Test public void theSameRuleHandlesTheStatusBarAndRotatedSideBarWithoutPixelThresholds() {
        assertTrue(allow(true, true, true, screen, content, bounds(50, 140, 500, 240), status));
        InputNodeOcclusion.Bounds rotatedScreen = bounds(0, 0, 2772, 1280);
        assertTrue(allow(true, true, true, rotatedScreen, bounds(0, 0, 2693, 1280),
                bounds(2560, 154, 2700, 998), bounds(2693, 0, 2772, 1280)));
    }

    @Test public void aCenterUnderTheBarOrACompletelyCoveredNodeRemainsBlocked() {
        assertFalse(allow(true, true, true, screen, content, bounds(154, 2680, 998, 2770), navigation));
        assertFalse(allow(true, true, true, screen, content, bounds(154, 2700, 998, 2750), navigation));
        assertFalse(allow(true, true, true, screen, content, bounds(154, 2660, 998, 2726), navigation));
    }

    @Test public void keyboardApplicationAndUnknownWindowTypesNeverQualify() {
        for (String kind : new String[]{"input_method", "application", "accessibility_overlay", "unknown"})
            assertFalse(kind, allow(true, true, false, screen, content, editor, navigation));
    }

    @Test public void aSystemWindowInsideContentOrOutsideTheDisplayNeverQualifies() {
        assertFalse(allow(true, true, true, screen, content, editor, bounds(0, 2600, 1280, 2772)));
        assertFalse(allow(true, true, true, screen, content, editor, bounds(-1, 2693, 1280, 2772)));
        assertFalse(allow(true, true, true, screen, content, editor, bounds(0, 2693, 1280, 2773)));
    }

    @Test public void tapAndNonEditableNodesKeepTheWholeTargetOcclusionRule() {
        assertFalse(allow(false, true, true, screen, content, editor, navigation));
        assertFalse(allow(true, false, true, screen, content, editor, navigation));
    }

    @Test public void emptyUnboundedOrMismatchedGeometryDoesNotQualify() {
        assertFalse(allow(true, true, true, screen, bounds(0, 0, 0, 0), editor, navigation));
        assertFalse(allow(true, true, true, screen, bounds(0, 152, 1281, 2693), editor, navigation));
        assertFalse(allow(true, true, true, screen, content, bounds(154, 2560, 998, 2773), navigation));
        assertFalse(allow(true, true, true, screen, content, editor, bounds(0, 2693, 0, 2772)));
        assertFalse(allow(true, true, true, screen, content, bounds(154, 2000, 998, 2100), navigation));
    }

    @Test public void semanticClickCanUseTheVisiblePartOfAButtonOverlappingTheBarBySevenPixels() {
        assertTrue(passive(bounds(990, 2560, 1229, 2700), navigation));
        assertTrue(passive(bounds(0, 152, 1280, 2720), navigation));
        assertTrue(passive(bounds(154, 2680, 998, 2770), navigation));
        assertFalse(passive(bounds(154, 2700, 998, 2750), navigation));
    }

    @Test public void passiveBarExceptionNeverAppliesToRealPopupsOrInputMethodWindows() {
        assertFalse(InputNodeOcclusion.mayIgnorePassiveSystemBar(false, false, false, screen, content, editor, navigation));
        assertFalse(InputNodeOcclusion.mayIgnorePassiveSystemBar(true, true, false, screen, content, editor, navigation));
        assertFalse(InputNodeOcclusion.mayIgnorePassiveSystemBar(true, false, true, screen, content, editor, navigation));
        assertFalse(passive(editor, bounds(100, 2500, 1180, 2772)));
        assertFalse(passive(editor, screen));
    }

    private boolean passive(InputNodeOcclusion.Bounds target, InputNodeOcclusion.Bounds window) {
        return InputNodeOcclusion.mayIgnorePassiveSystemBar(true, false, false, screen, content, target, window);
    }

    private static boolean allow(boolean input, boolean editable, boolean system, InputNodeOcclusion.Bounds screen,
                                  InputNodeOcclusion.Bounds content, InputNodeOcclusion.Bounds target, InputNodeOcclusion.Bounds window) {
        return InputNodeOcclusion.mayIgnoreSystemBar(input, editable, system, false, false, screen, content, target, window);
    }
    private static InputNodeOcclusion.Bounds bounds(int left, int top, int right, int bottom) {
        return new InputNodeOcclusion.Bounds(left, top, right, bottom);
    }
}
