package io.droyagent.androiduse;

import android.graphics.Rect;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

/** Connects the product's own overlay to Android input and capture boundaries. */
public final class OverlayInteraction {
    public interface Listener {
        /** Called on the main thread; complete only after the overlay has cleared the region. */
        CompletableFuture<Void> prepare(Rect screenArea, boolean capture);
        /** Called on the main thread, including cancellation and failed operations. */
        void finish();
        default void onKeyboardBounds(Rect bounds) {}
        default void onAccessibilityConnectionChanged() {}
    }

    private static final Set<Listener> listeners = new LinkedHashSet<>();
    private static final Set<Integer> windows = new HashSet<>();
    private static final LinkedHashSet<Integer> retiredWindows = new LinkedHashSet<>();

    private OverlayInteraction() {}

    public static synchronized void addListener(Listener value) { listeners.add(Objects.requireNonNull(value)); }
    public static synchronized void removeListener(Listener value) { listeners.remove(value); }
    private static synchronized List<Listener> currentListeners() { return List.copyOf(listeners); }

    public static synchronized void registerWindow(int windowId) {
        if (windowId < 0) return;
        windows.add(windowId);
        retiredWindows.remove(windowId);
    }

    public static synchronized void unregisterWindow(int windowId) {
        if (!windows.remove(windowId)) return;
        // Removal events can arrive after removeViewImmediate has detached the view.
        retiredWindows.add(windowId);
        while (retiredWindows.size() > 16) retiredWindows.remove(retiredWindows.iterator().next());
    }

    static synchronized boolean ownsWindow(int windowId) {
        return windowId >= 0 && (windows.contains(windowId) || retiredWindows.contains(windowId));
    }

    static Preparation prepare(Rect screenArea, boolean capture) {
        List<Listener> owners = currentListeners();
        List<Listener> prepared = new ArrayList<>();
        List<CompletableFuture<Void>> frames = new ArrayList<>();
        for (Listener owner : owners) {
            prepared.add(owner);
            try { frames.add(Objects.requireNonNull(owner.prepare(screenArea, capture))); }
            catch (RuntimeException failure) {
                CompletableFuture<Void> failed = new CompletableFuture<>(); failed.completeExceptionally(failure);
                frames.add(failed);
                break;
            }
        }
        return new Preparation(List.copyOf(prepared), CompletableFuture.allOf(frames.toArray(new CompletableFuture<?>[0])));
    }

    static void keyboardBounds(Rect bounds) {
        for (Listener current : currentListeners()) current.onKeyboardBounds(new Rect(bounds));
    }

    static void accessibilityConnectionChanged() {
        for (Listener current : currentListeners()) current.onAccessibilityConnectionChanged();
    }

    static final class Preparation {
        final CompletableFuture<Void> ready;
        private final List<Listener> owners;
        private boolean finished;

        Preparation(List<Listener> owners, CompletableFuture<Void> ready) {
            this.owners = owners;
            this.ready = ready;
        }

        void finish() {
            if (finished) return;
            finished = true;
            RuntimeException failure = null;
            for (Listener owner : owners) {
                try { owner.finish(); }
                catch (RuntimeException error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
