package io.droyagent.androiduse;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NodeSemanticsTest {
    @Test public void countdownDigitsChangeWithoutChangingTheActionOrAmount() {
        assertEquals(NodeSemantics.stableCountdownText("抢补贴再减300元｜00:18:47.6后开抢"),
                NodeSemantics.stableCountdownText("抢补贴再减300元｜00:11:11.1后开抢"));
        assertEquals("剩余 <countdown>", NodeSemantics.stableCountdownText("剩余 01:23:45"));
        assertNotEquals(NodeSemantics.stableCountdownText("抢补贴再减300元｜00:18:47.6后开抢"),
                NodeSemantics.stableCountdownText("抢补贴再减500元｜00:11:11.1后开抢"));
        assertNotEquals(NodeSemantics.stableCountdownText("抢补贴再减300元｜00:00:01.0后开抢"),
                NodeSemantics.stableCountdownText("抢券再减300元｜抢券进行中"));
    }

    @Test public void ordinaryTimesAndPricesRemainExactEvenBesideACountdown() {
        for (String text : new String[]{"出发时间 12:34:56", "预约 10:00:00", "价格 4999.00", "00:12:34"})
            assertEquals(text, NodeSemantics.stableCountdownText(text));
        assertEquals("预约10:00:00，剩余<countdown>", NodeSemantics.stableCountdownText("预约10:00:00，剩余00:12:34"));
        assertEquals("倒计时 00:99:01", NodeSemantics.stableCountdownText("倒计时 00:99:01"));
    }

    @Test public void explicitSearchRegionRolesAreRecognizedAcrossLabelsAndNativeTypes() {
        assertTrue(NodeSemantics.searchEntry("android.view.ViewGroup", "", "搜索栏"));
        assertTrue(NodeSemantics.searchEntry("android.view.ViewGroup", "", "Search bar"));
        assertTrue(NodeSemantics.searchEntry("android.widget.SearchView", "", ""));
        assertTrue(NodeSemantics.searchEntry("android.view.ViewGroup", "example.app:id/search_bar", ""));
    }

    @Test public void businessTextAndSubmitButtonsDoNotDeclareAStableRegion() {
        assertFalse(NodeSemantics.searchEntry("android.widget.TextView", "", "商品搜索框推荐"));
        assertFalse(NodeSemantics.searchEntry("android.widget.Button", "example.app:id/search_button", "搜索"));
        assertFalse(NodeSemantics.searchEntry("android.view.ViewGroup", "example.app:id/product_card", ""));
    }
}
