package io.droyagent.androiduse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

public final class DroyAccessibilityService extends AccessibilityService {
    public interface EventObserver {
        void onConnectionChanged(boolean connected);
        void onEvent(AccessibilityEvent event, DroyAccessibilityService service);
    }
    private static volatile EventObserver observer;
    /** The application may observe live events; this never grants capture consent. */
    public static void setEventObserver(EventObserver value) {
        observer = value;
        if (value != null) value.onConnectionChanged(connected != null);
    }
    private static volatile DroyAccessibilityService connected;
    private static final AtomicLong generation = new AtomicLong();
    private static final AtomicLong contentGeneration = new AtomicLong();
    private static final AtomicLong hardGeneration = new AtomicLong();
    private static volatile String lastHardChange = "service_not_connected";
    private boolean identityInitialized;
    private String activePackage;
    private int activeWindowId = -1;
    private final Rect keyboardBounds = new Rect();
    private List<WindowIdentity> contentWindows = List.of();
    private Map<Integer, Integer> windowLayers = Map.of();
    private Set<Integer> overlayWindows = Set.of();
    private final Map<Integer, Integer> overlayLayerShifts = new HashMap<>();

    @Override protected void onServiceConnected() {
        connected = this;
        OverlayInteraction.accessibilityConnectionChanged();
        identityInitialized = false;
        invalidateSnapshots();
        lastHardChange = "service_connected";
        AccessibilityRecovery.get(this).serviceConnected();
        EventObserver current = observer; if (current != null) current.onConnectionChanged(true);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        boolean overlayLayerShift = false;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            updateKeyboardBounds();
            overlayLayerShift = updateWindowLayers(event);
        }
        if (OverlayInteraction.ownsWindow(event.getWindowId()) || overlayLayerShift) {
            return;
        }
        // A delayed event or unrelated window property does not establish that
        // the active window changed. Read the owning identity before revoking
        // node bases; coordinates still track every listed UI event below.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            AccessibilityNodeInfo root = getRootInActiveContentWindow();
            try { syncActiveWindow(root); }
            finally { if (root != null) root.recycle(); }
        }
        recordUiChange(type, event.getWindowId());
        EventObserver current = observer; if (current != null) current.onEvent(event, this);
    }

    private void recordUiChange(int type, int windowId) {
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SELECTED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            generation.incrementAndGet();
            // Status-bar clocks and network indicators do not move an app's
            // interior target. Full-frame screenshots still use generation.
            if (!isSystemBarContentChange(type, windowId)) contentGeneration.incrementAndGet();
        }
    }

    private boolean isSystemBarContentChange(int type, int windowId) {
        if (type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return false;
        WindowManager manager = getSystemService(WindowManager.class);
        Rect display = manager.getMaximumWindowMetrics().getBounds();
        Rect content = coordinateContentBounds();
        List<AccessibilityWindowInfo> available = getWindows();
        try {
            for (AccessibilityWindowInfo window : available) {
                if (window.getId() != windowId || window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                return !bounds.isEmpty() && !content.isEmpty() && display.contains(bounds) && !Rect.intersects(content, bounds);
            }
            return false;
        } finally { for (AccessibilityWindowInfo window : available) window.recycle(); }
    }

    Rect coordinateContentBounds() {
        var metrics = getSystemService(WindowManager.class).getMaximumWindowMetrics();
        Rect display = metrics.getBounds();
        Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        return new Rect(display.left + insets.left, display.top + insets.top,
            display.right - insets.right, display.bottom - insets.bottom);
    }

    private record WindowIdentity(int id, int type, Rect bounds, boolean focused, boolean active,
                                  boolean accessibilityFocused, String title) {}

    private boolean updateWindowLayers(AccessibilityEvent event) {
        List<AccessibilityWindowInfo> available = new ArrayList<>(getWindows());
        List<WindowIdentity> content = new ArrayList<>();
        Map<Integer, Integer> layers = new HashMap<>();
        Set<Integer> own = new HashSet<>();
        try {
            available.sort(Comparator.comparingInt(AccessibilityWindowInfo::getLayer));
            for (AccessibilityWindowInfo window : available) {
                int id = window.getId(); layers.put(id, window.getLayer());
                if (OverlayInteraction.ownsWindow(id)) { own.add(id); continue; }
                Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
                content.add(new WindowIdentity(id, window.getType(), bounds, window.isFocused(), window.isActive(),
                    window.isAccessibilityFocused(), window.getTitle() == null ? null : window.getTitle().toString()));
            }
        } finally { for (AccessibilityWindowInfo window : available) window.recycle(); }
        if (!content.equals(contentWindows)) {
            overlayLayerShifts.clear();
        } else if (!own.equals(overlayWindows)) {
            // Android renumbers other windows when an overlay enters/leaves the
            // stack. Only discount those exact changes while every non-pet
            // window keeps its identity, geometry, focus and relative order.
            for (WindowIdentity window : content) {
                Integer before = windowLayers.get(window.id()), after = layers.get(window.id());
                if (before != null && !before.equals(after)) overlayLayerShifts.put(window.id(), after);
            }
        } else {
            // A subsequent layer move without an overlay transition has its own
            // cause; it cannot consume a pending pet-only renumbering receipt.
            for (WindowIdentity window : content) {
                if (!Objects.equals(windowLayers.get(window.id()), layers.get(window.id())))
                    overlayLayerShifts.remove(window.id());
            }
        }
        contentWindows = content; windowLayers = layers; overlayWindows = own;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || event.getWindowChanges() != AccessibilityEvent.WINDOWS_CHANGE_LAYER) return false;
        Integer shifted = overlayLayerShifts.remove(event.getWindowId());
        return shifted != null && shifted.equals(layers.get(event.getWindowId()));
    }

    @Override public void onInterrupt() {
        invalidateSnapshots();
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    private void disconnect() {
        if (connected == this) {
            connected = null;
            OverlayInteraction.accessibilityConnectionChanged();
            EventObserver current = observer; if (current != null) current.onConnectionChanged(false);
        }
        invalidateSnapshots();
    }

    static long generation() {
        return generation.get();
    }

    static long contentGeneration() { return contentGeneration.get(); }

    static long hardGeneration() {
        return hardGeneration.get();
    }

    static String lastHardChange() { return lastHardChange; }

    /** A non-focusable pet can become the accessibility-active window after a touch. */
    AccessibilityNodeInfo getRootInActiveContentWindow() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !OverlayInteraction.ownsWindow(root.getWindowId())) return root;
        root.recycle();
        List<AccessibilityWindowInfo> available = getWindows();
        try {
            for (AccessibilityWindowInfo window : available) {
                if (!OverlayInteraction.ownsWindow(window.getId())
                        && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused())
                    return window.getRoot();
            }
            return null;
        } finally { for (AccessibilityWindowInfo window : available) window.recycle(); }
    }

    private void updateKeyboardBounds() {
        Rect bounds = new Rect();
        List<AccessibilityWindowInfo> available = getWindows();
        try {
            for (AccessibilityWindowInfo window : available) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    window.getBoundsInScreen(bounds);
                    break;
                }
            }
        } finally { for (AccessibilityWindowInfo window : available) window.recycle(); }
        if (!keyboardBounds.equals(bounds)) {
            keyboardBounds.set(bounds);
            OverlayInteraction.keyboardBounds(bounds);
        }
    }

    void syncActiveWindow(AccessibilityNodeInfo root) {
        String packageName = root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
        int windowId = root == null ? -1 : root.getWindowId();
        if (identityInitialized && (windowId != activeWindowId || !Objects.equals(packageName, activePackage))) {
            lastHardChange = root == null ? "active_window_unavailable" : "active_window_changed";
            hardGeneration.incrementAndGet();
        }
        activePackage = packageName;
        activeWindowId = windowId;
        identityInitialized = true;
    }

    static void invalidateSnapshots() {
        lastHardChange = "basis_consumed_or_replaced";
        generation.incrementAndGet();
        contentGeneration.incrementAndGet();
        hardGeneration.incrementAndGet();
    }

    /** Reports the same live service availability required by Android Use actions. */
    public static boolean isConnected(Context context) {
        return current(context) != null;
    }

    /** Trusted window context for the product's non-interactive execution indicator. */
    public static Context overlayWindowContext(Context context) { return current(context); }

    static DroyAccessibilityService current(Context context) {
        DroyAccessibilityService service = connected;
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (service == null || manager == null || !manager.isEnabled()) return null;
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)
                    && DroyAccessibilityService.class.getName().equals(info.getResolveInfo().serviceInfo.name)) {
                return service == connected ? service : null;
            }
        }
        return null;
    }
}
