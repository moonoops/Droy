package io.droyagent.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;
import java.util.function.Predicate;

/** Deliberate play actions are separate from task progress and random idle variants. */
final class PetInteractionActions {
    private static final String[] IDS = {"tapTilt", "tapWave", "pet", "petShy", "idleTail", "idleStretch",
        "knead", "tailCurl", "sleep", "waveHands", "scream", "startle", "neckStretch", "spitForward", "spitScreen"};
    private static final Set<String> SIGNATURE_IDS = Set.of("knead", "tailCurl", "sleep", "waveHands", "scream",
        "startle", "neckStretch", "spitForward", "spitScreen");

    static final class IdleSelector {
        private final IntUnaryOperator random;
        private String previous;

        IdleSelector() { this(bound -> ThreadLocalRandom.current().nextInt(bound)); }
        IdleSelector(IntUnaryOperator random) { this.random = random; }

        String next(Predicate<String> exists) {
            List<String> choices = available(exists);
            if (choices.isEmpty()) return null;
            if (choices.size() > 1) choices.remove(previous);
            List<String> signature = new ArrayList<>(), other = new ArrayList<>();
            for (String id : choices) (SIGNATURE_IDS.contains(id) ? signature : other).add(id);
            List<String> pool = random.applyAsInt(100) < 70 ? signature : other;
            // A missing pool, including its only action being the previous one, must not force a repeat.
            if (pool.isEmpty()) pool = signature.isEmpty() ? other : signature;
            previous = pool.get(random.applyAsInt(pool.size()));
            return previous;
        }
    }

    static List<String> available(Predicate<String> exists) {
        List<String> result = new ArrayList<>();
        for (String id : IDS) if (exists.test(id)) result.add(id);
        return result;
    }

    static String fallbackLabel(String id) {
        return switch (id) {
            case "tapTilt" -> NativeLocale.text("好奇歪头", "Curious tilt");
            case "tapWave" -> NativeLocale.text("抬爪招呼", "Greeting wave");
            case "pet" -> NativeLocale.text("享受抚摸", "Enjoy a pet");
            case "petShy" -> NativeLocale.text("害羞缩身", "Shy response");
            case "idleTail" -> NativeLocale.text("轻摆尾巴", "Tail sway");
            case "idleStretch" -> NativeLocale.text("伸个懒腰", "Relaxed stretch");
            case "knead" -> NativeLocale.text("交替踩奶", "Kneading");
            case "tailCurl" -> NativeLocale.text("尾巴绕身", "Tail curl");
            case "sleep" -> NativeLocale.text("蜷身打盹", "Curled nap");
            case "waveHands" -> NativeLocale.text("挥动短手", "Tiny hand wave");
            case "scream" -> NativeLocale.text("抬头“啊”", "Meme scream");
            case "startle" -> NativeLocale.text("受惊缩回", "Startled recoil");
            case "neckStretch" -> NativeLocale.text("伸长脖子", "Neck stretch");
            case "spitForward" -> NativeLocale.text("朝前吐口水", "Sideways spit");
            case "spitScreen" -> NativeLocale.text("朝屏幕吐口水", "Spit toward viewer");
            default -> throw new IllegalArgumentException("unknown_pet_interaction");
        };
    }

    static String restingState(String phase, boolean pending, Predicate<String> exists) {
        if (pending) return "waiting";
        return switch (phase) {
            case "waiting", "pausing", "paused", "stopping" -> "waiting";
            case "completed" -> exists.test("completed") ? "completed" : "idle";
            case "blocked" -> exists.test("blocked") ? "blocked" : "waiting";
            case "interrupted" -> exists.test("stopped") ? "stopped" : "idle";
            case "executing", "verifying", "thinking", "resuming" -> "working";
            default -> "idle";
        };
    }

    static long duration(long bodyMs, long effectEndMs, boolean animationsEnabled) {
        return animationsEnabled ? Math.max(bodyMs, effectEndMs) : 0;
    }

    static boolean interrupted(boolean activeAtStart, String taskAtStart, String currentTask, String phase, boolean pending) {
        return pending || activeAtStart && (!taskAtStart.equals(currentTask) || "interrupted".equals(phase) || "blocked".equals(phase)
            || "pausing".equals(phase) || "paused".equals(phase) || "resuming".equals(phase) || "stopping".equals(phase));
    }
}
