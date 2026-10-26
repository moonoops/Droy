package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The actual DSH browser chrome and cross-process surface, with isolated fictional history. */
@RunWith(AndroidJUnit4.class)
public final class BrowserUiDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private FullDshUiDeviceTest fixture;
    private MainActivity activity;
    private DeviceUi ui;
    private String surfaceSession;
    private final Set<WindowInsetsAnimation> imeAnimations = new HashSet<>();
    private final Map<String, String> original = new HashMap<>();
    private static final String[] THEME_KEYS = {"themePreference", "themeColorScheme", "themeBackgroundColor"};
    private static final String ROOT = ".SB_kFW_root";
    private static final String ADDRESS = ".SB_kFW_root .SB_kFW_address";

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : THEME_KEYS) if (preferences.contains(key)) original.put(key, preferences.getString(key, ""));
        fixture = new FullDshUiDeviceTest();
        fixture.setUp();
        activity = fixture.browserFixtureActivity();
        ui = fixture.browserFixtureUi();
        fixture.selectBrowserFixture();
        instrumentation.runOnMainSync(() -> activity.getWindow().getDecorView().setWindowInsetsAnimationCallback(
            new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override public void onPrepare(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) != 0) imeAnimations.add(animation);
                }
                @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> running) { return insets; }
                @Override public void onEnd(WindowInsetsAnimation animation) { imeAnimations.remove(animation); }
            }));
        JSONObject viewport = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,mobile:document.documentElement.hasAttribute('data-droy-mobile')})");
        assertTrue("Browser UI regressions require the actual mobile layout: " + viewport,
            viewport.getBoolean("mobile") && Math.min(viewport.getDouble("width"), viewport.getDouble("height")) <= 600);
    }

    @After public void tearDown() throws Exception {
        try {
            if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(
                () -> activity.getWindow().getDecorView().setWindowInsetsAnimationCallback(null));
            if (surfaceSession != null && activity != null && !activity.isDestroyed())
                nativePage(new JSONObject().put("operation", "close").put("tabId", surfaceSession));
        } finally {
            try { if (fixture != null) fixture.tearDown(); }
            finally {
                if (preferences != null) {
                    SharedPreferences.Editor restore = preferences.edit();
                    for (String key : THEME_KEYS) {
                        if (original.containsKey(key)) restore.putString(key, original.get(key)); else restore.remove(key);
                    }
                    assertTrue(restore.commit());
                }
            }
        }
    }

    @Test public void sharedBrowserKeepsDshChromeThemeAndSafeAreaWithoutHandoverControls() throws Exception {
        for (String theme : new String[] {"light", "dark", "system"}) {
            chooseTheme(theme);
            openBrowser();
            assertSharedChrome();
            Rect toolbar = ui.webBounds(ROOT + " .SB_kFW_toolbar");
            AtomicReference<android.graphics.Insets> bars = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> bars.set(activity.getWindow().getDecorView().getRootWindowInsets()
                .getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout())));
            Rect display = context.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
            assertTrue("DSH browser controls overlap the status bar or cutout", toolbar.top >= bars.get().top);
            assertTrue("DSH browser controls overflow the narrow display", toolbar.left >= bars.get().left
                && toolbar.right <= display.right - bars.get().right);
            for (String selector : new String[] {ADDRESS, ROOT + " button[aria-label='后退']," + ROOT + " button[aria-label='Back']",
                    ROOT + " button[aria-label='前进']," + ROOT + " button[aria-label='Forward']",
                    ROOT + " button[aria-label='刷新']," + ROOT + " button[aria-label='Reload']",
                    ROOT + " button[aria-label='在系统浏览器中打开']," + ROOT + " button[aria-label='Open in system browser']"}) {
                Rect control = ui.webBounds(selector);
                assertTrue("A DSH browser control is clipped by its toolbar: " + selector,
                    control.width() > 0 && control.height() > 0 && toolbar.contains(control));
            }
            assertTrue("The browser chrome did not inherit DSH's accepted theme", ui.truth(
                "getComputedStyle(document.querySelector('" + ROOT + "')).backgroundColor===getComputedStyle(document.body).backgroundColor"
                + "&&document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
            DeviceUi.await("System bars did not follow DSH's browser theme", 5000, () -> {
                Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
                assertNotNull("The shared browser must allow user screenshots", image);
                try {
                    int expected = Color.parseColor(preferences.getString("themeBackgroundColor", "#ffffff"));
                    return image.getPixel(image.getWidth() / 4, 1) == expected
                        && image.getPixel(image.getWidth() / 4, image.getHeight() - 2) == expected;
                } finally { image.recycle(); }
            });
            capture("theme-" + theme);
            ui.pressBack();
            awaitCollapsed();
        }
    }

    @Test public void keyboardBackPreservesTheSharedPageThenCollapsesThroughDsh() throws Exception {
        openBrowser();
        ui.showKeyboard(ADDRESS);
        capture("keyboard-visible");
        ui.pressBack();
        DeviceUi.await("The first Back did not hide the shared address keyboard", 10000, () -> !ui.keyboardVisible());
        assertTrue("Keyboard dismissal removed the DSH browser page", visibleBrowser());
        assertTrue("Keyboard dismissal left the existing MainActivity", ui.nativeNavigationState().getBoolean("activityFocus"));
        capture("keyboard-hidden");
        ui.pressBack();
        awaitCollapsed();
        assertFalse("Collapsing the DSH browser finished MainActivity", ui.nativeNavigationState().getBoolean("finishing"));
        assertTrue("Collapsing the DSH browser did not restore the existing chat", ui.truth("!!document.querySelector('[data-composer-input]')"));
    }

    @Test public void actualRemoteBlankSurfaceIsRenderedInsideTheDshViewportAndReleasesOnClose() throws Exception {
        chooseTheme("dark");
        openBrowser();
        surfaceSession = (String) ui.evaluate("document.querySelector('" + ROOT + "').closest('[data-sidebar-right-tab]').getAttribute('data-sidebar-right-tab')");
        AtomicReference<JSONObject> measured = new AtomicReference<>();
        try {
            DeviceUi.await("The DSH browser viewport did not settle inside the actual page", 10000, () -> {
                JSONObject metrics = (JSONObject) ui.evaluate("(()=>{const r=document.querySelector('" + ROOT + " .SB_kFW_viewport').getBoundingClientRect(),"
                    + "viewportWidth=innerWidth,viewportHeight=innerHeight,pixelRatio=devicePixelRatio,epsilon=1/pixelRatio;return {"
                    + "viewport:{left:r.left,top:r.top,width:r.width,height:r.height,viewportWidth},innerWidth:viewportWidth,innerHeight:viewportHeight,"
                    + "devicePixelRatio,visualViewportScale:visualViewport?.scale,"
                    + "inside:[r.left,r.top,r.width,r.height,viewportWidth,viewportHeight,pixelRatio].every(Number.isFinite)"
                    + "&&r.width>0&&r.height>0&&viewportWidth>0&&viewportHeight>0&&pixelRatio>0"
                    + "&&r.left>=-epsilon&&r.top>=-epsilon&&r.right<=viewportWidth+epsilon&&r.bottom<=viewportHeight+epsilon}})()");
                measured.set(metrics);
                return metrics.getBoolean("inside");
            });
        } finally { reportAttachMetrics(measured.get()); }
        JSONObject viewport = measured.get().getJSONObject("viewport");
        // Use the real internal surface bridge with an empty native tab. Public
        // tools still reject about:blank; no debug URL or fake page is installed.
        JSONObject attached = nativePage(new JSONObject().put("operation", "attach").put("tabId", surfaceSession).put("viewport", viewport));
        assertEquals(surfaceSession, attached.getString("sessionId"));
        assertTrue(attached.getBoolean("sessionOpen"));
        Rect expected = ui.webBounds(ROOT + " .SB_kFW_viewport");
        awaitRenderedSurface(expected);
        assertSharedChrome();
        capture("remote-surface");
        try {
            nativePage(new JSONObject().put("operation", "attach").put("tabId", surfaceSession + "-rejected")
                .put("viewport", new JSONObject(viewport.toString()).put("left", -1)));
            fail("An out-of-bounds replacement tab was attached");
        } catch (IllegalArgumentException expectedFailure) { /* The old page must disappear even when its replacement is rejected. */ }
        DeviceUi.await("A rejected replacement left the previous tab's surface beneath another tab", 5000, () -> {
            boolean[] absent = {false};
            instrumentation.runOnMainSync(() -> absent[0] = findSurface(activity.getWindow().getDecorView()) == null);
            return absent[0];
        });
        nativePage(new JSONObject().put("operation", "attach").put("tabId", surfaceSession).put("viewport", viewport));
        awaitRenderedSurface(expected);
        nativePage(new JSONObject().put("operation", "close").put("tabId", surfaceSession));
        surfaceSession = null;
        DeviceUi.await("Closing the page did not remove its native surface", 5000, () -> {
            boolean[] absent = {false};
            instrumentation.runOnMainSync(() -> absent[0] = findSurface(activity.getWindow().getDecorView()) == null);
            return absent[0];
        });
        assertTrue("Closing native contents removed the DSH tab instead of its page", visibleBrowser());
        DeviceUi.await("The DSH start page did not recover after native contents closed", 10000,
            () -> ui.truth("(()=>{const e=document.querySelector('" + ROOT + " .SB_kFW_start');return !!e&&e.getClientRects().length>0"
                + "&&getComputedStyle(e).visibility!=='hidden'&&e.textContent.includes('HTTP')})()"));
        capture("remote-closed");
    }

    private void awaitRenderedSurface(Rect expected) throws Exception {
        DeviceUi.await("The cross-process browser did not render inside the existing DSH viewport", 15000, () -> {
            AtomicReference<SurfaceView> current = new AtomicReference<>();
            instrumentation.runOnMainSync(() -> current.set(findSurface(activity.getWindow().getDecorView())));
            SurfaceView surface = current.get();
            if (surface == null) return false;
            Rect actual = new Rect();
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                surface.getGlobalVisibleRect(actual);
                ready[0] = surface.isShown() && surface.getHolder().getSurface().isValid();
            });
            if (!ready[0] || Math.abs(actual.left - expected.left) > 2 || Math.abs(actual.top - expected.top) > 2
                || Math.abs(actual.right - expected.right) > 2 || Math.abs(actual.bottom - expected.bottom) > 2) return false;
            Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try {
                // The real remote WebView's opaque white blank page differs from
                // DSH's dark start page and an unattached SurfaceView's black fill.
                return screenshot.getPixel(actual.centerX(), actual.centerY()) == Color.WHITE
                    && screenshot.getPixel(actual.centerX(), actual.top + Math.max(1, actual.height() / 4)) == Color.WHITE;
            } finally { screenshot.recycle(); }
        });
    }

    private void capture(String stage) throws Exception {
        awaitImeSettled(stage.equals("keyboard-visible"));
        DeviceUi.await("The screenshot stage does not show the actual DSH browser: " + stage, 10000,
            () -> visibleBrowser() && ui.truth("!document.querySelector('[data-shortcut-modal=settings]')"
                + "&&document.getAnimations().every(a=>a.playState!=='running'||a.effect?.getTiming().iterations===Infinity)"));
        awaitPaintedFrame();
        assertEquals("The keyboard changed while capturing " + stage, stage.equals("keyboard-visible"), ui.keyboardVisible());
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Could not capture the actual browser window: " + stage, image);
        try {
            File directory = new File(context.getCacheDir(), "browser-ui-evidence");
            assertTrue("Could not create the browser evidence directory", directory.isDirectory() || directory.mkdirs());
            String orientation = image.getWidth() > image.getHeight() ? "landscape" : "portrait";
            int fontPercent = Math.round(activity.getResources().getConfiguration().fontScale * 100);
            File output = File.createTempFile(orientation + "-" + image.getWidth() + "x" + image.getHeight()
                + "-font" + fontPercent + "-" + stage + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                assertTrue("Could not save the browser screenshot", image.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            Bundle status = new Bundle();
            status.putString("stream", "\nDROY_BROWSER_EVIDENCE " + output.getAbsolutePath() + "\n");
            instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }

    private void awaitImeSettled(boolean expectedVisible) throws Exception {
        int[] previousBottom = {-1};
        long[] unchangedSince = {0};
        DeviceUi.await("The keyboard animation did not settle for the screenshot", 10000, () -> {
            boolean[] expected = {false};
            int[] bottom = {-1};
            instrumentation.runOnMainSync(() -> {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets == null) return;
                bottom[0] = insets.getInsets(WindowInsets.Type.ime()).bottom;
                expected[0] = insets.isVisible(WindowInsets.Type.ime()) == expectedVisible && imeAnimations.isEmpty();
            });
            if (!expected[0] || bottom[0] != previousBottom[0]) {
                previousBottom[0] = bottom[0]; unchangedSince[0] = 0; return false;
            }
            if (unchangedSince[0] == 0) unchangedSince[0] = SystemClock.elapsedRealtime();
            return SystemClock.elapsedRealtime() - unchangedSince[0] >= 200;
        });
    }

    private void awaitPaintedFrame() throws Exception {
        ui.evaluate("window.__browserCaptureFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__browserCaptureFrame=true))");
        DeviceUi.await("The browser screenshot did not reach a painted animation frame", 5000,
            () -> ui.truth("window.__browserCaptureFrame===true"));
        CountDownLatch visual = new CountDownLatch(1);
        WebView current = ui.web();
        instrumentation.runOnMainSync(() -> current.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { visual.countDown(); }
        }));
        assertTrue("The WebView did not publish the browser's current visual state", visual.await(5, TimeUnit.SECONDS));
        CountDownLatch drawn = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnDrawListener listener = new ViewTreeObserver.OnDrawListener() {
                private boolean posted;
                @Override public void onDraw() {
                    if (posted) return;
                    posted = true;
                    decor.post(() -> {
                        if (decor.getViewTreeObserver().isAlive()) decor.getViewTreeObserver().removeOnDrawListener(this);
                        decor.postOnAnimation(drawn::countDown);
                    });
                }
            };
            decor.getViewTreeObserver().addOnDrawListener(listener);
            decor.invalidate();
        });
        assertTrue("Android did not draw the committed browser screenshot frame", drawn.await(5, TimeUnit.SECONDS));
        instrumentation.waitForIdleSync();
        SystemClock.sleep(100);
    }

    private void chooseTheme(String theme) throws Exception {
        ui.openPermissions();
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The real DSH theme controls did not load", 10000,
            () -> ui.truth("document.querySelectorAll('[data-droy-theme-choice]').length===3"));
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("DSH did not accept the browser theme", 10000,
            () -> theme.equals(preferences.getString("themePreference", ""))
                && ui.truth("document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
        ui.pressBack();
        DeviceUi.await("The theme settings did not close", 5000,
            () -> ui.truth("!document.querySelector('[data-shortcut-modal=settings]')"));
    }

    private void openBrowser() throws Exception {
        ui.evaluate("window.DroyMobile.openBrowser()");
        DeviceUi.await("The shared DSH browser did not open", 10000, this::visibleBrowser);
        ui.hideKeyboard();
    }
    private boolean visibleBrowser() throws Exception {
        return ui.truth("(()=>{const e=document.querySelector('" + ROOT + "');return !!e&&e.getClientRects().length>0"
            + "&&getComputedStyle(e).visibility!=='hidden'&&!e.closest('[hidden],[inert],[aria-hidden=true]')})()");
    }
    private void awaitCollapsed() throws Exception {
        DeviceUi.await("Back did not collapse the DSH browser through its existing navigation", 10000,
            () -> !visibleBrowser() && !ui.nativeNavigationState().getBoolean("finishing"));
    }
    private void assertSharedChrome() throws Exception {
        assertTrue("The browser has duplicate toolbar or retired user-handover controls", ui.truth(
            "(()=>{const roots=[...document.querySelectorAll('" + ROOT + "')].filter(e=>e.getClientRects().length);"
                + "return roots.length===1&&roots[0].querySelectorAll('.SB_kFW_toolbar').length===1"
                + "&&!document.querySelector('[data-droy-native-browser]')"
                + "&&![...document.querySelectorAll('button')].some(e=>['用户接管','允许助手继续','在 Droy 浏览器中继续'].includes(e.textContent.trim()))})()"));
        assertTrue(ui.nativeNavigationState().getBoolean("activityFocus"));
    }
    @SuppressWarnings("deprecation")
    private void reportAttachMetrics(JSONObject measurement) throws Exception {
        JSONObject metrics = measurement == null ? new JSONObject() : new JSONObject(measurement.toString());
        WebView current = ui.web();
        instrumentation.runOnMainSync(() -> {
            try {
                View parent = (View) current.getParent();
                metrics.put("nativeWebWidth", current.getWidth()).put("nativeWebHeight", current.getHeight())
                    .put("nativeWebLeft", current.getLeft()).put("nativeWebTop", current.getTop())
                    .put("legacyWebScale", current.getScale()).put("displayDensity", current.getResources().getDisplayMetrics().density)
                    .put("parentWidth", parent.getWidth()).put("parentHeight", parent.getHeight());
            } catch (Exception error) { throw new AssertionError("Could not record actual browser attachment geometry", error); }
        });
        Bundle status = new Bundle();
        status.putString("stream", "\nDROY_BROWSER_ATTACH_METRICS " + metrics + "\n");
        instrumentation.sendStatus(2, status);
    }

    private JSONObject nativePage(JSONObject args) throws Exception {
        AtomicReference<JSONObject> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            try {
                Field field = MainActivity.class.getDeclaredField("browserSurface"); field.setAccessible(true);
                BrowserSurfaceHost host = (BrowserSurfaceHost) field.get(activity);
                host.request(args, value -> { result.set(value); done.countDown(); }, error -> { failure.set(error); done.countDown(); });
            } catch (Exception error) { failure.set(error); done.countDown(); }
        });
        assertTrue("The actual browser surface bridge timed out", done.await(20, TimeUnit.SECONDS));
        if (failure.get() != null) throw failure.get();
        assertNotNull(result.get()); return result.get();
    }
    private static SurfaceView findSurface(View view) {
        if (view instanceof SurfaceView surface && surface.isShown()) return surface;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            SurfaceView found = findSurface(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
}
