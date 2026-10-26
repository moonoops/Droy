package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Dedicated-emulator acceptance of the shipped diagnostic permission rows. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticsSettingsDeviceTest {
    private static final String[] GROUPS = {"diagnostics", "privacy", "usage", "apps"};
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private Map<String, ?> originalPreferences;
    private File home, savedHome;
    private boolean hadHome, ownsHome;
    private MainActivity activity;
    private DeviceUi ui;
    private CapabilityCatalog catalog;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Diagnostic UI fixtures require a dedicated emulator",
            Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop tasks and engine before diagnostic settings acceptance", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        originalPreferences = new HashMap<>(preferences.getAll());
        catalog = new CapabilityCatalog(context);
        RuntimeInstaller.installCertificate(context);
        RuntimeInstaller.install(context);
        home = new File(context.getFilesDir(), "dsh-home");
        savedHome = new File(context.getFilesDir(), "diagnostics-settings-home-" + UUID.randomUUID());
        hadHome = home.exists();
        if (hadHome) assertTrue("Could not preserve the original DSH home", home.renameTo(savedHome));
        ownsHome = true;
        assertTrue(home.mkdirs());
        assertTrue(preferences.edit().remove("allowDiagnostics").remove("allowPrivacyUsage")
            .putBoolean("allowUsage", true).putBoolean("allowApps", true).putBoolean("petEnabled", false).commit());
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        dismissAccountWelcome();
        openSettings();
        configureLayout();
        openPermissionsTab();
    }

    @After public void tearDown() throws Exception {
        if (!ownsHome) return;
        try {
            if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Diagnostic settings left the runtime alive", 15000, RuntimeService::canManageHistory);
        } finally {
            assertTrue("Keep the original home preserved until the fixture engine stops", RuntimeService.canManageHistory());
            HistoryData.deleteTree(home.toPath());
            if (hadHome) assertTrue("Could not restore the original DSH home", savedHome.renameTo(home));
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, ?> entry : originalPreferences.entrySet()) {
                String key = entry.getKey(); Object value = entry.getValue();
                if (value instanceof Boolean bool) restore.putBoolean(key, bool);
                else if (value instanceof String string) restore.putString(key, string);
                else if (value instanceof Integer integer) restore.putInt(key, integer);
                else if (value instanceof Long number) restore.putLong(key, number);
                else if (value instanceof Float number) restore.putFloat(key, number);
                else if (value instanceof Set<?> values) {
                    Set<String> strings = new HashSet<>(); for (Object item : values) strings.add((String) item);
                    restore.putStringSet(key, strings);
                } else throw new AssertionError("Unsupported saved preference type for " + key);
            }
            assertTrue("Could not restore product preferences", restore.commit());
            assertEquals("Diagnostic settings changed product choices", originalPreferences, preferences.getAll());
            ownsHome = false;
        }
    }

    @Test public void diagnosticRowsAreReadableAndActualTouchesPersistIndependentChoices() throws Exception {
        assertTrue("New diagnostics should retain the ordinary default-on policy", checked("allowDiagnostics"));
        assertTrue("New privacy query should retain the ordinary default-on policy", checked("allowPrivacyUsage"));
        Bundle viewport = new Bundle();
        viewport.putString("stream", "\nDROY_DIAGNOSTICS_VIEWPORT " + ui.evaluate(
            "({width:innerWidth,height:innerHeight,font:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size'),theme:document.documentElement.getAttribute('data-ds-theme-source')})") + "\n");
        instrumentation.sendStatus(2, viewport);
        for (String id : GROUPS) {
            JSONObject group = catalog.group(id);
            String key = group.getString("preference");
            markRow(key);
            assertRowLayout(key, group);
            capture("[data-test-diagnostics-row=" + key + "]", key + "-enabled");
            android.graphics.Rect touchArea = ui.visibleWebBounds("[data-droy-preference=" + key + "]");
            ui.tapAt(touchArea.centerX(), touchArea.top + 4);
            DeviceUi.await("The real switch touch did not save false for " + key, 5000,
                () -> preferences.contains(key) && !preferences.getBoolean(key, true) && !checked(key));
            capture("[data-test-diagnostics-row=" + key + "]", key + "-disabled");
            ui.tap("[data-droy-settings-back]");
            DeviceUi.await("Settings Back did not return to the conversation", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
            openSettings(); openPermissionsTab();
            assertFalse("Explicit off choice was lost for " + key, checked(key));
            markRow(key);
            ui.tap(toggle(key));
            DeviceUi.await("The real switch touch did not save true for " + key, 5000,
                () -> preferences.getBoolean(key, false) && checked(key));
            assertRowLayout(key, group);
        }
        JSONObject lastGroup = catalog.groups().getJSONObject(catalog.groups().length() - 1);
        String lastKey = lastGroup.getString("preference");
        markRow(lastKey); assertRowLayout(lastKey, lastGroup);
        capture("[data-test-diagnostics-row=" + lastKey + "]", "last-capability");
        capture("[data-droy-permission=directory]", "permission-page-bottom");
        ui.pressBack();
        DeviceUi.await("System Back did not close permission settings", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void openSettings() throws Exception {
        ui.hideKeyboard(); dismissAccountWelcome();
        ui.tap("[data-droy-menu]");
        DeviceUi.await("The conversation menu did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The real Settings entry did not open", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void openPermissionsTab() throws Exception {
        ui.tap("[data-droy-settings-section=droy-permissions]");
        int count = catalog.groups().length();
        DeviceUi.await("Diagnostic permission groups did not load", 10000,
            () -> ui.truth("document.querySelectorAll('[data-droy-preference] [role=switch]').length===" + count
                + "&&!document.querySelector('[data-droy-preference] [role=switch]').disabled"));
    }

    private void configureLayout() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        boolean maxFont = "true".equals(arguments.getString("layoutMaxTextFont"));
        String theme = arguments.getString("layoutTheme");
        if (!maxFont && theme == null) return;
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The original theme and font controls did not load", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')&&document.querySelectorAll('[data-droy-theme-choice]').length===3"));
        if (maxFont) {
            if (!ui.truth("document.querySelector('[data-droy-font-expander]').getAttribute('aria-expanded')==='true'"))
                ui.tap("[data-droy-font-expander]");
            String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
            DeviceUi.await("Expanded text-font control did not load", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(increase) + ")"));
            for (int step = 0; step < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); step++) {
                String before = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size')"));
                ui.tap(increase);
                DeviceUi.await("A real DSH font-size touch did not apply", 5000,
                    () -> !before.equals(String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size')"))));
            }
            assertTrue("The actual DSH text-font control did not reach maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
        }
        if (theme != null) {
            assertTrue("Unsupported test theme", Set.of("light", "dark", "system").contains(theme));
            ui.tap("[data-droy-theme-choice=" + theme + "]");
            DeviceUi.await("The actual DSH theme choice did not apply", 5000,
                () -> theme.equals(ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')")));
        }
    }

    private void markRow(String key) throws Exception {
        ui.evaluate("document.querySelector(" + JSONObject.quote("[data-droy-preference=" + key + "]")
            + ").closest('.droy-row').setAttribute('data-test-diagnostics-row'," + JSONObject.quote(key) + ")");
    }

    private void assertRowLayout(String key, JSONObject group) throws Exception {
        String row = "[data-test-diagnostics-row=" + key + "]";
        ui.visibleWebBounds(row + " .droy-row-title");
        JSONObject shape = (JSONObject) ui.evaluate(String.format(java.util.Locale.ROOT, """
            (()=>{const row=document.querySelector(%s),toggle=row.querySelector('[data-droy-preference]'),r=row.getBoundingClientRect(),t=toggle.getBoundingClientRect();
              let textFits=true;const walker=document.createTreeWalker(row.querySelector('.droy-row-copy'),NodeFilter.SHOW_TEXT);
              while(walker.nextNode()){const node=walker.currentNode;if(!node.textContent.trim())continue;const range=document.createRange();range.selectNodeContents(node);
                for(const x of range.getClientRects()){if(x.width<.1||x.height<.1)continue;
                  if(x.left<r.left-1||x.right>r.right+1||x.top<r.top-1||x.bottom>r.bottom+1)textFits=false;
                  if(x.right>t.left+1&&x.left<t.right-1&&x.bottom>t.top+1&&x.top<t.bottom-1)textFits=false;
                  for(let a=node.parentElement;a&&a!==row;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();
                    if(/hidden|clip/.test(s.overflowX)&&(x.left<b.left-1||x.right>b.right+1))textFits=false;
                    if(/hidden|clip/.test(s.overflowY)&&(x.top<b.top-1||x.bottom>b.bottom+1))textFits=false;}}}
              const ratio=window.__droyTouchConfig?.dpToCss||0;
              return {title:row.querySelector('.droy-row-title').textContent.trim(),description:row.querySelector('.droy-description').textContent.trim(),textFits,
                horizontalOverflow:[document.documentElement,document.querySelector('[data-droy-settings-options]'),row].some(e=>e.scrollWidth>e.clientWidth+1),
                touchWidthDp:ratio?t.width/ratio:0,touchHeightDp:ratio?t.height/ratio:0};})()
            """,JSONObject.quote(row)));
        assertEquals(group.getString("label"), shape.getString("title"));
        assertEquals(group.getString("description"), shape.getString("description"));
        assertTrue("Diagnostic permission text is clipped or overlaps its switch: " + shape, shape.getBoolean("textFits"));
        assertFalse("Diagnostic permissions introduced horizontal overflow: " + shape, shape.getBoolean("horizontalOverflow"));
        assertTrue("Diagnostic switch must provide a 48 dp touch target: " + shape,
            shape.getDouble("touchWidthDp") >= 47.5 && shape.getDouble("touchHeightDp") >= 47.5);
        ui.visibleWebBounds(toggle(key));
    }

    private boolean checked(String key) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(toggle(key)) + ").getAttribute('aria-checked')==='true'");
    }
    private static String toggle(String key) { return "[data-droy-preference=" + key + "] [role=switch]"; }

    private void dismissAccountWelcome() throws Exception {
        ui.evaluate("(()=>{for(const h of document.querySelectorAll('[role=dialog] h2')){if(!['开始使用','Get started'].includes(h.textContent.trim()))continue;"
            + "const b=h.closest('[role=dialog]').querySelector('button[aria-label=关闭],button[aria-label=Close]');if(b&&!b.disabled)b.click()}})()");
    }

    private void capture(String selector, String stage) throws Exception {
        String label = InstrumentationRegistry.getArguments().getString("diagnosticsEvidenceLabel", "settings").replaceAll("[^A-Za-z0-9_-]", "_");
        captureSettingsRow(instrumentation, context, ui, selector, "diagnostics-settings-evidence", label + "-" + stage, false);
    }

    /** A compositor marker binds the real screenshot to the settled DOM geometry. */
    static void captureSettingsRow(Instrumentation instrumentation, Context context, DeviceUi ui,
                                   String selector, String folderName, String name, boolean cropOnly) throws Exception {
        WebView web = ui.web();
        instrumentation.runOnMainSync(() -> web.flingScroll(0, 0));
        Object previousBehavior = ui.evaluate("(()=>{const p=document.querySelector('[data-droy-settings-options]');"
            + "const old=p.style.scrollBehavior;p.style.scrollBehavior='auto';return old})()");
        try {
            JSONObject initial = captureGeometry(ui, selector);
            boolean split = initial.getJSONObject("row").getDouble("height") > initial.getJSONObject("clip").getDouble("height") - 16;
            assertTrue("A settings row needs more than the two declared capture segments", !split
                || initial.getJSONObject("row").getDouble("height") <= 2 * (initial.getJSONObject("clip").getDouble("height") - 16));
            for (String part : split ? new String[]{"top", "bottom"} : new String[]{"whole"}) {
                ui.evaluate(String.format(java.util.Locale.ROOT, """
                    (()=>{const e=document.querySelector(%s),p=document.querySelector('[data-droy-settings-options]'),r=e.getBoundingClientRect(),b=p.getBoundingClientRect(),v=visualViewport;
                      const top=Math.max(b.top,v?.offsetTop||0)+8,bottom=Math.min(b.bottom,(v?.offsetTop||0)+(v?.height||innerHeight))-8;
                      const anchor=%s,offset=anchor==='top'?r.top-top:anchor==='bottom'?r.bottom-bottom:r.top-(top+(bottom-top-r.height)/2);
                      p.scrollTo({top:p.scrollTop+offset,behavior:'instant'});return true})()
                    """,JSONObject.quote(selector), JSONObject.quote(part)));
                JSONObject[] previous = {null}; long[] stableSince = {0};
                DeviceUi.await("Diagnostic capture target kept scrolling: " + selector, 5000, () -> {
                    JSONObject current = captureGeometry(ui, selector);
                    if (previous[0] != null && current.toString().equals(previous[0].toString())) {
                        if (stableSince[0] == 0) stableSince[0] = android.os.SystemClock.elapsedRealtime();
                    } else stableSince[0] = 0;
                    previous[0] = current;
                    return stableSince[0] != 0 && android.os.SystemClock.elapsedRealtime() - stableSince[0] >= 250;
                });
                JSONObject positioned = captureGeometry(ui, selector);
                JSONObject row = positioned.getJSONObject("row"), clip = positioned.getJSONObject("clip");
                assertTrue("The intended settings row is not inside the content viewport: " + positioned,
                    row.getDouble("bottom") > clip.getDouble("top") + 12 && row.getDouble("top") < clip.getDouble("bottom") - 12);
                if (!split) assertTrue("A complete settings row is outside the capture viewport: " + positioned,
                    row.getDouble("top") >= clip.getDouble("top") - 1 && row.getDouble("bottom") <= clip.getDouble("bottom") + 1);
                else if (part.equals("top")) assertTrue(positioned.toString(), row.getDouble("top") >= clip.getDouble("top") - 1);
                else assertTrue(positioned.toString(), row.getDouble("bottom") <= clip.getDouble("bottom") + 1);
                ui.evaluate(String.format(java.util.Locale.ROOT, """
                    (()=>{document.getElementById('droy-diagnostic-capture-marker')?.remove();const r=document.querySelector(%s).getBoundingClientRect(),owner=document.querySelector('[data-droy-settings-options]'),p=owner.getBoundingClientRect(),v=visualViewport;
                      const sequence=window.__droyDiagnosticCaptureSequence=(window.__droyDiagnosticCaptureSequence||0)+1;
                      const color=[40+(sequence*37)%%160,40+(sequence*67)%%160,210];const marker=document.createElement('div');marker.id='droy-diagnostic-capture-marker';marker.setAttribute('aria-hidden','true');
                      marker.dataset.color=JSON.stringify(color);marker.style.cssText='position:fixed;z-index:2147483647;pointer-events:none;width:6px;height:6px;';
                      marker.style.left=(Math.min(r.right,p.right,(v?.offsetLeft||0)+(v?.width||innerWidth))-9)+'px';
                      marker.style.top=(Math.max(r.top,p.top,v?.offsetTop||0)+3)+'px';marker.style.backgroundColor='rgb('+color.join(',')+')';
                      (owner.closest('[data-droy-settings-panel]')||document.body).appendChild(marker);return true})()
                    """,JSONObject.quote(selector)));
                for (int frame = 0; frame < 2; frame++) drawCaptureFrame(instrumentation, web);
                instrumentation.getUiAutomation().waitForIdle(200, 5000);
                drawCaptureFrame(instrumentation, web);
                JSONObject before = captureGeometry(ui, selector);
                AtomicReference<JSONObject> nativeBounds = new AtomicReference<>();
                instrumentation.runOnMainSync(() -> {
                    int[] location = new int[2]; web.getLocationOnScreen(location);
                    try { nativeBounds.set(new JSONObject().put("left", location[0]).put("top", location[1])
                        .put("width", web.getWidth()).put("height", web.getHeight())); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                Bitmap screen = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(screen);
                try {
                    JSONObject after = captureGeometry(ui, selector);
                    assertEquals("Settings moved between compositor commit and screenshot", before.toString(), after.toString());
                    JSONObject nativeWeb = nativeBounds.get(), viewport = before.getJSONObject("viewport");
                    double scale = nativeWeb.getDouble("width") / viewport.getDouble("width");
                    JSONObject marker = before.getJSONObject("marker");
                    JSONObject visibleRow = before.getJSONObject("row"), visibleClip = before.getJSONObject("clip");
                    assertTrue("The compositor marker is outside the intended settings row", marker.getDouble("left") >= Math.max(visibleRow.getDouble("left"), visibleClip.getDouble("left"))
                        && marker.getDouble("right") <= Math.min(visibleRow.getDouble("right"), visibleClip.getDouble("right"))
                        && marker.getDouble("top") >= Math.max(visibleRow.getDouble("top"), visibleClip.getDouble("top"))
                        && marker.getDouble("bottom") <= Math.min(visibleRow.getDouble("bottom"), visibleClip.getDouble("bottom")));
                    int markerX = (int) Math.round(nativeWeb.getDouble("left") + (marker.getDouble("left") + marker.getDouble("width") / 2 - viewport.getDouble("offsetLeft")) * scale);
                    int markerY = (int) Math.round(nativeWeb.getDouble("top") + (marker.getDouble("top") + marker.getDouble("height") / 2 - viewport.getDouble("offsetTop")) * scale);
                    assertTrue("Compositor marker is outside the screenshot", markerX >= 0 && markerX < screen.getWidth() && markerY >= 0 && markerY < screen.getHeight());
                    int pixel = screen.getPixel(markerX, markerY); JSONArray color = before.getJSONArray("markerColor");
                    assertTrue("Screenshot has not reached the DOM's committed settings row: " + before,
                        Math.abs(Color.red(pixel) - color.getInt(0)) <= 12 && Math.abs(Color.green(pixel) - color.getInt(1)) <= 12 && Math.abs(Color.blue(pixel) - color.getInt(2)) <= 12);
                    Rect crop = new Rect((int) Math.floor(nativeWeb.getDouble("left") + (Math.max(visibleRow.getDouble("left"), visibleClip.getDouble("left")) - viewport.getDouble("offsetLeft")) * scale),
                        (int) Math.floor(nativeWeb.getDouble("top") + (Math.max(visibleRow.getDouble("top"), visibleClip.getDouble("top")) - viewport.getDouble("offsetTop")) * scale),
                        (int) Math.ceil(nativeWeb.getDouble("left") + (Math.min(visibleRow.getDouble("right"), visibleClip.getDouble("right")) - viewport.getDouble("offsetLeft")) * scale),
                        (int) Math.ceil(nativeWeb.getDouble("top") + (Math.min(visibleRow.getDouble("bottom"), visibleClip.getDouble("bottom")) - viewport.getDouble("offsetTop")) * scale));
                    assertTrue("The visible settings row has no native crop area", crop.intersect(0, 0, screen.getWidth(), screen.getHeight()) && !crop.isEmpty());
                    before.put("nativeWebView", nativeWeb).put("nativeCropBounds", new JSONObject().put("left", crop.left).put("top", crop.top).put("right", crop.right).put("bottom", crop.bottom))
                        .put("scale", scale).put("part", part).put("partCount", split ? 2 : 1).put("captureRevision", 2).put("selector", selector)
                        .put("cropOnly", cropOnly).put("markerPixel", pixel).put("markerNativeX", markerX).put("markerNativeY", markerY);
                    File directory = new File(context.getExternalFilesDir(null), folderName); assertTrue(directory.isDirectory() || directory.mkdirs());
                    String stem = name + (split ? "-" + part : ""); File file = new File(directory, stem + ".png");
                    Bitmap output = cropOnly ? Bitmap.createBitmap(screen, crop.left, crop.top, crop.width(), crop.height()) : screen;
                    try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
                    finally { if (output != screen) output.recycle(); }
                    try (FileOutputStream stream = new FileOutputStream(new File(directory, stem + ".json"))) {
                        stream.write(before.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                    Bundle status = new Bundle(); status.putString("stream", "\nDROY_DIAGNOSTICS_UI_EVIDENCE " + file.getAbsolutePath() + "\nDROY_DIAGNOSTICS_CAPTURE " + before + "\n");
                    instrumentation.sendStatus(2, status);
                } finally { screen.recycle(); ui.evaluate("document.getElementById('droy-diagnostic-capture-marker')?.remove()"); }
            }
        } finally {
            ui.evaluate("document.getElementById('droy-diagnostic-capture-marker')?.remove();document.querySelector('[data-droy-settings-options]').style.scrollBehavior="
                + JSONObject.quote(String.valueOf(previousBehavior)));
        }
    }

    private static JSONObject captureGeometry(DeviceUi ui, String selector) throws Exception {
        return (JSONObject) ui.evaluate(String.format(java.util.Locale.ROOT, """
            (()=>{const e=document.querySelector(%s),p=document.querySelector('[data-droy-settings-options]'),r=e.getBoundingClientRect(),b=p.getBoundingClientRect(),v=visualViewport;
              const rect=x=>({left:+x.left.toFixed(2),top:+x.top.toFixed(2),right:+x.right.toFixed(2),bottom:+x.bottom.toFixed(2),width:+x.width.toFixed(2),height:+x.height.toFixed(2)});
              const left=Math.max(b.left,v?.offsetLeft||0),top=Math.max(b.top,v?.offsetTop||0),right=Math.min(b.right,(v?.offsetLeft||0)+(v?.width||innerWidth)),bottom=Math.min(b.bottom,(v?.offsetTop||0)+(v?.height||innerHeight));
              const m=document.getElementById('droy-diagnostic-capture-marker');return {row:rect(r),clip:{left,top,right,bottom,width:right-left,height:bottom-top},scrollTop:+p.scrollTop.toFixed(2),
                viewport:{width:innerWidth,height:innerHeight,offsetLeft:v?.offsetLeft||0,offsetTop:v?.offsetTop||0},marker:m?rect(m.getBoundingClientRect()):null,markerColor:m?JSON.parse(m.dataset.color):null};})()
            """,JSONObject.quote(selector)));
    }

    private static void drawCaptureFrame(Instrumentation instrumentation, WebView web) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) {
                web.postOnAnimation(() -> {
                    web.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    web.invalidate();
                });
            }
        }));
        assertTrue("The real WebView frame did not commit", committed.await(5, TimeUnit.SECONDS));
    }
}
