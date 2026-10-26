package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.accessibility.AccessibilityWindowInfo;
import android.webkit.WebView;
import android.widget.Button;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Small UI harness shared by device tests; selectors target Droy contracts, never bundled class hashes. */
final class DeviceUi {
    final Instrumentation instrumentation;
    private final int touchDeviceId;
    private final UiAutomation automation;
    MainActivity activity;
    JSONObject beforeBack;

    DeviceUi(Instrumentation instrumentation, MainActivity activity) {
        this.instrumentation = instrumentation;
        this.activity = activity;
        automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo automationInfo = automation.getServiceInfo();
        automationInfo.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(automationInfo);
        int sourceDevice = Integer.MIN_VALUE;
        // Emulator multi-display inputs can advertise touch while disabled;
        // only an input mapped to a display exposes usable coordinate ranges.
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || !device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) continue;
            InputDevice.MotionRange x = device.getMotionRange(MotionEvent.AXIS_X);
            InputDevice.MotionRange y = device.getMotionRange(MotionEvent.AXIS_Y);
            if (x != null && y != null && x.getRange() > 0 && y.getRange() > 0) {
                sourceDevice = id;
                break;
            }
        }
        touchDeviceId = sourceDevice;
    }

    UiAutomation automation() { return automation; }

    WebView web() {
        AtomicReference<WebView> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField("web");
                field.setAccessible(true);
                result.set((WebView) field.get(activity));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        return result.get();
    }

    Object evaluate(String expression) throws Exception {
        WebView view = web();
        assertNotNull("The conversation WebView is unavailable", view);
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> view.evaluateJavascript(expression,
                result -> { value.set(result); done.countDown(); }));
        assertTrue("JavaScript evaluation timed out", done.await(5, TimeUnit.SECONDS));
        return new JSONArray("[" + value.get() + "]").get(0);
    }

    boolean truth(String expression) throws Exception { return Boolean.TRUE.equals(evaluate(expression)); }

    void click(String selector) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        do {
            if (truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                    + ");if(!e||e.disabled||e.closest('[inert]'))return false;e.scrollIntoView({block:'center',inline:'nearest',behavior:'instant'});"
                    + "const r=e.getBoundingClientRect();if(!e.getClientRects().length||r.width<=0||r.height<=0||getComputedStyle(e).visibility==='hidden')return false;"
                    + "e.click();return true})()")) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        Object summary = evaluate("(()=>{const visible=e=>e.getClientRects().length>0;const known=['关于与诊断','继续','稍后配置','深色','浅色','跟随系统','Continue','Configure later','Dark','Light','System'];"
                + "const allowedSections=['general','models','droy-permissions'];const e=document.querySelector(" + JSONObject.quote(selector) + ");const r=e?.getBoundingClientRect();return {"
                + "targetPresent:!!e,targetDisabled:!!e?.disabled,targetInert:!!e?.closest('[inert]'),targetSize:r?{width:r.width,height:r.height}:null,"
                + "modals:[...document.querySelectorAll('[role=dialog],[role=alertdialog]')].filter(visible).map(e=>e.getAttribute('data-shortcut-modal')==='settings'?'settings':e.hasAttribute('data-droy-sidebar')?'sidebar':'other'),"
                + "sections:[...document.querySelectorAll('[data-droy-settings-section]')].map(e=>e.getAttribute('data-droy-settings-section')).filter(s=>allowedSections.includes(s)),"
                + "knownButtons:[...document.querySelectorAll('button')].filter(visible).map(e=>e.textContent.trim()).filter(s=>known.includes(s))}})()");
        fail("UI target did not become actionable: " + selector + "; safe state=" + summary);
    }

    void awaitPage() throws Exception {
        await("Real DSH conversation did not become ready: " + RuntimeService.status, 90000,
                () -> RuntimeService.ready && web() != null
                        && truth("typeof window.DroyMobile?.handleBack === 'function' && document.documentElement.dataset.droyUiReady === 'true'")
                        && nativeUiReady() && !loadingOverlayShown());
        dismissInitialOnboarding();
    }

    boolean nativeUiReady() {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField("uiReady");
                field.setAccessible(true);
                Field webField = MainActivity.class.getDeclaredField("web");
                Field loadedField = MainActivity.class.getDeclaredField("loaded");
                webField.setAccessible(true);
                loadedField.setAccessible(true);
                java.lang.reflect.Method current = MainActivity.class.getDeclaredMethod(
                        "isCurrentWebSession", WebView.class, String.class, int.class);
                current.setAccessible(true);
                // A restarted engine can become ready before the Activity's next
                // refresh replaces its hidden old document. Never drive that page.
                result.set(field.getBoolean(activity) && (Boolean) current.invoke(activity,
                        webField.get(activity), loadedField.get(activity), RuntimeService.webPort));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        return result.get();
    }

    boolean loadingOverlayShown() {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            View overlay = activity.getWindow().getDecorView().findViewWithTag("droy-chat-loading");
            result.set(overlay != null && overlay.isShown() && overlay.getAlpha() > 0);
        });
        return result.get();
    }

    private void dismissInitialOnboarding() throws Exception {
        long[] quietSince = {0};
        await("The initial DSH notice/model onboarding did not finish", 20000, () -> {
            JSONObject state = (JSONObject) evaluate("(()=>{const visible=e=>!!e&&e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden'&&!e.closest('[inert]');"
                    + "const titles=[...document.querySelectorAll('h2')].filter(visible);"
                    + "for(const heading of titles){const title=heading.textContent.trim();let labels;"
                    + "if(['预览版说明','Preview Notice'].includes(title)||title.startsWith('欢迎使用 ')||title.startsWith('Welcome to '))labels=['继续','Continue'];"
                    + "else if(['添加一个 API Key 开始使用','Add an API key to get started'].includes(title))labels=['稍后配置','Configure later'];"
                    + "else continue;const modal=heading.closest('[role=dialog],[role=alertdialog],dialog[open]');"
                    + "if(!modal)continue;const button=[...modal.querySelectorAll('button')].find(e=>visible(e)&&!e.disabled&&labels.includes(e.textContent.trim()));"
                    + "if(button){button.click();return {clicked:true,ready:false}}return {clicked:false,ready:false}}"
                    + "return {clicked:false,ready:!!document.getElementById('root')&&(!document.getElementById('root').inert||!!document.querySelector('[data-shortcut-modal=\"settings\"]'))}})()");
            if (state.getBoolean("clicked") || !state.getBoolean("ready")) { quietSince[0] = 0; return false; }
            if (quietSince[0] == 0) quietSince[0] = SystemClock.elapsedRealtime();
            return SystemClock.elapsedRealtime() - quietSince[0] >= 600;
        });
    }

    void openPermissions() throws Exception {
        awaitPage();
        evaluate("window.DroyMobile.openPermissions()");
        int capabilityCount = new CapabilityCatalog(instrumentation.getTargetContext()).groups().length();
        await("DSH did not open permission settings", 15000,
                () -> truth("document.querySelectorAll('[data-droy-preference] [role=\"switch\"]').length === " + capabilityCount
                    + " && !document.querySelector('[data-droy-preference] [role=\"switch\"]')?.disabled"));
    }

    void hideKeyboard() throws Exception {
        instrumentation.runOnMainSync(() -> {
            android.view.inputmethod.InputMethodManager input = activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
            input.hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0);
            activity.getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        });
        await("The test keyboard did not close", 5000, () -> {
            AtomicReference<Boolean> hidden = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> hidden.set(!activity.getWindow().getDecorView()
                    .getRootWindowInsets().isVisible(WindowInsets.Type.ime())));
            return hidden.get();
        });
    }

    void showKeyboard(String selector) throws Exception {
        // Match normal test touches: an unspecified input source may move focus without opening an IME on a real phone.
        tap(selector);
        await("The input keyboard did not open", 10000, () -> {
            AtomicReference<Boolean> shown = new AtomicReference<>(false);
            instrumentation.runOnMainSync(() -> shown.set(activity.getWindow().getDecorView()
                    .getRootWindowInsets().isVisible(WindowInsets.Type.ime())));
            return shown.get();
        });
    }

    Rect webBounds(String selector) throws Exception {
        JSONObject rect = (JSONObject) evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + ");if(!e)return null;const r=e.getBoundingClientRect(),v=visualViewport,dx=v?.offsetLeft||0,dy=v?.offsetTop||0;return {left:r.left-dx,top:r.top-dy,right:r.right-dx,bottom:r.bottom-dy,width:innerWidth}})()");
        WebView view = web();
        int[] location = new int[2];
        int[] width = new int[1];
        instrumentation.runOnMainSync(() -> { view.getLocationOnScreen(location); width[0] = view.getWidth(); });
        double scale = width[0] / rect.getDouble("width");
        return new Rect(location[0] + (int) (rect.getDouble("left") * scale),
                location[1] + (int) (rect.getDouble("top") * scale),
                location[0] + (int) (rect.getDouble("right") * scale),
                location[1] + (int) (rect.getDouble("bottom") * scale));
    }

    /** Inject through Android's input dispatcher so WebView receives trusted touch events. */
    void tap(String selector) throws Exception {
        Rect bounds = visibleWebBounds(selector);
        touch(bounds.centerX(), bounds.centerY(), 40);
    }

    void longPress(String selector, long holdMs) throws Exception {
        Rect bounds = visibleWebBounds(selector);
        touch(bounds.centerX(), bounds.centerY(), holdMs);
    }

    void tapAt(float x, float y) { touch(x, y, 45); }

    private void touch(float x, float y, long holdMs) {
        long downTime = SystemClock.uptimeMillis();
        injectTouch(downTime, MotionEvent.ACTION_DOWN, x, y);
        try { SystemClock.sleep(holdMs); }
        finally { injectTouch(downTime, MotionEvent.ACTION_UP, x, y); }
    }

    void swipe(float fromX, float fromY, float toX, float toY, long durationMs) {
        assertTrue("A touch swipe needs a positive duration", durationMs > 0);
        long downTime = SystemClock.uptimeMillis();
        injectTouch(downTime, MotionEvent.ACTION_DOWN, fromX, fromY);
        try {
            int steps = Math.max(2, (int) (durationMs / 16));
            for (int step = 1; step <= steps; step++) {
                long until = downTime + durationMs * step / steps;
                SystemClock.sleep(Math.max(0, until - SystemClock.uptimeMillis()));
                float fraction = (float) step / steps;
                injectTouch(downTime, MotionEvent.ACTION_MOVE,
                        fromX + (toX - fromX) * fraction, fromY + (toY - fromY) * fraction);
            }
        } finally { injectTouch(downTime, MotionEvent.ACTION_UP, toX, toY); }
    }

    void pressBack() throws Exception {
        beforeBack = nativeNavigationState();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(automation
                .executeShellCommand("input keyevent KEYCODE_BACK"))) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                assertTrue("The system Back command returned excessive output", output.size() <= 4096);
            }
        }
        String diagnostic = output.toString("UTF-8").trim();
        assertTrue("The system Back command failed: " + diagnostic, diagnostic.isEmpty());
    }

    JSONObject nativeNavigationState() {
        AtomicReference<JSONObject> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                JSONObject state = new JSONObject();
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                state.put("liveIme", insets != null && insets.isVisible(WindowInsets.Type.ime()));
                for (String name : new String[]{"imeVisible", "chatVisible"}) {
                    Field field = MainActivity.class.getDeclaredField(name);field.setAccessible(true);
                    state.put(name, field.getBoolean(activity));
                }
                Field failure = MainActivity.class.getDeclaredField("webFailure");failure.setAccessible(true);
                state.put("webFailure", failure.get(activity) != null);
                state.put("runtimeReady", RuntimeService.ready).put("activityFocus", activity.hasWindowFocus())
                        .put("finishing", activity.isFinishing()).put("destroyed", activity.isDestroyed());
                result.set(state);
            } catch (Exception error) { throw new AssertionError("Could not inspect navigation state", error); }
        });
        return result.get();
    }

    boolean keyboardVisible() {
        AtomicReference<Boolean> result = new AtomicReference<>(false);
        instrumentation.runOnMainSync(() -> {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            result.set(insets != null && insets.isVisible(WindowInsets.Type.ime()));
        });
        if (result.get()) return true;
        // Insets can report hidden while the IME still owns its touch window
        // during dismissal. A following tap must wait until that window leaves.
        for (AccessibilityWindowInfo window : automation.getWindows()) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            Rect bounds = new Rect(); window.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return true;
        }
        return false;
    }

    void awaitPaint() throws Exception { awaitPaint(null); }

    void awaitPaint(String dynamicSelector) throws Exception {
        await("Finite page animations did not finish", 5000, () -> truth("document.getAnimations().every(a=>"
            + (dynamicSelector == null ? "" : "a.effect?.target?.closest(" + JSONObject.quote(dynamicSelector) + ")||")
            + "!['running','pending'].includes(a.playState)||!Number.isFinite(a.effect?.getComputedTiming().endTime))"));
        automation.waitForIdle(150, 5000);
        WebView view = web();
        CountDownLatch committed = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> view.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) {
                view.postOnAnimation(() -> {
                    view.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    view.invalidate();
                });
            }
        }));
        assertTrue("WebView did not commit the screenshot frame", committed.await(5, TimeUnit.SECONDS));
    }

    Rect visibleWebBounds(String selector) throws Exception {
        evaluate("window.__droyBoundsProbe=[]");
        try {
            await("The real touch target is not visible: " + selector, 5000,
                    () -> truth("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                            + ");const marker=n=>n?{tag:n.tagName,role:n.getAttribute('role'),sidebar:n.hasAttribute('data-droy-sidebar'),"
                            + "settings:n.hasAttribute('data-droy-settings'),row:n.hasAttribute('data-droy-session-row'),surface:n.hasAttribute('data-droy-session-surface'),"
                            + "ariaHidden:n.getAttribute('aria-hidden')}:null;"
                            + "const record=reason=>{const r=e?.getBoundingClientRect(),s=e?getComputedStyle(e):null;"
                            + "window.__droyBoundsProbe.push({reason,present:!!e,disabledType:typeof e?.disabled,disabledBoolean:!!e?.disabled,"
                            + "inertOwner:marker(e?.closest('[inert]')),rect:r?{x:r.x,y:r.y,width:r.width,height:r.height,top:r.top,bottom:r.bottom}:null,"
                            + "visibility:s?.visibility,display:s?.display,viewport:{width:innerWidth,height:innerHeight},scrollY});"
                            + "if(window.__droyBoundsProbe.length>6)window.__droyBoundsProbe.shift();return false};"
                            + "if(!e)return record('missing');if(e.disabled)return record('disabled');if(e.closest('[inert]'))return record('inert');"
                            + "const owner=e.closest('[data-droy-settings-options]'),v=visualViewport;"
                            + "if(owner){const r=e.getBoundingClientRect(),b=owner.getBoundingClientRect(),top=Math.max(b.top,(v?.offsetTop||0)+60),bottom=Math.min(b.bottom,(v?.offsetTop||0)+(v?.height||innerHeight)-24);"
                            + "if(r.top<top)owner.scrollTop+=r.top-top;else if(r.bottom>bottom)owner.scrollTop+=r.bottom-bottom}"
                            + "else e.scrollIntoView({block:'nearest',inline:'nearest',behavior:'instant'});"
                            + "const r=e.getBoundingClientRect(),s=getComputedStyle(e),visibleTop=v?.offsetTop||0,visibleBottom=visibleTop+(v?.height||innerHeight);"
                            + "if(!(r.width>0))return record('empty-width');if(!(r.height>0))return record('empty-height');"
                            + "if(!(r.top>=visibleTop))return record('above-viewport');if(!(r.bottom<=visibleBottom+1))return record('below-viewport');"
                            + "if(s.visibility==='hidden')return record('hidden');if(s.display==='none')return record('none');return true})()"));
            Rect bounds = webBounds(selector);
            assertFalse("The real touch target has empty bounds: " + selector, bounds.isEmpty());
            return bounds;
        } catch (AssertionError failure) {
            Object diagnostic = evaluate("window.__droyBoundsProbe||[]");
            throw new AssertionError(failure.getMessage() + "; safe target predicate=" + diagnostic, failure);
        } finally { evaluate("delete window.__droyBoundsProbe"); }
    }

    private void injectTouch(long downTime, int action, float x, float y) {
        assertNotEquals("The device has no advertised touchscreen input source", Integer.MIN_VALUE, touchDeviceId);
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = x;
        coordinates.y = y;
        coordinates.pressure = action == MotionEvent.ACTION_UP ? 0 : 1;
        coordinates.size = 1;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action,
                1, new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
                0, 0, 1, 1, touchDeviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            assertTrue("Android rejected a touchscreen input event", automation.injectInputEvent(event, true));
        } finally { event.recycle(); }
    }

    void invoke(String method) {
        instrumentation.runOnMainSync(() -> {
            try {
                Method target = MainActivity.class.getDeclaredMethod(method);
                target.setAccessible(true);
                target.invoke(activity);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    void nativeClick(String label) {
        instrumentation.runOnMainSync(() -> {
            Button button = findButton(activity.getWindow().getDecorView(), label);
            assertNotNull("Missing native button: " + label, button);
            button.performClick();
        });
    }

    static Button findButton(View view, String label) {
        if (view instanceof Button button && label.contentEquals(button.getText())) return button;
        if (view instanceof ViewGroup group) for (int index = 0; index < group.getChildCount(); index++) {
            Button found = findButton(group.getChildAt(index), label);
            if (found != null) return found;
        }
        return null;
    }

    static void await(String message, long timeout, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        do {
            if (condition.check()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    interface Condition { boolean check() throws Exception; }
}
