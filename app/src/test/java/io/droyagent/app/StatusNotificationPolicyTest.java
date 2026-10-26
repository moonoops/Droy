package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StatusNotificationPolicyTest {
    private static final StatusNotificationPolicy.Audio NONE = StatusNotificationPolicy.Audio.NONE;

    @Test public void idleHasNoFillerAndWakeUsesOneShortLine() {
        assertEquals("", detail(false, NONE, false, false, false, false));
        assertEquals("可以直接叫我", detail(false, StatusNotificationPolicy.Audio.WAKE, false, false, false, false));
        assertEquals("通知整理需要设置", detail(false, NONE, false, false, true, false));
    }

    @Test public void activeTaskKeepsItsStatusWhenCaptureOrSetupAlsoNeedsAttention() {
        assertEquals(NotificationCopy.title(true, true, "waiting_input", 0),
                StatusNotificationPolicy.title(true, true, "waiting_input", StatusNotificationPolicy.Audio.RECORDING, true, 0));
        String text = StatusNotificationPolicy.detail(true, "executing", "正在整理日程", "progress",
                StatusNotificationPolicy.Audio.RECORDING, true, true, true, false);
        assertTrue(text.startsWith("正在整理日程"));
        assertTrue(text.contains("录音中")); assertTrue(text.contains("摄像头使用中")); assertTrue(text.contains("位置记录中"));
        assertFalse(text.contains("需要设置"));
    }

    @Test public void recordingAndLocationRemainVisibleWithoutTheRuntime() {
        assertEquals("Droy · 正在录音", StatusNotificationPolicy.title(true, false, "", StatusNotificationPolicy.Audio.RECORDING, false, 0));
        assertEquals("Droy · 正在记录位置", StatusNotificationPolicy.title(true, false, "", NONE, true, 0));
        assertEquals("Droy · 正在和你交谈", StatusNotificationPolicy.title(true, false, "", StatusNotificationPolicy.Audio.VOICE, true, 0));
        assertTrue(detail(false, NONE, false, true, false, false).contains("位置记录中"));
    }

    @Test public void taskSummaryIsBoundedByCodepointsAndSwitchesLanguageWithoutChangingUserText() {
        String original = "😀".repeat(110);
        String result = StatusNotificationPolicy.detail(true, "executing", original, "progress", NONE, false, false, false, false);
        assertEquals(101, result.codePointCount(0, result.length()));
        try {
            NativeLocale.mirrorProcessLanguage("en");
            assertEquals("You can call me", detail(false, StatusNotificationPolicy.Audio.WAKE, false, false, false, false));
            assertEquals("Notification management needs setup", detail(false, NONE, false, false, true, false));
            assertEquals("原始任务内容", StatusNotificationPolicy.detail(true, "executing", "原始任务内容", "progress", NONE, false, false, false, false));
        } finally { NativeLocale.mirrorProcessLanguage("zh"); }
    }

    @Test public void stopActionsFollowTheCurrentLanguageWhileTheOwnerStaysTheSame() {
        try {
            NativeLocale.mirrorProcessLanguage("zh");
            assertEquals("关闭监听", StatusNotificationPolicy.stopLabel(StatusNotificationPolicy.Audio.WAKE));
            NativeLocale.mirrorProcessLanguage("en");
            assertEquals("Stop listening", StatusNotificationPolicy.stopLabel(StatusNotificationPolicy.Audio.WAKE));
            assertEquals("Stop capture", StatusNotificationPolicy.stopLabel(StatusNotificationPolicy.Audio.PLAYBACK));
            NativeLocale.mirrorProcessLanguage("zh");
            assertEquals("停止录音", StatusNotificationPolicy.stopLabel(StatusNotificationPolicy.Audio.RECORDING));
        } finally { NativeLocale.mirrorProcessLanguage("zh"); }
    }

    private static String detail(boolean active, StatusNotificationPolicy.Audio audio, boolean camera, boolean location, boolean attention, boolean paused) {
        return StatusNotificationPolicy.detail(active, "executing", "", "", audio, camera, location, attention, paused);
    }
}
