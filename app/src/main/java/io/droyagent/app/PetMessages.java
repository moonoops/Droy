package io.droyagent.app;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/** Picks companion chatter when a new bubble is intentionally shown, not on every UI refresh. */
final class PetMessages {
    private static String[] workingDefaults() { return new String[] {
        NativeLocale.text("%s正在处理。", "%s is working on it."),
        NativeLocale.text("%s还在忙。", "%s is still busy."),
        NativeLocale.text("%s正专心处理。", "%s is focusing on it."),
        NativeLocale.text("%s正在推进这件事。", "%s is making progress on this."),
        NativeLocale.text("%s还在处理这件事。", "%s is still working on this."),
        NativeLocale.text("%s正忙，稍等。", "%s is busy. One moment.")
    }; }
    private static String[] idleDefaults() { return new String[] {
        NativeLocale.text("%s在，有事叫我。", "%s is here if you need anything."),
        NativeLocale.text("%s耳朵竖好了。", "%s has ears ready."),
        NativeLocale.text("%s在，随时开工。", "%s is here and ready to work."),
        NativeLocale.text("%s等你安排。", "%s is waiting for your next task."),
        NativeLocale.text("%s在，今天忙点什么？", "%s is here. What shall we do today?"),
        NativeLocale.text("%s待命，尾巴也就位。", "%s is standing by, tail and all.")
    }; }
    private final IntUnaryOperator random;
    private int lastWorking = -1, lastIdle = -1;
    private final java.util.Map<String, Integer> lastActivity = new java.util.HashMap<>();
    private PetPersona persona = PetPersona.DEFAULT;
    private String language = NativeLocale.active();

    void usePersona(PetPersona value) { if (persona != value) { persona = value; lastWorking = lastIdle = -1; lastActivity.clear(); } }

    PetMessages() { this(bound -> ThreadLocalRandom.current().nextInt(bound)); }
    PetMessages(IntUnaryOperator random) { this.random = random; }

    private void syncLanguage() {
        String current = NativeLocale.active();
        if (!current.equals(language)) { language = current; lastWorking = lastIdle = -1; lastActivity.clear(); }
    }

    String activity(String kind, String name) {
        syncLanguage();
        String[] lines = switch (kind) {
            case "started" -> new String[] {
                NativeLocale.text("%s开始工作啦。", "%s is getting started."),
                NativeLocale.text("%s收到，这就开始。", "%s is on it. Let's begin."),
                NativeLocale.text("%s准备好啦，开工。", "%s is ready. Time to get to work.")
            };
            case "thinking" -> new String[] {
                NativeLocale.text("%s正在思考中，再给我一点时间。", "%s is thinking it through. One moment."),
                NativeLocale.text("%s还在琢磨下一步。", "%s is considering the next step."),
                NativeLocale.text("%s正认真想一想，稍等一下。", "%s is taking a moment to think carefully.")
            };
            case "observing" -> new String[] {
                NativeLocale.text("%s正在仔细看屏幕画面。", "%s is taking a close look at the screen."),
                NativeLocale.text("%s看一眼手机当前的页面。", "%s is checking the current phone screen."),
                NativeLocale.text("%s正在查看屏幕上的内容。", "%s is looking at what's on the screen.")
            };
            case "controlling" -> new String[] {
                NativeLocale.text("%s需要控制一下你的手机。", "%s needs to use your phone for a moment."),
                NativeLocale.text("%s来操作一下手机，稍等。", "%s is operating the phone. One moment."),
                NativeLocale.text("%s正在手机上完成这一步。", "%s is carrying out this step on your phone.")
            };
            default -> null;
        };
        if (lines == null) return working(name);
        int selected = next(lines.length, lastActivity.getOrDefault(kind, -1));
        lastActivity.put(kind, selected);
        return render(lines[selected], name);
    }

    String working(String name) {
        syncLanguage();
        String[] lines = persona.working == null || !persona.matchesLanguage() ? workingDefaults() : persona.working;
        lastWorking = next(lines.length, lastWorking);
        return render(lines[lastWorking], name);
    }

    String idle(String name) {
        syncLanguage();
        String[] lines = persona.idle == null || !persona.matchesLanguage() ? idleDefaults() : persona.idle;
        lastIdle = next(lines.length, lastIdle);
        return render(lines[lastIdle], name);
    }

    private String render(String text, String name) { return text.replace("%s", name).replace("{name}", name); }

    private int next(int count, int last) {
        int selected = random.applyAsInt(last < 0 ? count : count - 1);
        return last >= 0 && selected >= last ? selected + 1 : selected;
    }
}
