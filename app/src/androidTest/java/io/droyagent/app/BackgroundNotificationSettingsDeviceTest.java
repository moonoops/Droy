package io.droyagent.app;

import android.app.Instrumentation;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** The shipped switches and expanded settings, exercised with actual touches and fictional rules. */
@RunWith(AndroidJUnit4.class)
public final class BackgroundNotificationSettingsDeviceTest {
    private static final String BACKGROUND = "[data-droy-general='backgroundRuntimeEnabled'] [role='switch']";
    private static final String MANAGEMENT = "[data-droy-general='notificationManagementEnabled'] > .droy-notification-header [role='switch']";
    private static final String DETAILS = "[data-droy-notification-details]";
    private static final String CLOUD = ".droy-notification-body > .droy-notification-header [role='switch']";
    private static final String RULE = "notification-ui-fixture";
    private static final String PACKAGE = InstrumentationRegistry.getInstrumentation().getContext().getPackageName();
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private NotificationTestState saved;
    private String originalLanguage;
    private boolean languageChanged;
    private String language;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop existing tasks before notification settings tests", RuntimeService.canManageHistory());
        saved = new NotificationTestState(context); saved.prepare();
        String requestedLanguage = InstrumentationRegistry.getArguments().getString("notificationLocale");
        if (requestedLanguage != null) {
            assertTrue("Notification language fixtures require the isolated emulator home", NotificationTestState.emulator());
            assertTrue("notificationLocale must be zh or en", requestedLanguage.equals("zh") || requestedLanguage.equals("en"));
            originalLanguage = context.getSharedPreferences("product", Context.MODE_PRIVATE).getString("languagePreference", null);
            languageChanged = true;
            NativeLocale.adopt(requestedLanguage);
        }
        language = NativeLocale.active();
        NotificationManagement.get(context).request(new JSONObject().put("operation", "upsertRule").put("rule", new JSONObject()
                .put("id", RULE).put("packageName", PACKAGE)
                .put("instruction", "测试通知规则：只有闪送已送达时提醒，其他消息静默收纳；这是一条用于检查窄屏与放大字体换行的虚构规则。")
                .put("defaultAction", "collect").put("entries", new JSONArray().put(new JSONObject()
                    .put("containsAll", new JSONArray().put("闪送").put("已送达")).put("action", "notify")))));
        NotificationManagement.get(context).request(new JSONObject().put("operation", "configure")
                .put("enabled", true).put("cloudAnalysisEnabled", false).put("reminderMinutes", 5).put("exceptions", saved.allowOthers(PACKAGE)));
        boolean coldEntry = "true".equals(InstrumentationRegistry.getArguments().getString("notificationColdEntry"));
        Intent launch = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (coldEntry) launch.putExtra("notificationManagement", true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity = (MainActivity) instrumentation.startActivitySync(launch);
        ui = new DeviceUi(instrumentation, activity); ui.awaitPage(); dismissAccountWelcome();
        DeviceUi.await("The notification fixture language did not reach DSH", 10000,
                () -> ui.truth("document.documentElement.lang.startsWith(" + JSONObject.quote(language) + ")"));
        if (coldEntry) {
            try {
                DeviceUi.await("Cold notification entry did not open settings before the drawer was mounted", 10000,
                        () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')&&document.querySelector('[data-droy-notification-details]')?.open===true&&document.querySelector('[data-droy-notification-archive]')?.open===true"));
            } catch (AssertionError failure) {
                capture("cold-summary-entry-failure");
                throw new AssertionError(failure.getMessage() + "; route=" + coldEntryState(), failure);
            }
            awaitNotificationEntryVisible();
            capture("cold-summary-entry"); ui.pressBack();
            DeviceUi.await("Back from cold notification entry did not return to the conversation", 5000,
                    () -> ui.truth("!document.querySelector('[data-droy-settings-panel]')"));
            ui.awaitPage(); dismissAccountWelcome();
        }
        ui.hideKeyboard();
        DeviceUi.await("Notification settings window did not gain focus", 5000,
                () -> ui.nativeNavigationState().getBoolean("activityFocus"));
        instrumentation.getUiAutomation().waitForIdle(300, 5000);
        try {
            DeviceUi.await("The initial menu is covered by a dialog or animation", 5000,
                    () -> ui.truth("(()=>{const e=document.querySelector('[data-droy-menu]');"
                        + "if(!e)return false;const r=e.getBoundingClientRect();return document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('[data-droy-menu]')===e})()"));
        } catch (AssertionError failure) {
            capture("initial-menu-covered");
            throw new AssertionError(failure.getMessage() + "; route=" + coldEntryState(), failure);
        }
        ui.tap("[data-droy-menu]");
        try {
            DeviceUi.await("Settings drawer did not open", 5000,
                    () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        } catch (AssertionError failure) {
            capture("drawer-open-failure");
            throw new AssertionError(failure.getMessage() + "; native=" + ui.nativeNavigationState(), failure);
        }
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("Settings did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("New background settings did not become interactive", 10000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(BACKGROUND) + ")&&!document.querySelector(" + JSONObject.quote(BACKGROUND) + ").disabled"));
        Bundle arguments = InstrumentationRegistry.getArguments();
        if ("dark".equals(arguments.getString("layoutTheme")) || "true".equals(arguments.getString("layoutMaxTextFont"))) {
            assertTrue("Theme/font conditions require the isolated emulator home", NotificationTestState.emulator());
            DeviceUi.await("Original font/theme controls did not load", 10000,
                    () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')&&document.querySelectorAll('[data-droy-theme-choice]').length===3"));
            if ("true".equals(arguments.getString("layoutMaxTextFont"))) maximizeDshTextFont();
            if ("dark".equals(arguments.getString("layoutTheme"))) {
                ui.tap("[data-droy-theme-choice=dark]");
                DeviceUi.await("The actual theme control did not apply dark mode", 10000,
                        () -> ui.truth("document.querySelector('[data-droy-theme-choice=dark]').getAttribute('aria-pressed')==='true'&&document.body.hasAttribute('data-ds-dark-theme')"));
            }
        }
    }

    @After public void tearDown() throws Exception {
        if (saved == null) return;
        try {
            NotificationManagement.get(context).request(new JSONObject().put("operation", "configure").put("enabled", false));
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Notification settings test left the engine active", 15000, RuntimeService::canManageHistory);
        } finally {
            try { saved.restore(); }
            finally {
                if (languageChanged) {
                    android.content.SharedPreferences.Editor restore = context.getSharedPreferences("product", Context.MODE_PRIVATE).edit();
                    if (originalLanguage == null) restore.remove("languagePreference");
                    else restore.putString("languagePreference", originalLanguage);
                    assertTrue("Could not restore the original notification-test language", restore.commit());
                }
            }
        }
    }

    @Test public void defaultTakeoverUsesExceptionsAndKeepsBackgroundChoiceIndependent() throws Exception {
        android.webkit.WebView originalWeb = ui.web();
        assertFalse(checked(BACKGROUND)); assertTrue(checked(MANAGEMENT));
        scroll(BACKGROUND); assertLayout(); capture("management-on");
        set(BACKGROUND, "backgroundRuntimeEnabled", true);
        assertTrue("Background choice changed notification management", checked(MANAGEMENT));
        set(BACKGROUND, "backgroundRuntimeEnabled", false);
        set(MANAGEMENT, "notificationManagementEnabled", false);
        assertFalse("Notification choice changed the independent background switch", checked(BACKGROUND));
        set(MANAGEMENT, "notificationManagementEnabled", true);

        if (!ui.truth("document.querySelector('" + DETAILS + "').open")) ui.tap(DETAILS + " > summary");
        DeviceUi.await("Notification details did not expand", 5000, () -> ui.truth("document.querySelector('" + DETAILS + "').open"));
        assertFalse("Installed applications still appear on the primary settings page",
                ui.truth("!!document.querySelector('[data-droy-general=notificationManagementEnabled] [data-droy-notification-app]')"));
        assertLayout(); scroll(CLOUD); capture("details-top");
        assertFalse(checked(CLOUD));
        ui.tap(CLOUD);
        DeviceUi.await("Smart analysis choice was not saved", 10000,
                () -> NotificationManagement.get(context).state().getBoolean("cloudAnalysisEnabled") && checked(CLOUD)
                    && ui.truth("!document.querySelector(" + JSONObject.quote(CLOUD) + ").disabled"));
        ui.tap(CLOUD);
        DeviceUi.await("Smart analysis could not be turned off", 10000,
                () -> !NotificationManagement.get(context).state().getBoolean("cloudAnalysisEnabled") && !checked(CLOUD)
                    && ui.truth("!document.querySelector(" + JSONObject.quote(CLOUD) + ").disabled"));

        verifyReminderDelayControls();

        String entry = "[data-droy-action=openNotificationExceptions]";
        scroll(entry); ui.tap(entry);
        DeviceUi.await("Exception page did not open", 5000, () -> ui.truth("!!document.querySelector('[data-droy-notification-exceptions]')"));
        assertExceptionLayout(); capture("exceptions-top");
        String row = "[data-droy-notification-app='" + PACKAGE + "']";
        assertFalse("A default-managed application was presented as an exception", ui.truth("!!document.querySelector(" + JSONObject.quote(row) + ")"));
        String search = "[data-droy-notification-exceptions] input[aria-label=" + JSONObject.quote(NativeLocale.text("搜索应用", "Search apps")) + "]";
        ui.tap(search); instrumentation.sendStringSync(PACKAGE);
        DeviceUi.await("Searching installed applications did not reveal the fixture", 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(search) + ").value===" + JSONObject.quote(PACKAGE)
                    + "&&!!document.querySelector(" + JSONObject.quote(row) + ")"));
        ui.hideKeyboard(); awaitKeyboardClosed(); assertExceptionLayout(); capture("exceptions-search");
        setAppMode(row, "allow"); setAppMode(row, "block"); setAppMode(row, "managed");
        assertFalse("Restoring intelligent management kept an exception", hasException(PACKAGE));
        assertTrue("The exception page changed the main management switch", NotificationManagement.enabled(context));
        ui.showKeyboard(search);
        DeviceUi.await("The search keyboard inset did not reach the page", 5000,
                () -> ui.truth("parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))>0"));
        capture("exceptions-keyboard"); assertExceptionLayout();
        setAppModeWithKeyboard(row, "allow");
        capture("exceptions-keyboard-controls");
        ui.hideKeyboard(); awaitKeyboardClosed();
        setAppMode(row, "managed");
        ui.showKeyboard(search);
        DeviceUi.await("The search keyboard did not reopen for Back navigation", 5000,
                () -> ui.truth("parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))>0"));
        ui.pressBack();
        DeviceUi.await("Back did not dismiss the search keyboard", 5000, () -> !ui.keyboardVisible());
        awaitKeyboardClosed();
        assertTrue("Dismissing the keyboard also closed the exception page", ui.truth("!!document.querySelector('[data-droy-notification-exceptions]')"));
        ui.pressBack();
        DeviceUi.await("Back did not return from exceptions to notification settings", 5000,
                () -> ui.truth("!document.querySelector('[data-droy-notification-exceptions]')&&!!document.querySelector('[data-droy-settings-panel]')"));
        assertSame("The exception page replaced the original WebView", originalWeb, ui.web());

        String remove = "[data-droy-notification-rule-delete='" + RULE + "']";
        scroll(remove); assertLayout(); capture("rule-and-archive");
        if (!ui.truth("document.querySelector('[data-droy-notification-archive]').open")) ui.tap("[data-droy-notification-archive] > summary");
        DeviceUi.await("Notification archive did not expand", 5000,
                () -> ui.truth("document.querySelector('[data-droy-notification-archive]').open"));
        assertTrue("Empty archive is not visible", ui.truth("document.querySelector('[data-droy-notification-archive]').textContent.includes(" + JSONObject.quote(NativeLocale.text("暂无收纳通知", "No archived notifications")) + ")"));
        assertFalse("Unwanted acknowledgement controls appeared", ui.truth("(()=>{const e=document.querySelector('" + DETAILS
                + "');return [...e.querySelectorAll('button')].some(b=>/已知晓|稍后提醒|acknowledge|snooze/i.test(b.textContent))})()"));
        assertLayout(); capture("archive-empty"); ui.tap(remove);
        DeviceUi.await("Rule deletion touch did not persist", 10000, () -> {
            JSONArray rules = NotificationManagement.get(context).state().getJSONArray("rules");
            for (int i = 0; i < rules.length(); i++) if (RULE.equals(rules.getJSONObject(i).getString("id"))) return false;
            return !ui.truth("!!document.querySelector(" + JSONObject.quote(remove) + ")");
        });
        set(MANAGEMENT, "notificationManagementEnabled", false);
        scroll(MANAGEMENT); capture("management-off"); assertLayout(); ui.hideKeyboard(); ui.pressBack();
        DeviceUi.await("One system Back did not return from settings to the conversation", 5000,
                () -> ui.truth("!document.querySelector('[data-droy-settings-panel]')&&!document.querySelector('[data-droy-drawer-open=true]')"));
        assertSame("Returning from notification settings replaced the conversation WebView", originalWeb, ui.web());
        instrumentation.runOnMainSync(() -> context.startActivity(new Intent(context, MainActivity.class)
                .putExtra("notificationManagement", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        DeviceUi.await("The notification summary entry did not reopen its settings and archive", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')&&document.querySelector('[data-droy-notification-details]')?.open===true&&document.querySelector('[data-droy-notification-archive]')?.open===true"));
        assertSame("The summary entry replaced the conversation WebView", originalWeb, ui.web());
        awaitNotificationEntryVisible(); capture("summary-entry"); ui.pressBack();
        DeviceUi.await("Back from the summary entry did not return to the conversation", 5000,
                () -> ui.truth("!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void verifyReminderDelayControls() throws Exception {
        String five = "[data-droy-notification-reminder='5']", ten = "[data-droy-notification-reminder='10']";
        String heading = "#droy-notification-reminder-label";
        DeviceUi.await("The reminder choices did not mount", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(ten) + ")"));
        assertTrue("The reminder heading did not follow the selected language", ui.truth("document.querySelector("
                + JSONObject.quote(heading) + ").textContent===" + JSONObject.quote(NativeLocale.text("重要通知提醒", "Important notification reminders"))));
        assertEquals("The fixture must begin at five minutes", 5, NotificationManagement.get(context).state().getInt("reminderMinutes"));
        scroll(heading); assertLayout(); capture("reminder-description");
        scroll(ten); ui.tap(ten);
        awaitReminderChoice(10); assertLayout(); capture("reminder-ten-minutes");
        // Switching real settings sections unmounts the notification form and reloads its saved state.
        ui.tap("[data-droy-settings-section=droy-permissions]");
        DeviceUi.await("Permissions section did not replace General settings", 5000,
                () -> ui.truth("!document.querySelector('[data-droy-notification-reminder]')&&!!document.querySelector('[data-droy-permissions]')"));
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("Notification settings did not remount", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(DETAILS) + ")"));
        if (!ui.truth("document.querySelector('" + DETAILS + "').open")) ui.tap(DETAILS + " > summary");
        awaitReminderChoice(10);
        scroll(ten); assertLayout(); capture("reminder-ten-restored");
        scroll(five); ui.tap(five); awaitReminderChoice(5);
        assertLayout(); capture("reminder-five-minutes");
    }

    private void awaitReminderChoice(int minutes) throws Exception {
        String selected = "[data-droy-notification-reminder='" + minutes + "']";
        String other = "[data-droy-notification-reminder='" + (minutes == 5 ? 10 : 5) + "']";
        DeviceUi.await("The reminder delay touch was not saved and reflected: " + minutes, 10000,
                () -> NotificationManagement.get(context).state().getInt("reminderMinutes") == minutes
                    && ui.truth("document.querySelector(" + JSONObject.quote(selected) + ")?.getAttribute('aria-pressed')==='true'"
                        + "&&document.querySelector(" + JSONObject.quote(other) + ")?.getAttribute('aria-pressed')==='false'"
                        + "&&!document.querySelector(" + JSONObject.quote(selected) + ").disabled"));
    }

    private void setAppMode(String row, String mode) throws Exception {
        String selector = row + " [data-droy-notification-mode='" + mode + "']";
        ui.tap(selector);
        DeviceUi.await("The real exception action did not persist: " + mode, 10000, () -> {
            JSONArray apps = NotificationManagement.get(context).state().getJSONArray("apps");
            for (int i = 0; i < apps.length(); i++) if (PACKAGE.equals(apps.getJSONObject(i).getString("packageName")))
                return mode.equals(apps.getJSONObject(i).getString("mode"))
                    && ui.truth("document.querySelector(" + JSONObject.quote(selector) + ").getAttribute('aria-pressed')==='true'&&!document.querySelector(" + JSONObject.quote(selector) + ").disabled");
            return false;
        });
    }

    private boolean hasException(String packageName) throws Exception {
        JSONArray exceptions = NotificationManagement.get(context).state().getJSONArray("exceptions");
        for (int i = 0; i < exceptions.length(); i++) if (packageName.equals(exceptions.getJSONObject(i).getString("packageName"))) return true;
        return false;
    }

    private void setAppModeWithKeyboard(String row, String mode) throws Exception {
        String selector = row + " [data-droy-notification-mode='" + mode + "']";
        scroll(selector);
        assertTrue("The exception control test closed the keyboard before " + mode, ui.keyboardVisible());
        assertTrue("The exception control cannot scroll above the keyboard", ui.truth("(()=>{const e=document.querySelector("
                + JSONObject.quote(selector) + "),r=e.getBoundingClientRect(),body=e.closest('[data-droy-notification-exceptions]').getBoundingClientRect(),"
                + "ime=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0;"
                + "return r.top>=body.top-1&&r.bottom<=Math.min(body.bottom,innerHeight-ime)+1})()"));
        setAppMode(row, mode);
    }

    private JSONObject coldEntryState() throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const panel=document.querySelector('[data-droy-settings-panel]');return {"
                + "readyState:document.readyState,mobileApi:typeof window.DroyMobile?.openNotificationManagement,"
                + "menu:!!document.querySelector('[data-droy-menu]'),drawer:!!document.querySelector('[data-droy-drawer-open=true]'),"
                + "sidebar:!!document.querySelector('[data-droy-sidebar]'),settingsLauncher:!!document.querySelector('[data-droy-sidebar] button[aria-haspopup=menu]'),"
                + "panel:!!panel,details:document.querySelector('[data-droy-notification-details]')?.open,"
                + "archive:document.querySelector('[data-droy-notification-archive]')?.open,"
                + "dialogTitles:[...document.querySelectorAll('[role=dialog] h2')].filter(e=>e.getClientRects().length).map(e=>e.textContent.trim()),"
                + "modalCount:document.querySelectorAll('[role=dialog]').length}})()");
        instrumentation.runOnMainSync(() -> {
            try {
                for (String name : new String[]{"uiReady", "pendingNotificationManagement"}) {
                    java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name); field.setAccessible(true);
                    result.put(name, field.getBoolean(activity));
                }
            } catch (ReflectiveOperationException | org.json.JSONException error) { throw new AssertionError(error); }
        });
        return result.put("native", ui.nativeNavigationState());
    }

    private void awaitKeyboardClosed() throws Exception {
        AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        int originalFlags = info.flags;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        try {
            // Insets report hidden when dismissal starts; the input window can still intercept the next touch.
            DeviceUi.await("The dismissing keyboard still covers exception controls", 5000, () -> {
                if (ui.keyboardVisible()) return false;
                for (AccessibilityWindowInfo window : instrumentation.getUiAutomation().getWindows())
                    if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false;
                return true;
            });
        } finally {
            info.flags = originalFlags;
            instrumentation.getUiAutomation().setServiceInfo(info);
        }
    }

    private void assertExceptionLayout() throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const dialog=document.querySelector('.droy-notification-exceptions[role=dialog]'),body=document.querySelector('[data-droy-notification-exceptions]');"
                + "if(!dialog||!body)return {failures:['missing-page']};const r=dialog.getBoundingClientRect(),failures=[];"
                + "if(Math.abs(r.left)>1||Math.abs(r.top)>1||Math.abs(r.width-innerWidth)>1||Math.abs(r.height-innerHeight)>1)failures.push('not-full-screen');"
                + "if(body.scrollWidth>body.clientWidth+1)failures.push('page-overflow');"
                + "const ime=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'))||0,search=body.querySelector('input');"
                + "if(ime>0&&search.getBoundingClientRect().bottom>innerHeight-ime+1)failures.push('search-under-keyboard');"
                + "for(const e of dialog.querySelectorAll('button,input,h2,.droy-row-title')){if(!e.getClientRects().length)continue;const b=e.getBoundingClientRect();"
                + "if(b.width<=0||b.height<=0||b.left<r.left-1||b.right>r.right+1)failures.push('control-outside-page');"
                + "if(e.matches('button,input')&&(b.height<47||b.width<47))failures.push({kind:'small-touch-target',tag:e.tagName,label:e.getAttribute('aria-label'),width:b.width,height:b.height});"
                + "const range=document.createRange();range.selectNodeContents(e);for(const t of range.getClientRects())if(t.width>.1&&(t.left<b.left-1||t.right>b.right+1))failures.push('text-clipped')}"
                + "const box=e=>{if(!e)return null;const b=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,className:e.className,"
                + "top:b.top,bottom:b.bottom,left:b.left,width:b.width,height:b.height,clientHeight:e.clientHeight,scrollHeight:e.scrollHeight,scrollTop:e.scrollTop,"
                + "padding:s.padding,margin:s.margin,display:s.display,flex:s.flex,minHeight:s.minHeight,maxHeight:s.maxHeight,overflow:s.overflow}};"
                + "const ancestors=[];for(let e=body.parentElement;e;e=e.parentElement){ancestors.push(box(e));if(e===dialog)break}"
                + "return {failures,cssWidth:innerWidth,cssHeight:innerHeight,ime,imeTop:innerHeight-ime,visualViewport:{top:visualViewport?.offsetTop,height:visualViewport?.height},"
                + "search:box(search),body:box(body),header:box(dialog.querySelector('h2')?.parentElement),ancestors,dialog:{left:r.left,top:r.top,width:r.width,height:r.height}}})()");
        if (result.getJSONArray("failures").length()>0) capture("exceptions-layout-failure");
        assertEquals("Exception page layout failed: " + result, 0, result.getJSONArray("failures").length());
    }

    private void set(String selector, String key, boolean value) throws Exception {
        assertNotEquals("The fixture requires a real switch change", value, checked(selector));
        ui.tap(selector);
        DeviceUi.await("Setting touch did not persist: " + key, 10000,
                () -> context.getSharedPreferences("product", Context.MODE_PRIVATE).getBoolean(key, !value) == value
                    && checked(selector) == value && ui.truth("!document.querySelector(" + JSONObject.quote(selector) + ").disabled"));
    }
    private boolean checked(String selector) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(selector) + ")?.getAttribute('aria-checked')==='true'");
    }
    private void scroll(String selector) throws Exception {
        ui.visibleWebBounds(selector);
    }

    private void dismissAccountWelcome() throws Exception {
        long[] quietSince = {0};
        DeviceUi.await("The fresh-home account welcome did not settle", 10000, () -> {
            boolean clicked = ui.truth("(()=>{for(const heading of document.querySelectorAll('[role=dialog] h2')){"
                    + "if(!['开始使用','Get started'].includes(heading.textContent.trim()))continue;const dialog=heading.closest('[role=dialog]');"
                    + "if(!dialog.getClientRects().length)continue;const close=dialog.querySelector('button[aria-label=关闭],button[aria-label=Close]');"
                    + "if(close&&!close.disabled){close.click();return true}}return false})()");
            if (clicked) { quietSince[0] = 0; return false; }
            if (quietSince[0] == 0) quietSince[0] = android.os.SystemClock.elapsedRealtime();
            return android.os.SystemClock.elapsedRealtime() - quietSince[0] >= 600;
        });
    }

    private void awaitNotificationEntryVisible() throws Exception {
        DeviceUi.await("The notification entry did not reveal its archive within the settings pane", 5000,
                () -> ui.truth("(()=>{const summary=document.querySelector('[data-droy-notification-archive] > summary'),options=document.querySelector('[data-droy-settings-options]'),"
                    + "back=document.querySelector('[data-droy-settings-back]'),panel=document.querySelector('[data-droy-settings-panel]');if(!summary||!options||!back||!panel)return false;"
                    + "const r=summary.getBoundingClientRect(),o=options.getBoundingClientRect(),b=back.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                    + "return r.top>=o.top-1&&r.bottom<=Math.min(o.bottom,innerHeight)+1&&b.top>=0&&b.bottom<=o.top+1&&scrollY===0&&panel.scrollTop===0&&!!hit&&(summary===hit||summary.contains(hit))})()"));
    }

    private void maximizeDshTextFont() throws Exception {
        if (!ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'")) {
            ui.tap("[data-droy-font-expander]");
            DeviceUi.await("The original font controls did not expand", 5000,
                    () -> ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'"));
        }
        String increase = "[data-droy-font-row=size][data-droy-font-role=text] [data-droy-font-step=increase]";
        for (int i = 0; i < 60 && !ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"); i++) {
            String previous = String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"));
            ui.tap(increase);
            DeviceUi.await("The original text-size control did not change the real font", 5000,
                    () -> !previous.equals(String.valueOf(ui.evaluate("getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim()"))));
        }
        assertTrue("The actual DSH text-size control did not reach its supported maximum", ui.truth("document.querySelector(" + JSONObject.quote(increase) + ").disabled"));
    }

    private void assertLayout() throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const roots=[document.querySelector('[data-droy-general=backgroundRuntimeEnabled]'),document.querySelector('[data-droy-general=notificationManagementEnabled]')];"
                + "const failures=[];for(const root of roots){const rr=root.getBoundingClientRect();if(root.scrollWidth>root.clientWidth+1)failures.push('row-overflow');"
                + "for(const e of root.querySelectorAll('button,input,[role=switch],summary,.droy-row-title,.droy-description,.droy-status')){if(!e.getClientRects().length)continue;"
                + "let collapsed=false;for(let a=e.parentElement;a&&a!==root;a=a.parentElement)if(a.matches('details:not([open])')&&!a.querySelector(':scope > summary')?.contains(e)){collapsed=true;break}if(collapsed)continue;const b=e.getBoundingClientRect();"
                + "if(b.width<=0||b.height<=0||b.left<rr.left-1||b.right>rr.right+1)failures.push('control-outside-row');"
                + "if(e.matches('button,[role=switch],summary')&&b.height<47)failures.push({kind:'small-touch-target',tag:e.tagName,role:e.getAttribute('role'),label:e.getAttribute('aria-label'),height:b.height,width:b.width});"
                + "if(e.matches('input'))continue;const walk=document.createTreeWalker(e,NodeFilter.SHOW_TEXT);let n;while(n=walk.nextNode()){if(!n.textContent.trim()||n.parentElement.closest('[aria-hidden=true]'))continue;"
                + "const range=document.createRange();range.selectNodeContents(n);for(const r of range.getClientRects()){if(r.width<.1||r.height<.1)continue;"
                + "if(r.left<b.left-1||r.right>b.right+1||r.top<b.top-1||r.bottom>b.bottom+1)failures.push('text-clipped');"
                + "for(let a=n.parentElement;a&&a!==root;a=a.parentElement){const style=getComputedStyle(a),ar=a.getBoundingClientRect();"
                + "if(/hidden|clip/.test(style.overflowX)&&(r.left<ar.left-1||r.right>ar.right+1))failures.push('ancestor-clips-text');}}}}}"
                + "const options=document.querySelector('[data-droy-settings-options]');if(options&&options.scrollWidth>options.clientWidth+1)failures.push('settings-overflow');"
                + "return {failures,cssWidth:innerWidth,cssHeight:innerHeight}})()");
        if (result.getJSONArray("failures").length() > 0) capture("layout-failure");
        assertEquals("Notification settings layout failed: " + result, 0, result.getJSONArray("failures").length());
    }

    private void capture(String stage) throws Exception {
        ui.evaluate("window.__droyNotificationPainted=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__droyNotificationPainted=true))");
        DeviceUi.await("Notification settings did not finish painting", 5000, () -> ui.truth("window.__droyNotificationPainted"));
        instrumentation.waitForIdleSync();
        Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        try {
            File directory = new File(context.getExternalFilesDir(null), "notification-ui-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            String label = InstrumentationRegistry.getArguments().getString("notificationEvidenceLabel", "settings").replaceAll("[^A-Za-z0-9_-]", "_");
            JSONObject conditions = (JSONObject) ui.evaluate("(()=>{const ancestors=[];for(let e=document.querySelector('[data-droy-settings-options]');e;e=e.parentElement){const r=e.getBoundingClientRect();"
                    + "ancestors.push({tag:e.tagName,attrs:[...e.attributes].map(a=>a.name).filter(a=>a.startsWith('data-droy-settings')),top:r.top,height:r.height,scrollTop:e.scrollTop,scrollHeight:e.scrollHeight,clientHeight:e.clientHeight})}"
                    + "return {theme:document.body.hasAttribute('data-ds-dark-theme')?'dark':'light',cssWidth:innerWidth,cssHeight:innerHeight,dshTextFont:getComputedStyle(document.body).getPropertyValue('--dsh-content-font-size').trim(),windowScroll:scrollY,ancestors}})()");
            conditions.put("systemFontScale", activity.getResources().getConfiguration().fontScale);
            conditions.put("language", language);
            File file = File.createTempFile(label + "-" + stage + "-", ".png", directory);
            try (FileOutputStream stream = new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_NOTIFICATION_EVIDENCE " + file.getAbsolutePath()
                    + "\nDROY_NOTIFICATION_CONDITIONS " + conditions + "\n"); instrumentation.sendStatus(2, status);
        } finally { image.recycle(); }
    }
}
