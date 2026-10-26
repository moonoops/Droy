package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeLocaleTest {
    @Test public void installationLanguageHasExactlyTwoSupportedOutcomes() {
        for (String tag : new String[]{"zh", "zh-CN", "zh-Hans-CN", "zh-TW", "ZH_hk"})
            assertEquals(tag, "zh", NativeLocale.normalize(tag));
        for (String tag : new String[]{"en", "en-US", "ja-JP", "fr", "", "zhh", "x-zh"})
            assertEquals(tag, "en", NativeLocale.normalize(tag));
        assertEquals("en", NativeLocale.normalize(null));
    }
}
