package io.droyagent.app;

/** Status titles stay stable; conversational notification summaries retain their event wording. */
final class NotificationCopy {
    private NotificationCopy() {}

    static String title(boolean ready, boolean active, String phase, int seed) {
        if (!ready) return NativeLocale.text("正在启动", "Starting");
        if (!active) return NativeLocale.text("后台运行中", "Running in background");
        return switch (phase) {
            case "waiting_input" -> NativeLocale.text("需要你的确认", "Your confirmation is needed");
            case "waiting" -> NativeLocale.text("等待条件满足", "Waiting to continue");
            case "blocked" -> NativeLocale.text("任务需要处理", "Task needs attention");
            case "pausing" -> NativeLocale.text("正在暂停任务", "Pausing your task");
            case "paused" -> NativeLocale.text("任务已暂停", "Task paused");
            case "resuming" -> NativeLocale.text("正在继续任务", "Resuming your task");
            case "stopping" -> NativeLocale.text("正在停止任务", "Stopping your task");
            default -> NativeLocale.text("正在处理任务", "Working on your task");
        };
    }

    static String important(String app, int seed) {
        return String.format(java.util.Locale.ROOT, choose(seed,
                NativeLocale.text("%s 有条重要通知，记得看一眼。", "%s has an important notification. Take a look."),
                NativeLocale.text("帮你留住了 %s 的重要通知，抽空看看吧。", "Kept an important notification from %s for you. Have a look when you can."),
                NativeLocale.text("%s 有件事值得你留意，通知已经留好了。", "%s has something worth your attention. The notification is still there.")), app);
    }

    static String reminder(String app, int seed) {
        return String.format(java.util.Locale.ROOT, choose(seed,
                NativeLocale.text("%s 的重要通知还在，方便的话现在看看吧。", "That important notification from %s is still there. Take a look when you can."),
                NativeLocale.text("轻轻提醒一下：%s 还有条重要通知等你查看。", "A gentle reminder: %s still has an important notification for you."),
                NativeLocale.text("%s 的重要通知已经留了一会儿，别忘了看一眼。", "An important notification from %s has been waiting a while. Remember to take a look.")), app);
    }

    static String ads(int count, int seed) {
        return String.format(java.util.Locale.ROOT, choose(seed,
                NativeLocale.text("最近收到 %d 条广告，Droy 都替你清理啦。", "Droy cleared %d recent ads for you."),
                NativeLocale.text("刚帮你清走了 %d 条广告，通知栏清爽一点啦。", "Cleared %d recent ads. Your notification shade is a little tidier."),
                NativeLocale.text("%d 条广告已清理，有用的通知给你留着。", "Cleared %d ads and kept useful notifications for you.")), count);
    }

    static String collecting(int seed) {
        return choose(seed,
                NativeLocale.text("正在帮你留意通知，有用的留下，广告清走。", "Watching your notifications: keeping useful ones and clearing ads."),
                NativeLocale.text("通知收纳已开启，Droy 帮你筛掉广告。", "Notification collection is on. Droy is watching for ads."),
                NativeLocale.text("新通知交给我，有用的会替你留好。", "Leave new notifications to me. I will keep the useful ones."));
    }

    static String idle(int seed) {
        return choose(seed,
                NativeLocale.text("暂时没有任务，叫我一声就来。", "No task right now. I am here when you need me."),
                NativeLocale.text("这会儿没什么要忙的，随时等你安排。", "Nothing to work on right now. Ready when you are."),
                NativeLocale.text("暂时闲着，下一件事想让我做什么？", "Taking a break. What would you like me to do next?"));
    }

    private static String choose(int seed, String... variants) {
        return variants[Math.floorMod(seed, variants.length)];
    }
}
