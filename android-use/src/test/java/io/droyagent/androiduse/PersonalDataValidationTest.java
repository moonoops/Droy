package io.droyagent.androiduse;

import org.junit.Test;
import static org.junit.Assert.*;

public class PersonalDataValidationTest {
    @Test public void rangesAreBoundedAndIncreasing() throws Exception {
        PersonalDataTools.validateRange(1000, 2000, PersonalDataTools.MAX_RANGE_MS);
        reject(() -> PersonalDataTools.validateRange(-1, 2000, PersonalDataTools.MAX_RANGE_MS));
        reject(() -> PersonalDataTools.validateRange(1000, 1000, PersonalDataTools.MAX_RANGE_MS));
        reject(() -> PersonalDataTools.validateRange(1000, 1000 + PersonalDataTools.MAX_RANGE_MS + 1, PersonalDataTools.MAX_RANGE_MS));
    }

    @Test public void contactsRequireAnExplicitBoundedSearch() throws Exception {
        assertEquals("示例联系人", PersonalDataTools.search(" 示例联系人 ", true));
        reject(() -> PersonalDataTools.search(" ", true));
        reject(() -> PersonalDataTools.search("x".repeat(201), true));
        reject(() -> PersonalDataTools.search("sample\0name", true));
        reject(() -> PersonalDataTools.search(123, true));
    }

    @Test public void limitsRejectFractionalAndOversizedRequests() throws Exception {
        assertEquals(20, PersonalDataTools.limit(null, 20, 50));
        assertEquals(50, PersonalDataTools.limit(50, 20, 50));
        reject(() -> PersonalDataTools.limit(51, 20, 50));
        reject(() -> PersonalDataTools.limit(1.5, 20, 50));
        reject(() -> PersonalDataTools.limit(Double.NaN, 20, 50));
        reject(() -> PersonalDataTools.limit("10", 20, 50));
    }

    @Test public void longProviderFieldsAreClippedAndMarkTheirItem() {
        PersonalDataTools.TextBudget text = new PersonalDataTools.TextBudget();
        text.beginItem();
        assertEquals(1000, text.take("Fictional title ".repeat(1000), 1000).length());
        assertEquals(1000, text.take("Fictional location ".repeat(1000), 1000).length());
        assertEquals(100, text.take("Fictional timezone ".repeat(100), 100).length());
        assertTrue(text.itemTruncated);
        assertTrue(text.truncated);
        text.beginItem();
        assertEquals("2025550101", text.take("2025550101", 200));
        assertFalse(text.itemTruncated);
        assertTrue("The query still records the earlier clipped item", text.truncated);
        assertEquals(200, text.take("0".repeat(10000), 200).length());
        assertEquals(200, text.take("Fictional label ".repeat(1000), 200).length());
        assertTrue(text.itemTruncated);
    }

    @Test public void manyProviderRowsCannotExceedTheSharedTextBudget() {
        PersonalDataTools.TextBudget text = new PersonalDataTools.TextBudget();
        int emitted = 0;
        for (int item = 0; item < 100; item++) {
            text.beginItem();
            emitted += text.take("t".repeat(1000), 1000).length();
            emitted += text.take("l".repeat(1000), 1000).length();
            emitted += text.take("UTC", 100).length();
        }
        assertEquals(24000, emitted);
        assertEquals(emitted, text.used);
        assertTrue(text.exhausted());
        assertTrue(text.truncated);
    }

    @Test public void clippingDoesNotSplitSupplementaryCharacters() {
        PersonalDataTools.TextBudget text = new PersonalDataTools.TextBudget();
        String clipped = text.take("Fixture🌙suffix", 8);
        assertEquals("Fixture", clipped);
        assertTrue(text.itemTruncated);
    }

    private interface Checked { void run() throws Exception; }
    private static void reject(Checked action) throws Exception {
        try { action.run(); fail("Expected invalid_argument"); }
        catch (ActionException expected) { assertEquals("invalid_argument", expected.getCode()); }
    }
}
