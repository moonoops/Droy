package io.droyagent.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

final class LauncherIconRotation {
    static final String PREFERENCES = "launcher-icons";
    static final String[] ALIAS_NAMES = {
        "io.droyagent.app.launcher.Closeup", "io.droyagent.app.launcher.Sunglasses",
        "io.droyagent.app.launcher.Headphones", "io.droyagent.app.launcher.Programmer",
        "io.droyagent.app.launcher.HatScarf", "io.droyagent.app.launcher.SleepCurled",
        "io.droyagent.app.launcher.SleepSide", "io.droyagent.app.launcher.SleepBellyUp",
        "io.droyagent.app.launcher.SleepSlumped"
    };
    private static final Object LOCK = new Object();

    private LauncherIconRotation() {}

    static void onLauncherOpened(Context context) {
        if (!BuildConfig.DROY_RANDOM_LAUNCHER_ICONS) return;
        synchronized (LOCK) {
            try {
                maybeSwitch(new AndroidComponents(context),
                    new PreferenceStore(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)),
                    System.currentTimeMillis(), bound -> ThreadLocalRandom.current().nextInt(bound));
            } catch (RuntimeException failure) {
                // An optional launcher update must not interrupt opening the actual app.
                Log.e("DroyLauncherIcon", "Could not update the launcher icon", failure);
            }
        }
    }

    interface Components {
        int enabledMask();
        void select(int index);
    }

    interface Store {
        long lastSwitchMillis();
        Pending pending();
        void begin(Pending pending);
        void complete(long when);
        void restore(long lastSwitch);
    }

    static final class Pending {
        final int previous, next;
        final long when;
        Pending(int previous, int next, long when) {
            this.previous = previous;
            this.next = next;
            this.when = when;
        }
    }

    static boolean maybeSwitch(Components components, Store store, long now, IntUnaryOperator random) {
        long lastSwitch = store.lastSwitchMillis();
        int current = uniqueIcon(components.enabledMask());
        Pending pending = store.pending();
        if (pending != null) {
            // Recover a process death between PackageManager changing the alias and the final commit.
            if (current == pending.next) {
                store.complete(pending.when);
                lastSwitch = pending.when;
            } else {
                components.select(pending.previous);
                store.restore(lastSwitch);
                current = pending.previous;
            }
        }
        if (current < 0) {
            components.select(0);
            current = 0;
        }
        int next = LauncherIconPolicy.nextIcon(current, now, lastSwitch, random);
        if (next == current) return false;
        store.begin(new Pending(current, next, now));
        try {
            components.select(next);
            store.complete(now);
        } catch (RuntimeException failure) {
            try {
                components.select(current);
                store.restore(lastSwitch);
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        return true;
    }

    static int uniqueIcon(int mask) {
        return Integer.bitCount(mask) == 1 ? Integer.numberOfTrailingZeros(mask) : -1;
    }

    static final class PreferenceStore implements Store {
        private final SharedPreferences preferences;
        PreferenceStore(SharedPreferences preferences) { this.preferences = preferences; }
        @Override public long lastSwitchMillis() { return preferences.getLong("last-switch-ms", -1L); }
        @Override public Pending pending() {
            int next = preferences.getInt("pending-next", -1);
            return next < 0 ? null : new Pending(preferences.getInt("pending-previous", 0), next,
                preferences.getLong("pending-at-ms", 0L));
        }
        @Override public void begin(Pending pending) {
            requireCommit(preferences.edit().putInt("pending-previous", pending.previous)
                .putInt("pending-next", pending.next).putLong("pending-at-ms", pending.when));
        }
        @Override public void complete(long when) {
            requireCommit(clearPending(preferences.edit().putLong("last-switch-ms", when)));
        }
        @Override public void restore(long lastSwitch) {
            SharedPreferences.Editor editor = preferences.edit();
            if (lastSwitch < 0) editor.remove("last-switch-ms");
            else editor.putLong("last-switch-ms", lastSwitch);
            requireCommit(clearPending(editor));
        }
        private static SharedPreferences.Editor clearPending(SharedPreferences.Editor editor) {
            return editor.remove("pending-previous").remove("pending-next").remove("pending-at-ms");
        }
        private static void requireCommit(SharedPreferences.Editor editor) {
            if (!editor.commit()) throw new IllegalStateException("Could not persist launcher icon state");
        }
    }

    static final class AndroidComponents implements Components {
        private final PackageManager packages;
        private final ComponentName[] names = new ComponentName[ALIAS_NAMES.length];
        AndroidComponents(Context context) {
            packages = context.getPackageManager();
            for (int i = 0; i < names.length; i++) names[i] = new ComponentName(context.getPackageName(), ALIAS_NAMES[i]);
        }
        ComponentName name(int index) { return names[index]; }
        int[] states() {
            int[] states = new int[names.length];
            for (int i = 0; i < names.length; i++) states[i] = packages.getComponentEnabledSetting(names[i]);
            return states;
        }
        @Override public int enabledMask() {
            int[] states = states();
            int mask = 0;
            for (int i = 0; i < states.length; i++) if (enabled(i, states[i])) mask |= 1 << i;
            return mask;
        }
        @Override public void select(int index) {
            int[] states = new int[names.length];
            for (int i = 0; i < states.length; i++) states[i] = i == index
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            applyStates(states);
        }
        void applyStates(int[] states) {
            if (Build.VERSION.SDK_INT >= 33) {
                List<PackageManager.ComponentEnabledSetting> changes = new ArrayList<>();
                for (int i = 0; i < names.length; i++) changes.add(new PackageManager.ComponentEnabledSetting(
                    names[i], states[i], PackageManager.DONT_KILL_APP));
                packages.setComponentEnabledSettings(changes);
            } else {
                // Android 11–12 has no atomic API: enable the replacement before disabling old aliases.
                for (int i = 0; i < names.length; i++) if (enabled(i, states[i]))
                    packages.setComponentEnabledSetting(names[i], states[i], PackageManager.DONT_KILL_APP);
                for (int i = 0; i < names.length; i++) if (!enabled(i, states[i]))
                    packages.setComponentEnabledSetting(names[i], states[i], PackageManager.DONT_KILL_APP);
            }
        }
        private static boolean enabled(int index, int state) {
            return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && index == 0;
        }
    }
}
