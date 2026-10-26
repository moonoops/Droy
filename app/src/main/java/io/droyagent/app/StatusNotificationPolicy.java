package io.droyagent.app;

/** Presentation priority is independent of which foreground service started last. */
final class StatusNotificationPolicy {
    enum Audio { NONE, WAKE, VOICE, ENROLLMENT, RECORDING, PLAYBACK }

    static String title(boolean ready, boolean active, String phase, Audio audio, boolean location, int seed) {
        if (active || !ready) return NotificationCopy.title(ready, active, phase, seed);
        return switch (audio) {
            case VOICE -> NativeLocale.text("Droy · 正在和你交谈", "Droy · Talking with you");
            case ENROLLMENT -> NativeLocale.text("Droy · 正在录入声纹", "Droy · Enrolling your voice");
            case RECORDING -> NativeLocale.text("Droy · 正在录音", "Droy · Recording audio");
            case PLAYBACK -> NativeLocale.text("Droy · 正在采集应用音频", "Droy · Capturing app audio");
            default -> location ? NativeLocale.text("Droy · 正在记录位置", "Droy · Recording location")
                    : NotificationCopy.title(true, false, phase, seed);
        };
    }

    static String detail(boolean active, String phase, String summary, String summaryKind, Audio audio,
                         boolean camera, boolean location, boolean attention, boolean paused) {
        String detail;
        if (active) {
            detail = !summary.isBlank() && !summaryKind.equals("working") ? summary : RuntimeService.executionPhaseLabel(phase);
            if (detail.isBlank()) detail = NativeLocale.text("正在处理你的任务", "Working on your task");
            detail = bounded(detail, 100);
        } else {
            detail = switch (audio) {
                case WAKE -> NativeLocale.text("可以直接叫我", "You can call me");
                case VOICE -> NativeLocale.text("点按返回对话", "Tap to return to the conversation");
                case ENROLLMENT -> NativeLocale.text("请按屏幕提示朗读", "Read the text shown on screen");
                case RECORDING, PLAYBACK -> NativeLocale.text("可随时停止", "You can stop at any time");
                default -> "";
            };
        }
        if (active && audio == Audio.RECORDING) detail = append(detail, NativeLocale.text("录音中", "Recording audio"));
        if (active && audio == Audio.PLAYBACK) detail = append(detail, NativeLocale.text("采集应用音频中", "Capturing app audio"));
        if (active && audio == Audio.VOICE) detail = append(detail, NativeLocale.text("语音对话中", "Voice conversation active"));
        if (camera) detail = append(detail, NativeLocale.text("摄像头使用中", "Camera active"));
        if (location) detail = append(detail, NativeLocale.text("位置记录中", "Recording location"));
        if (paused) detail = append(detail, NativeLocale.text("手机操作已暂停", "Phone operations paused"));
        if (!active && attention) detail = append(detail, NativeLocale.text("通知整理需要设置", "Notification management needs setup"));
        return detail;
    }

    static String stopLabel(Audio audio) {
        return switch (audio) {
            case WAKE -> NativeLocale.text("关闭监听", "Stop listening");
            case VOICE -> NativeLocale.text("结束对话", "End conversation");
            case ENROLLMENT -> NativeLocale.text("取消录入", "Cancel enrollment");
            case RECORDING -> NativeLocale.text("停止录音", "Stop recording");
            case PLAYBACK -> NativeLocale.text("停止采集", "Stop capture");
            case NONE -> throw new IllegalArgumentException("No audio owner");
        };
    }

    private static String append(String detail, String addition) { return detail.isEmpty() ? addition : detail + " · " + addition; }
    private static String bounded(String value, int codepoints) {
        return value.codePointCount(0, value.length()) <= codepoints ? value
                : value.substring(0, value.offsetByCodePoints(0, codepoints)) + "…";
    }
}
