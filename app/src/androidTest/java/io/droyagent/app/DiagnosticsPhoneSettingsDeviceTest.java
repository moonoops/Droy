package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.SystemDiagnosticsTools;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Default-phone settings checks; leaves existing DSH files and model configuration in place. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticsPhoneSettingsDeviceTest {
    private static final String[] KEYS = {"allowDiagnostics", "allowPrivacyUsage"};
    private final Map<String, Boolean> saved = new HashMap<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences;
    private MainActivity activity;
    private DeviceUi ui;
    private boolean ownsRuntime;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks and engine before the phone settings check", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        for (String key : KEYS) if (preferences.contains(key)) saved.put(key, preferences.getBoolean(key, false));
        ownsRuntime = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
    }

    @After public void tearDown() throws Exception {
        if (!ownsRuntime) return;
        try {
            if (activity != null && !activity.isDestroyed()) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("The phone settings check left the engine running", 15000, RuntimeService::canManageHistory);
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : KEYS) {
                if (saved.containsKey(key)) restore.putBoolean(key, saved.get(key)); else restore.remove(key);
            }
            assertTrue("Could not restore original diagnostic choices", restore.commit());
            for (String key : KEYS) {
                assertEquals("The check changed whether a saved preference exists", saved.containsKey(key), preferences.contains(key));
                if (saved.containsKey(key)) assertEquals(saved.get(key), preferences.getBoolean(key, false));
            }
        }
    }

    @Test public void phoneRowsAreReadableAndSwitchTouchesReturnToTheSameConversationView() throws Exception {
        WebView original = ui.web();
        ui.openPermissions();
        CapabilityCatalog catalog = new CapabilityCatalog(context);
        for (String id : new String[]{"diagnostics", "privacy"}) {
            JSONObject group = catalog.group(id);
            String key = group.getString("preference");
            markRow(key);
            String row = row(key);
            revealWithTouch(row + " .droy-row-title");
            assertCopy(row, group.getString("label"), group.getString("description"));
            revealWithTouch(toggle(key));
            JSONObject target = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote("[data-droy-preference=" + key + "]")
                + "),r=e.getBoundingClientRect(),ratio=window.__droyTouchConfig?.dpToCss||0;"
                + "return {widthDp:ratio?r.width/ratio:0,heightDp:ratio?r.height/ratio:0}})()");
            assertTrue("The diagnostic permission switch has no 48 dp touch target: " + target,
                target.getDouble("widthDp") >= 47.5 && target.getDouble("heightDp") >= 47.5);
            capture(row, key);
        }
        String key = "allowDiagnostics";
        boolean originalValue = checked(key);
        android.graphics.Rect touchArea = ui.visibleWebBounds("[data-droy-preference=" + key + "]");
        ui.tapAt(touchArea.centerX(), touchArea.top + 4);
        DeviceUi.await("The actual diagnostic switch touch was not saved", 5000,
            () -> preferences.contains(key) && preferences.getBoolean(key, originalValue) != originalValue && checked(key) != originalValue);
        capture(row(key), "diagnostics-switched");
        ui.tap(toggle(key));
        DeviceUi.await("The actual diagnostic switch touch did not restore its value", 5000,
            () -> preferences.getBoolean(key, !originalValue) == originalValue && checked(key) == originalValue);

        String status = "[data-droy-permission-status=diagnostics]";
        ui.evaluate("document.querySelector(" + JSONObject.quote(status)
            + ").closest('.droy-system-row').setAttribute('data-test-phone-diagnostic-status','')");
        revealWithTouch(status);
        JSONObject system = new SystemDiagnosticsTools(context).capabilities().getJSONObject("privacy_usage");
        String expected = system.getBoolean("available") ? "已配置"
            : "usage_permission_required".equals(system.optString("reason")) ? "请开启使用情况访问"
            : "待配置，请通过电脑端权限配置工具授权";
        assertCopy("[data-test-phone-diagnostic-status]", "系统诊断读取", expected);
        assertEquals(expected, ui.evaluate("document.querySelector(" + JSONObject.quote(status) + ").textContent.trim()"));
        capture("[data-test-phone-diagnostic-status]", "system-diagnostics-status");
        ui.pressBack();
        DeviceUi.await("System Back did not close the phone permission settings", 5000,
            () -> !ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        assertSame("Returning from settings recreated the conversation WebView", original, ui.web());
        assertTrue("Returning from settings left the conversation unusable", ui.nativeUiReady());
    }

    private void markRow(String key) throws Exception {
        ui.evaluate("document.querySelector(" + JSONObject.quote("[data-droy-preference=" + key + "]")
            + ").closest('.droy-row').setAttribute('data-test-phone-diagnostic-row'," + JSONObject.quote(key) + ")");
    }
    private static String row(String key) { return "[data-test-phone-diagnostic-row=" + key + "]"; }
    private static String toggle(String key) { return "[data-droy-preference=" + key + "] [role=switch]"; }
    private boolean checked(String key) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(toggle(key)) + ").getAttribute('aria-checked')==='true'");
    }

    private void revealWithTouch(String selector) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            JSONObject target = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector)
                + "),r=e.getBoundingClientRect(),p=document.querySelector('[data-droy-settings-options]').getBoundingClientRect();"
                + "return {visible:r.top>=p.top+8&&r.bottom<=p.bottom-8,before:r.top<p.top+8}})()");
            if (target.getBoolean("visible")) { ui.visibleWebBounds(selector); return; }
            Rect options = ui.webBounds("[data-droy-settings-options]");
            int top = options.top + options.height() / 4;
            int bottom = options.bottom - options.height() / 4;
            boolean before = target.getBoolean("before");
            ui.swipe(options.centerX(), before ? top : bottom, options.centerX(), before ? bottom : top, 280);
            instrumentation.waitForIdleSync();
        }
        fail("Actual touch scrolling did not reveal " + selector);
    }

    private void assertCopy(String selector, String title, String description) throws Exception {
        JSONObject shape = (JSONObject) ui.evaluate("""
            (()=>{const row=document.querySelector(%s),r=row.getBoundingClientRect(),toggle=row.querySelector('[role=switch]')?.getBoundingClientRect();
              let fits=true;for(const e of row.querySelectorAll('.droy-row-title,.droy-description,.droy-status')){
                const walk=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);while(walk.nextNode()){const node=walk.currentNode;if(!node.textContent.trim())continue;
                  const range=document.createRange();range.selectNodeContents(node);for(const x of range.getClientRects()){
                    if(x.width<.1||x.height<.1)continue;if(x.left<r.left-1||x.right>r.right+1||x.top<r.top-1||x.bottom>r.bottom+1)fits=false;
                    if(toggle&&x.right>toggle.left+1&&x.left<toggle.right-1&&x.bottom>toggle.top+1&&x.top<toggle.bottom-1)fits=false;
                    for(let a=node.parentElement;a&&a!==row;a=a.parentElement){const s=getComputedStyle(a),b=a.getBoundingClientRect();
                      if(/hidden|clip/.test(s.overflowX)&&(x.left<b.left-1||x.right>b.right+1))fits=false;
                      if(/hidden|clip/.test(s.overflowY)&&(x.top<b.top-1||x.bottom>b.bottom+1))fits=false;}}}}
              return {fits,title:row.querySelector('.droy-row-title').textContent.trim(),description:row.querySelector('.droy-description,.droy-status').textContent.trim(),
                overflow:[document.documentElement,document.querySelector('[data-droy-settings-options]'),row].some(e=>e.scrollWidth>e.clientWidth+1)};})()
            """.formatted(JSONObject.quote(selector)));
        assertEquals(title, shape.getString("title"));
        assertEquals(description, shape.getString("description"));
        assertTrue("Phone diagnostic copy is clipped or overlaps its switch: " + shape, shape.getBoolean("fits"));
        assertFalse("Phone diagnostic settings has horizontal overflow: " + shape, shape.getBoolean("overflow"));
    }

    private void capture(String selector, String name) throws Exception {
        DiagnosticsSettingsDeviceTest.captureSettingsRow(instrumentation, context, ui, selector,
            "diagnostics-phone-settings-evidence", name, true);
    }
}
