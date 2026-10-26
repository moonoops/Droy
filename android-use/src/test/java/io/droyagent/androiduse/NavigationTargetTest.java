package io.droyagent.androiduse;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class NavigationTargetTest {
    private static NavigationTarget.Candidate item(String key, String bounds, int clarity) {
        return new NavigationTarget.Candidate(key, bounds, clarity);
    }

    @Test public void browseIdentityKeepsProductNameButIgnoresPriceAndSalesSuffix() {
        assertEquals("label:练习面包", NavigationTarget.key("", "练习面包 54.30元 1000+人付款", ""));
        assertEquals(NavigationTarget.key("", "练习面包 54.30元 1000+人付款", ""),
                NavigationTarget.key("", "练习面包 59.90元 2000+人付款", ""));
        assertNotEquals(NavigationTarget.key("", "练习面包 500g 54.30元", ""),
                NavigationTarget.key("", "练习面包 750g 54.30元", ""));
    }

    @Test public void duplicateVisualObjectSelectsMostExplicitNode() {
        assertEquals(2, NavigationTarget.select(List.of(item("", "card", 0),
                item("label:面包", "card", 1), item("label:面包", "card", 2)), "label:面包"));
        assertEquals(1, NavigationTarget.select(List.of(item("id:wrapper", "card", 0),
                item("label:面包", "card", 2)), "label:面包"));
    }

    @Test public void differentObjectsAtSameBoundsAndSameNamesAtDifferentBoundsRemainAmbiguous() {
        assertEquals(-1, NavigationTarget.select(List.of(item("label:面包", "card", 2),
                item("label:进店", "card", 2)), "label:面包"));
        assertEquals(-1, NavigationTarget.select(List.of(item("label:面包", "left", 2),
                item("label:面包", "right", 2)), "label:面包"));
    }

    @Test public void separateSmallButtonsDoNotChangeTheCardIdentity() {
        assertEquals(0, NavigationTarget.select(List.of(item("label:面包", "card", 2),
                item("label:更多", "corner", 2)), "label:面包"));
        assertEquals(-1, NavigationTarget.select(List.of(item("label:其他商品", "card", 2)), "label:面包"));
    }

    @Test public void genericDescriptionDoesNotDiscardTheVisibleObjectName() {
        assertNotEquals(NavigationTarget.key("商品甲", "查看详情", ""), NavigationTarget.key("商品乙", "查看详情", ""));
    }

    @Test public void onlyVolatileTokensAreRemovedNotFollowingShopOrSku() {
        assertEquals("label:面包 甲店 500g", NavigationTarget.key("", "面包 54元 1000+人付款 甲店 500g", ""));
        assertNotEquals(NavigationTarget.key("", "面包 54元 甲店", ""), NavigationTarget.key("", "面包 59元 乙店", ""));
    }
}
