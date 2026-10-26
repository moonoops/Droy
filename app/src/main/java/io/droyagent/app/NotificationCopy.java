package io.droyagent.app;

/** A seed belongs to an event, so routine refreshes do not keep changing its wording. */
final class NotificationCopy {
    private NotificationCopy() {}

    static String title(boolean ready, boolean active, String phase, int seed) {
        if (!ready) return choose(seed,
                NativeLocale.text("Droy · 正在启动", "Droy · Starting"),
                NativeLocale.text("Droy · 马上就来", "Droy · Getting ready"),
                NativeLocale.text("Droy · 正在做准备", "Droy · Preparing to help"));
        if (!active) return choose(seed,
                NativeLocale.text("Droy · 待命", "Droy · Ready"),
                NativeLocale.text("Droy · 正在无所事事", "Droy · Taking a little break"),
                NativeLocale.text("Droy · 随时听你安排", "Droy · Ready when you are"));
        return switch (phase) {
            case "waiting_input" -> choose(seed,
                    NativeLocale.text("Droy · 需要你的回应", "Droy · Your response is needed"),
                    NativeLocale.text("Droy · 等你拿个主意", "Droy · Waiting for your decision"),
                    NativeLocale.text("Droy · 有件事想问你", "Droy · A question for you"));
            case "waiting" -> choose(seed,
                    NativeLocale.text("Droy · 等待中", "Droy · Waiting"),
                    NativeLocale.text("Droy · 正在等候时机", "Droy · Waiting for the right moment"),
                    NativeLocale.text("Droy · 等条件满足就继续", "Droy · Waiting to continue"));
            case "blocked" -> choose(seed,
                    NativeLocale.text("Droy · 任务需要处理", "Droy · Task needs attention"),
                    NativeLocale.text("Droy · 遇到一点阻碍", "Droy · Something is in the way"),
                    NativeLocale.text("Droy · 需要你搭把手", "Droy · Could use your help"));
            default -> choose(seed,
                    NativeLocale.text("Droy · 正在处理任务", "Droy · Working on your task"),
                    NativeLocale.text("Droy · 正在认真忙碌", "Droy · Busy helping you"),
                    NativeLocale.text("Droy · 正在替你办事", "Droy · Taking care of your task"));
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
