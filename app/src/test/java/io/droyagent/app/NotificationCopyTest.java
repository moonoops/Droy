package io.droyagent.app;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NotificationCopyTest {
    @Test public void pausedAndTransitionalTitlesAreDistinctInBothLanguages() {
        try {
            for (String language : new String[]{"zh", "en"}) {
                NativeLocale.mirrorProcessLanguage(language);
                Set<String> titles = new HashSet<>();
                for (String phase : new String[]{"executing", "pausing", "paused", "resuming", "stopping"}) {
                    String title = NotificationCopy.title(true, true, phase, 0);
                    assertFalse(title.isBlank());
                    if (language.equals("en")) assertFalse(title.matches(".*[\\p{IsHan}].*"));
                    titles.add(title);
                }
                assertEquals(5, titles.size());
            }
        } finally { NativeLocale.mirrorProcessLanguage("zh"); }
    }

    @Test public void allIdleVariantsRemainIdleAfterACompletedOrInterruptedTask() {
        for (int seed : new int[]{0, 1, 2, -1, Integer.MIN_VALUE}) {
            assertEquals(NotificationCopy.title(true, false, "completed", seed),
                    NotificationCopy.title(true, false, "interrupted", seed));
            assertFalse(NotificationCopy.title(true, false, "executing", seed).contains("正在处理任务"));
            assertEquals(NotificationCopy.title(false, true, "executing", seed),
                    NotificationCopy.title(false, false, "", seed));
        }
    }

    @Test public void statusTitlesStayStableWhileMessageSummariesKeepTheirEventChoices() {
        Set<String> titles = new HashSet<>(), important = new HashSet<>(), reminders = new HashSet<>(), ads = new HashSet<>();
        for (int seed = 0; seed < 3; seed++) {
            titles.add(NotificationCopy.title(true, true, "executing", seed));
            important.add(NotificationCopy.important("测试应用", seed));
            reminders.add(NotificationCopy.reminder("测试应用", seed));
            ads.add(NotificationCopy.ads(7, seed));
            assertEquals(NotificationCopy.reminder("测试应用", seed), NotificationCopy.reminder("测试应用", seed));
        }
        assertEquals(1, titles.size());
        assertEquals(3, important.size());
        assertEquals(3, reminders.size());
        assertEquals(3, ads.size());
    }

    @Test public void switchingLanguageKeepsTheAppNameAndCountInEveryVariant() {
        try {
            for (String language : new String[]{"zh", "en", "zh"}) {
                NativeLocale.mirrorProcessLanguage(language);
                for (int seed = 0; seed < 3; seed++) {
                    String app = "示例 App 100%";
                    assertTrue(NotificationCopy.important(app, seed).contains(app));
                    assertTrue(NotificationCopy.reminder(app, seed).contains(app));
                    assertTrue(NotificationCopy.ads(7, seed).contains("7"));
                    assertFalse(NotificationCopy.ads(7, seed).contains("%d"));
                    if (language.equals("en")) {
                        assertFalse(NotificationCopy.collecting(seed).matches(".*[\\p{IsHan}].*"));
                        assertFalse(NotificationCopy.idle(seed).matches(".*[\\p{IsHan}].*"));
                        assertEquals("Your confirmation is needed", NotificationCopy.title(true, true, "waiting_input", seed));
                    }
                }
            }
        } finally {
            NativeLocale.mirrorProcessLanguage("zh");
        }
    }
}
