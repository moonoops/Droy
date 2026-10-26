package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.ViewConfiguration;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Trusted Android touches against real DSH rows; isolated fictional history, emulator only. */
@RunWith(AndroidJUnit4.class)
public final class TouchNavigationDeviceTest {
    private static final int SESSION_COUNT = 28;
    private static final String FIRST = "droy-touch-27";
    private static final String OTHER = "droy-touch-26";
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private File runtime;
    private File isolatedHome;
    private File preservedHome;
    private boolean homeOwned;
    private boolean hadOriginalHome;
    private Map<String, String> originalFiles;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        // This class temporarily swaps only the DSH home. Never permit it on a
        // personal phone, even when the instrumentation command named this class.
        assertTrue("Touch history fixtures require a dedicated emulator",
                Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                        || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop the existing engine before touch navigation tests", RuntimeService.canManageHistory());
        RuntimeInstaller.installCertificate(context);
        runtime = RuntimeInstaller.install(context);
        isolatedHome = new File(context.getFilesDir(), "dsh-home");
        preservedHome = new File(context.getFilesDir(), "droy-touch-home-backup-" + UUID.randomUUID());
        hadOriginalHome = isolatedHome.exists();
        originalFiles = snapshot(isolatedHome.toPath());
        if (hadOriginalHome) assertTrue("Could not preserve the original DSH home", isolatedHome.renameTo(preservedHome));
        homeOwned = true;
        assertTrue("Could not create the isolated touch fixture home", isolatedHome.mkdirs());
        JSONObject seeded = runFixtureProfile(true);
        assertEquals("Fixture sessions must be durably created through DSH", SESSION_COUNT, seeded.getInt("sessions"));
        assertEquals("Every fixture must belong to the real workspace", SESSION_COUNT, seeded.getInt("accounted"));
        assertEquals("Workspace menu fixtures require exactly one real workspace", 1, seeded.getInt("workspaces"));
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        DeviceUi.await("The standalone touch controller did not attach", 10000,
                () -> ui.truth("typeof window.DroyTouch?.closeExpandedRow === 'function' && !!window.__droyTouchConfig"));
        ui.evaluate("(()=>{window.__droyTouchProbe={count:0};document.addEventListener('pointerdown',event=>{"
                + "window.__droyTouchProbe={count:window.__droyTouchProbe.count+1,pointerType:event.pointerType,isTrusted:event.isTrusted}"
                + "},{capture:true})})()");
        ui.hideKeyboard();
    }

    @After public void tearDown() throws Exception {
        if (!homeOwned) return;
        try {
            if (activity != null) {
                try { releaseFixtureInput(); }
                finally {
                    instrumentation.runOnMainSync(activity::finish);
                    DeviceUi.await("The touch fixture Activity did not finish", 10000, () -> {
                        boolean[] destroyed = {false};
                        instrumentation.runOnMainSync(() -> destroyed[0] = activity.isDestroyed());
                        return destroyed[0];
                    });
                }
            }
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Touch fixture cleanup left a runtime alive", 15000, RuntimeService::canManageHistory);
            if (runtime != null && new File(isolatedHome, "profiles/mobile/package.json").isFile()) {
                JSONObject stored = runFixtureProfile(false);
                assertEquals("Touch operations must not delete fictional session contents", SESSION_COUNT, stored.getInt("sessions"));
                assertEquals("Touch operations must not submit another turn", SESSION_COUNT, stored.getInt("turns"));
                assertEquals("No touch test may cause model requests or tool actions", 0, stored.getInt("modelOrToolEvents"));
            }
        } finally {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Touch fixture cleanup left a runtime alive", 15000, RuntimeService::canManageHistory);
            // Never touch the preserved tree. Refuse restoration while a live
            // process could still hold the isolated home open.
            assertTrue("Runtime still owns the isolated home; original retained at its backup path", RuntimeService.canManageHistory());
            HistoryData.deleteTree(isolatedHome.toPath());
            if (hadOriginalHome) assertTrue("Could not restore the preserved DSH home", preservedHome.renameTo(isolatedHome));
            assertEquals("Original DSH home changed during isolated touch tests", originalFiles, snapshot(isolatedHome.toPath()));
            assertFalse("The original home backup should have been restored", preservedHome.exists());
            homeOwned = false;
        }
    }

    private void releaseFixtureInput() throws Exception {
        if (ui == null) return;
        WebView view = ui.web();
        if (view == null) return;
        ui.evaluate("document.activeElement?.blur()");
        InputMethodManager input = activity.getSystemService(InputMethodManager.class);
        assertNotNull("The fixture cannot verify input cleanup without Android's input manager", input);
        instrumentation.runOnMainSync(() -> {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            View focusSink = new View(activity);
            focusSink.setTag("droy-test-input-cleanup");
            focusSink.setFocusable(true);
            focusSink.setFocusableInTouchMode(true);
            focusSink.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            decor.addView(focusSink, new ViewGroup.LayoutParams(1, 1));
            view.clearFocus();
            assertTrue("The disposing fixture could not move native focus away from its WebView", focusSink.requestFocus());
            input.hideSoftInputFromWindow(view.getWindowToken(), 0);
        });
        ui.hideKeyboard();
        instrumentation.waitForIdleSync();
        DeviceUi.await("Android retained the disposing fixture WebView's active input connection", 5000, () -> {
            boolean[] inactive = {false};
            instrumentation.runOnMainSync(() -> inactive[0] = !input.isActive(view));
            return inactive[0];
        });
        // The focus sink stays attached until Activity destruction; removing it
        // here could return native focus to the WebView before finish completes.
    }

    @Test public void drawerSwipesSnapBackOrCloseAndKeepAnExplicitCloseTarget() throws Exception {
        traceTouchFlow("drawer", () -> {
        openDrawer();
        JSONObject config = (JSONObject) ui.evaluate("window.__droyTouchConfig");
        assertEquals("Long press must follow the user's Android setting", ViewConfiguration.getLongPressTimeout(), config.getInt("longPressMs"));
        assertTrue("Android touch slop must be delivered in CSS pixels", config.getDouble("touchSlopCssPx") > 0);
        float density = context.getResources().getDisplayMetrics().density;
        float[] headerStart = drawerHeaderBlankPoint();
        ui.swipe(headerStart[0], headerStart[1], headerStart[0] - 16 * density, headerStart[1], 650);
        SystemClock.sleep(350);
        assertTrue("A short slow drag must spring back to the open drawer", drawerOpen());
        assertTrue("Short drag left the drawer partially translated", ui.truth("(()=>{const r=document.querySelector('[data-droy-sidebar]').getBoundingClientRect();return Math.abs(r.left)<1})()"));
        headerStart = drawerHeaderBlankPoint();
        Rect headerDrawer = ui.webBounds("[data-droy-sidebar]");
        ui.swipe(headerStart[0], headerStart[1], headerDrawer.left + 12 * density, headerStart[1], 380);
        awaitDrawer(false);
        openDrawer();
        Rect drawer = ui.webBounds("[data-droy-sidebar]");
        Rect backdrop = ui.webBounds("[data-droy-backdrop]");
        float backdropX = Math.min(backdrop.right - 20 * density, drawer.right + 24 * density);
        ui.swipe(backdropX, backdrop.centerY(), Math.max(24 * density, backdropX - 180 * density), backdrop.centerY(), 400);
        awaitDrawer(false);
        openDrawer();
        assertTouchTarget("[data-droy-drawer-close]");
        ui.tap("[data-droy-drawer-close]");
        awaitDrawer(false);
        });
    }

    @Test public void longPressAndVisibleMoreOpenTheRealMenuWithoutSelectingTheRow() throws Exception {
        selectFixture(FIRST);
        openDrawer();
        assertEquals(FIRST, selectedSession());
        assertTouchTarget(row(OTHER) + " [data-droy-session-menu]");
        assertTrue("The More action must be visible without hovering", ui.truth("(()=>{const e=document.querySelector(" + JSONObject.quote(row(OTHER) + " [data-droy-session-menu]")
                + ");return !!e&&getComputedStyle(e).display!=='none'&&getComputedStyle(e).visibility==='visible'&&e.getBoundingClientRect().width>0})()"));
        assertEquals("An ordinary touch row must show only More, without desktop archive/pin buttons", 1,
                ((Number) ui.evaluate("(()=>{const actions=document.querySelector(" + JSONObject.quote(row(OTHER) + " [data-droy-row-actions]")
                        + ");return [...actions.querySelectorAll('button')].filter(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                        + "return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'}).length})()")).intValue());
        assertTrue("Desktop archive and pin controls must have no touch-row layout boxes", ui.truth("(()=>{const actions=document.querySelector("
                + JSONObject.quote(row(OTHER) + " [data-droy-row-actions]") + ");const controls=[...actions.querySelectorAll("
                + "'[data-droy-session-archive][data-droy-session-action=archive],[data-droy-session-pin]')];"
                + "return controls.length===2&&controls.every(e=>e.getClientRects().length===0)})()"));
        ui.longPress(row(OTHER) + " [data-droy-session-surface]", ViewConfiguration.getLongPressTimeout() + 150L);
        awaitMenu(OTHER, true);
        SystemClock.sleep(250);
        assertEquals("Long press/release must not switch the current conversation", FIRST, selectedSession());
        assertTrue("Lifting the finger must keep the original menu open", menuOpen(OTHER));
        assertTouchHighlightTransparent(row(OTHER));
        assertTouchHighlightTransparent(row(OTHER) + " [data-droy-session-menu]");
        assertTrue("The active row must paint one opaque themed surface without a second outer fill", ui.truth("(()=>{"
                + "const row=document.querySelector(" + JSONObject.quote(row(OTHER)) + "),surface=row.querySelector('[data-droy-session-surface]');"
                + "const alpha=color=>{const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');ctx.fillStyle=color;ctx.fillRect(0,0,1,1);return ctx.getImageData(0,0,1,1).data[3]};"
                + "return alpha(getComputedStyle(row).backgroundColor)===0&&alpha(getComputedStyle(surface).backgroundColor)===255})()"));
        assertTrue("The original DSH menu must expose its archive action", ui.truth("[...document.querySelectorAll('[role=menuitem]')].some(e=>e.getClientRects().length>0&&['归档会话','Archive session'].includes(e.textContent.trim()))"));
        ui.pressBack();
        awaitMenu(OTHER, false);
        assertTrue("Back must close the menu before the drawer", drawerOpen());
        ui.tap(row(OTHER) + " [data-droy-session-menu]");
        awaitMenu(OTHER, true);
        // Reopening immediately after Back must survive the preceding menu's
        // exit animation and its delayed unmount callback.
        SystemClock.sleep(250);
        assertTrue("The preceding close animation dismissed the reopened menu", menuOpen(OTHER));
        assertEquals("Only the reopened session menu may remain interactive", 1,
                ((Number) ui.evaluate("[...document.querySelectorAll('.droy-session-menu')].filter(e=>!e.closest('[inert]')"
                        + "&&e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden').length")).intValue());
        assertEquals("One tap on More must not select that conversation", FIRST, selectedSession());
        assertFalse("Touch rows must not start desktop drag and drop", ui.truth("document.querySelector(" + JSONObject.quote(row(OTHER)) + ").draggable"));
    }

    @Test public void rowSwipeBackAndVerticalScrollDoNotCompeteWithDrawerOrLongPress() throws Exception {
        traceTouchFlow("row-back", () -> {
        selectFixture(FIRST);
        openDrawer();
        swipeRow(OTHER);
        assertTrue("Session swipe must leave the conversation drawer open", drawerOpen());
        assertEquals("Session swipe must not select that conversation", FIRST, selectedSession());
        ui.pressBack();
        DeviceUi.await("Back did not close only the revealed row action", 5000,
                () -> !ui.truth("!!document.querySelector('[data-droy-row-expanded=true]')"));
        assertTrue("The first Back must preserve the drawer", drawerOpen());
        ui.pressBack();
        awaitDrawer(false);
        // A system edge gesture also sends pointercancel into WebView before
        // invoking Back. It must consume only the revealed row, once.
        for (boolean fromLeft : new boolean[] { true, false }) {
            openDrawer();
            swipeRow(OTHER);
            assertTrue("Android must provide its Back gesture edge", ui.truth(fromLeft
                    ? "window.__droyTouchConfig.systemGestureLeftCssPx>0"
                    : "window.__droyTouchConfig.systemGestureRightCssPx>0"));
            int backCalls = ((Number) ui.evaluate("window.__droyGestureTrace.backCalls")).intValue();
            Rect frame = ui.visibleWebBounds("[data-droy-frame]");
            Rect swipedRow = ui.visibleWebBounds(row(OTHER));
            float startX = fromLeft ? frame.left + 1 : frame.right - 1;
            float endX = fromLeft ? frame.left + frame.width() * .35f : frame.right - frame.width() * .35f;
            ui.swipe(startX, swipedRow.centerY(), endX, swipedRow.centerY(), 350);
            DeviceUi.await("Edge gesture did not invoke the actual system Back", 5000,
                    () -> ((Number) ui.evaluate("window.__droyGestureTrace.backCalls")).intValue() > backCalls);
            assertFalse("Edge Back must close the revealed row", ui.truth("!!document.querySelector('[data-droy-row-expanded=true]')"));
            assertTrue("One edge Back must preserve the drawer", drawerOpen());
            ui.pressBack();
            awaitDrawer(false);
        }
        openDrawer();
        String overflow = "[data-droy-sidebar] [data-row-key^='overflow:']";
        if (ui.truth("!!document.querySelector(" + JSONObject.quote(overflow) + ")")) ui.tap(overflow);
        ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(row(OTHER)) + ");let e=row.parentElement;"
                + "while(e&&!(e.scrollHeight>e.clientHeight+2&&['auto','scroll'].includes(getComputedStyle(e).overflowY)))e=e.parentElement;"
                + "if(!e)throw Error('Real DSH list must scroll');e.dataset.droyTestScroll='true';e.scrollTop=0})()");
        Rect scroll = ui.visibleWebBounds("[data-droy-test-scroll]");
        float fromY = scroll.top + scroll.height() * .8f;
        float toY = scroll.top + scroll.height() * .25f;
        ui.swipe(scroll.left + scroll.width() * .4f, fromY, scroll.left + scroll.width() * .4f, toY, 450);
        DeviceUi.await("Vertical touch did not scroll the actual DSH list", 5000,
                () -> ui.truth("document.querySelector('[data-droy-test-scroll]').scrollTop>10"));
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100L);
        assertFalse("Vertical scrolling must cancel long press", ui.truth("!!document.querySelector('[data-droy-session-menu-open=true]')"));
        assertFalse("Vertical scrolling must not reveal a row action", ui.truth("!!document.querySelector('[data-droy-row-expanded=true]')"));
        assertTrue(drawerOpen());
        assertEquals(FIRST, selectedSession());
        });
    }

    @Test public void archiveCurrentKeepsDrawerAndRestoresThroughUndoAndArchivedFilter() throws Exception {
        selectFixture(FIRST);
        openDrawer();
        archiveVisibleRow(FIRST);
        assertTrue("Archiving the current conversation must preserve the drawer", drawerOpen());
        assertNotEquals("Archived history must leave the conversation selection", FIRST, selectedSession());
        assertFalse("Successfully archived row remained in the ordinary list", ui.truth("!!document.querySelector(" + JSONObject.quote(row(FIRST)) + ")"));
        tapVisibleText("button", "撤销", "Undo");
        DeviceUi.await("The DSH Undo action did not restore the original row", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(row(FIRST)) + ")"));
        assertNotEquals("Undo must not reopen or restart the archived conversation", FIRST, selectedSession());
        dismissFixtureModelOnboarding();
        archiveVisibleRow(FIRST);
        tapVisibleText("button", "筛选已归档会话", "filter archived sessions");
        DeviceUi.await("Archived filter did not reveal the archived row", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(row(FIRST)) + ")"));
        String restore = row(FIRST) + " [data-droy-row-actions] [data-droy-session-archive]";
        assertTouchTarget(restore);
        ui.tap(restore);
        DeviceUi.await("The visible archived-row restore action did not finish", 5000,
                () -> ui.truth("(()=>{const b=document.querySelector(" + JSONObject.quote(restore)
                        + ");return !!b&&['归档会话','Archive session'].includes(b.getAttribute('aria-label'))})()"));
        assertTrue("Restore must leave the drawer open", drawerOpen());
        assertNotEquals("Restore must not automatically select or run the conversation", FIRST, selectedSession());
        ui.tap("[data-droy-view-options]");
        tapVisibleText("[role=menuitem]", "隐藏已归档", "Hide archived");
        DeviceUi.await("Restored session is absent from the normal list", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(row(FIRST)) + ")"));
    }

    @Test public void composerAddLeavesClosedKeyboardClosedAndDraftEditable() throws Exception {
        selectFixture(FIRST);
        ui.hideKeyboard();
        assertTouchTarget("[data-droy-composer-add]");
        ui.tap("[data-droy-composer-add]");
        DeviceUi.await("The real composer add menu did not open", 5000,
                () -> ui.truth("document.querySelector('[data-droy-composer-add]')?.getAttribute('aria-expanded')==='true'"
                        + "&&!!document.querySelector('[data-trigger-menu] [role=listbox]')"));
        assertKeyboardStaysClosed("Opening the add menu must not force the input keyboard open");
        ui.pressBack();
        DeviceUi.await("Back did not close the real composer add menu", 5000,
                () -> ui.truth("document.querySelector('[data-droy-composer-add]')?.getAttribute('aria-expanded')!=='true'"
                        + "&&!document.querySelector('[data-trigger-menu]')"));
        DeviceUi.await("One Back from the add menu sent the app into the background", 5000, () -> {
            boolean[] foreground = {false};
            instrumentation.runOnMainSync(() -> foreground[0] = activity.hasWindowFocus()
                    && !activity.isFinishing() && !activity.isDestroyed());
            return foreground[0];
        });
        assertKeyboardStaysClosed("Closing the add menu must not focus the editor");
        ui.tap("[data-composer-input]");
        DeviceUi.await("A deliberate editor tap must still open the keyboard", 10000, ui::keyboardVisible);
        instrumentation.sendStringSync("touch draft");
        DeviceUi.await("The DSH draft did not accept actual Android key input", 5000,
                () -> ui.truth("document.querySelector('[data-composer-input]').textContent.includes('touch draft')"));
        ui.pressBack();
        DeviceUi.await("One system Back must hide the keyboard before navigating", 5000, () -> !ui.keyboardVisible());
        assertTrue("Hiding the keyboard must keep the app in the foreground", ui.nativeNavigationState().getBoolean("activityFocus"));
        openDrawer();
        ui.pressBack();
        awaitDrawer(false);
        assertKeyboardStaysClosed("Closing the drawer must not restore editor focus and reopen the keyboard");
        assertTrue("The draft was lost while using menus", ui.truth("document.querySelector('[data-composer-input]').textContent.includes('touch draft')"));
        ui.tap("[data-composer-input]");
        DeviceUi.await("The retained draft is no longer editable", 10000, ui::keyboardVisible);
        instrumentation.sendStringSync(" edited");
        DeviceUi.await("The retained draft did not accept additional typing", 5000,
                () -> ui.truth("document.querySelector('[data-composer-input]').textContent.includes('edited')"));
        ui.hideKeyboard();
    }

    @Test public void provisionalBlankConversationHasNoArchiveGestureOrMenu() throws Exception {
        openDrawer();
        ui.tap("[data-droy-sidebar] button[aria-label='新建会话'],[data-droy-sidebar] button[aria-label='New session']");
        awaitDrawer(false);
        openDrawer();
        String blank = "[data-droy-session-row][data-droy-session-blank=true]";
        DeviceUi.await("A new workspace conversation did not expose its blank row", 10000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(blank) + ")"));
        assertFalse("Blank conversation must not have an archive action or More menu", ui.truth("!!document.querySelector(" + JSONObject.quote(blank)
                + ").querySelector('[data-droy-swipe-action],[data-droy-session-menu]')"));
        assertEquals("The current empty conversation must retain its selected row", "true",
                ui.evaluate("document.querySelector(" + JSONObject.quote(blank) + ").getAttribute('aria-selected')"));
        assertTrue("The selected blank conversation must have the same selection feedback as history rows", ui.truth("(()=>{"
                + "const blank=document.querySelector(" + JSONObject.quote(blank) + ")?.querySelector('[data-droy-session-surface]'),"
                + "other=document.querySelector(" + JSONObject.quote(row(FIRST)) + ")?.querySelector('[data-droy-session-surface]');"
                + "return !!blank&&!!other&&getComputedStyle(blank).backgroundColor!==getComputedStyle(other).backgroundColor})()"));
        Rect bounds = ui.visibleWebBounds(blank);
        ui.swipe(bounds.right - bounds.width() * .25f, bounds.centerY(), bounds.left + bounds.width() * .1f, bounds.centerY(), 400);
        ui.longPress(blank, ViewConfiguration.getLongPressTimeout() + 150L);
        assertTrue("A row swipe must not fall through to closing the drawer", drawerOpen());
        assertFalse("Blank row must not open a secondary menu", ui.truth("!!document.querySelector('[data-droy-session-menu-open=true],[data-droy-row-expanded=true]')"));
    }

    @Test public void workspaceRepeatedTapAndCurrentChoicePreserveTheOriginalDraft() throws Exception {
        String blankId = newBlankConversation();
        String draft = "Workspace picker keeps this unsent draft";
        typeDraft(draft);
        rememberInputFace();
        WebView original = ui.web();
        String chip = "[data-droy-workspace-chip]";
        assertTouchHighlightTransparent(chip);
        ui.tap(chip);
        awaitWorkspacePicker(true);
        assertKeyboardStaysClosed("Opening the workspace picker must leave the keyboard closed");
        ui.tap(chip);
        awaitWorkspacePicker(false);
        assertDraftIdentity(blankId, draft);

        ui.tap(chip);
        awaitWorkspacePicker(true);
        // The isolated profile has exactly one workspace. Its actual original
        // menu item precedes the separate Add workspace footer.
        markVisibleTarget("[...document.querySelectorAll('.droy-workspace-picker [role=menuitem]')].find(e=>visible(e))");
        assertTouchHighlightTransparent("[data-droy-test-target]");
        ui.tap("[data-droy-test-target]");
        awaitWorkspacePicker(false);
        assertDraftIdentity(blankId, draft);
        assertSame("Choosing the current workspace replaced the WebView", original, ui.web());
        assertKeyboardStaysClosed("Choosing the current workspace must not refocus the editor");
    }

    @Test public void temporaryTextDraftCanBeFoundAfterSwitchingAndDisappearsWhenCleared() throws Exception {
        String blankId = newBlankConversation();
        String draft = "Find this unsent conversation again";
        typeDraft(draft);
        rememberInputFace();
        selectFixture(FIRST);
        assertFalse("A blank conversation's draft leaked into historical input", composerText().contains(draft));
        openDrawer();
        String temporary = row(blankId) + "[data-droy-session-draft=true]";
        DeviceUi.await("Switching away hid an unsent text draft from the real conversation list", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(temporary) + ")"));
        assertFalse("An unsent draft must not offer archival controls", ui.truth("!!document.querySelector(" + JSONObject.quote(temporary)
                + ").querySelector('[data-droy-session-menu],[data-droy-swipe-action]')"));
        ui.tap(temporary + " [data-droy-session-surface]");
        awaitDrawer(false);
        dismissFixtureModelOnboarding();
        assertDraftIdentity(blankId, draft);
        clearDraftWithAndroidKeys();
        selectFixture(FIRST);
        openDrawer();
        DeviceUi.await("Clearing and leaving a draft kept an unnecessary temporary row", 5000,
                () -> !ui.truth("!!document.querySelector(" + JSONObject.quote(row(blankId)) + ")"));
        assertEquals(FIRST, selectedSession());
    }

    @Test public void imageOnlyDraftRetainsItsOriginalInputAndPreviewAcrossRealNavigation() throws Exception {
        String blankId = newBlankConversation();
        rememberInputFace();
        // Inject only fictional file intake through the original component's
        // addFiles face. Native picker behavior is deliberately not inferred.
        assertTrue("The original DSH input refused the fictional PNG", ui.truth("(()=>{"
                + "const input=window.__droyInputFixture.current(),bytes=Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGOoiHr2HwAFvgK4os1+JAAAAABJRU5ErkJggg=='),c=>c.charCodeAt(0));"
                + "const result=input.addFiles([new File([bytes],'droy-fictional-draft.png',{type:'image/png'})]);"
                + "if(result!==null)return false;const ids=input.keyboard.snapshot.attachmentIds;"
                + "window.__droyInputFixture.attachmentId=ids[0];return ids.length===1})()"));
        String image = "[data-droy-center] img[alt='droy-fictional-draft.png']";
        DeviceUi.await("The real DSH attachment rail did not render the PNG preview", 5000,
                () -> ui.truth("(()=>{const image=document.querySelector(" + JSONObject.quote(image)
                        + ");return !!image&&image.complete&&image.naturalWidth>0})()"));
        ui.evaluate("window.__droyInputFixture.previewUrl=document.querySelector(" + JSONObject.quote(image) + ").currentSrc");
        assertTrue("This fixture must contain an image without typed text", composerText().trim().isEmpty());
        selectFixture(FIRST);
        assertFalse("The image leaked into the other conversation", ui.truth("!!document.querySelector(" + JSONObject.quote(image) + ")"));
        openDrawer();
        String temporary = row(blankId) + "[data-droy-session-draft=true]";
        DeviceUi.await("An image-only unsent conversation disappeared from the sidebar", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(temporary) + ")"));
        ui.tap(temporary + " [data-droy-session-surface]");
        awaitDrawer(false);
        dismissFixtureModelOnboarding();
        assertDraftIdentity(blankId, "");
        assertTrue("Returning reconstructed or dropped the original image attachment", ui.truth("(()=>{const fixture=window.__droyInputFixture,"
                + "input=fixture.current(),image=document.querySelector(" + JSONObject.quote(image) + ");"
                + "return input.keyboard.snapshot.attachmentIds.length===1&&input.keyboard.snapshot.attachmentIds[0]===fixture.attachmentId"
                + "&&!!image&&image.complete&&image.naturalWidth>0&&image.currentSrc===fixture.previewUrl})()"));
        for (boolean systemBack : new boolean[]{true, false}) {
            markVisibleTarget("document.querySelector(" + JSONObject.quote(image) + ")?.closest('button')");
            ui.tap("[data-droy-test-target]");
            assertAttachmentLightboxGeometry();
            assertTouchTarget("[data-droy-attachment-close]");
            assertTouchHighlightTransparent("[data-droy-attachment-close]");
            if (systemBack) ui.pressBack();
            else ui.tap("[data-droy-attachment-close]");
            DeviceUi.await("The original image preview did not close through " + (systemBack ? "Android Back" : "its close control"), 5000,
                    () -> !ui.truth("!!document.querySelector('[data-droy-attachment-lightbox]')"));
            assertDraftIdentity(blankId, "");
            assertTrue("Closing image preview changed its unsent attachment or preview URL", ui.truth("(()=>{const fixture=window.__droyInputFixture,"
                    + "input=fixture.current(),image=document.querySelector(" + JSONObject.quote(image) + ");"
                    + "return input.keyboard.snapshot.attachmentIds[0]===fixture.attachmentId&&!!image&&image.currentSrc===fixture.previewUrl})()"));
            assertKeyboardStaysClosed("Closing an image preview must not open the input keyboard");
        }
        markVisibleTarget("(()=>{const image=document.querySelector(" + JSONObject.quote(image)
                + ");return image?.closest('button')?.parentElement.querySelector('button[aria-label]')})()");
        ui.tap("[data-droy-test-target]");
        DeviceUi.await("Removing the image left a draft attachment or its preview alive", 5000,
                () -> ui.truth("(()=>{const fixture=window.__droyInputFixture,input=fixture.current();return input.keyboard.snapshot.attachmentIds.length===0"
                        + "&&input.resolveDraftAttachments([fixture.attachmentId]).length===0&&!document.querySelector(" + JSONObject.quote(image) + ")})()"));
        selectFixture(FIRST);
        openDrawer();
        DeviceUi.await("Removing the only image left an empty draft row", 5000,
                () -> !ui.truth("!!document.querySelector(" + JSONObject.quote(row(blankId)) + ")"));
    }

    @Test public void fullScreenRightPanelKeepsSafeControlsAndReturnsToTheSameConversation() throws Exception {
        selectFixture(FIRST);
        String draft = "Right panel keeps this conversation draft";
        typeDraft(draft);
        rememberInputFace();
        WebView original = ui.web();
        String panel = "[data-droy-mobile-panel][data-sidebar-right-open=true]";
        ui.tap("[data-sidebar-right-expand]");
        DeviceUi.await("The original right panel did not open", 5000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(panel) + ")"));
        assertRightPanelGeometry(panel);
        assertTouchTarget(panel + " [data-sidebar-right-toggle]");
        assertTouchHighlightTransparent(panel + " [data-sidebar-right-toggle]");
        ui.pressBack();
        DeviceUi.await("One Android Back did not close the right panel", 5000,
                () -> !ui.truth("!!document.querySelector(" + JSONObject.quote(panel) + ")"));
        assertDraftIdentity(FIRST, draft);
        assertSame("Closing the right panel replaced the conversation WebView", original, ui.web());
        assertKeyboardStaysClosed("Returning from the right panel must not reopen the keyboard");
        ui.tap("[data-sidebar-right-expand]");
        assertRightPanelGeometry(panel);
        ui.tap(panel + " [data-sidebar-right-toggle]");
        DeviceUi.await("The explicit right-panel return control did not close it", 5000,
                () -> !ui.truth("!!document.querySelector(" + JSONObject.quote(panel) + ")"));
        assertDraftIdentity(FIRST, draft);
        assertTrue("Returning from the right panel sent the app into the background", ui.nativeNavigationState().getBoolean("activityFocus"));
    }

    private String newBlankConversation() throws Exception {
        selectFixture(FIRST);
        openDrawer();
        ui.tap("[data-droy-sidebar] button[aria-label='新建会话'],[data-droy-sidebar] button[aria-label='New session']");
        awaitDrawer(false);
        ui.awaitPage();
        openDrawer();
        String blank = "[data-droy-session-row][data-droy-session-blank=true][aria-selected=true]";
        DeviceUi.await("The newly selected blank conversation did not render", 10000,
                () -> ui.truth("!!document.querySelector(" + JSONObject.quote(blank) + ")"));
        String id = String.valueOf(ui.evaluate("document.querySelector(" + JSONObject.quote(blank) + ").getAttribute('data-droy-session-row')"));
        assertFalse("The blank fixture must have a session identity", id.isEmpty());
        ui.tap("[data-droy-drawer-close]");
        awaitDrawer(false);
        assertTrue("The new conversation must start with empty input", composerText().trim().isEmpty());
        return id;
    }

    private void dismissFixtureModelOnboarding() throws Exception {
        if (!ui.truth("[...document.querySelectorAll('[role=dialog] h2,[role=alertdialog] h2')].some(e=>e.getClientRects().length>0"
                + "&&['添加一个 API Key 开始使用','Add an API key to get started'].includes(e.textContent.trim()))")) return;
        // Reopening a blank session remounts DSH's initial model-key notice in
        // this unconfigured fixture. Only its existing precise handler may act.
        ui.awaitPage();
        ui.hideKeyboard();
    }

    private void typeDraft(String text) throws Exception {
        ui.tap("[data-composer-input]");
        DeviceUi.await("Deliberate draft editing did not open Android's keyboard", 10000, ui::keyboardVisible);
        instrumentation.sendStringSync(text);
        DeviceUi.await("The DSH input did not receive the real Android key events", 5000,
                () -> composerText().contains(text));
        ui.evaluate("document.activeElement?.blur()");
        ui.hideKeyboard();
    }

    private String composerText() throws Exception {
        return String.valueOf(ui.evaluate("document.querySelector('[data-composer-input]')?.textContent||''"));
    }

    private void rememberInputFace() throws Exception {
        // React is used only to discover the real component's existing public
        // input face; the fixture never replaces a store or a navigation action.
        assertTrue("The real composer did not expose its mounted DSH input face", ui.truth("(()=>{const current=()=>{"
                + "const editor=document.querySelector('[data-composer-input]');if(!editor)return null;"
                + "const key=Object.keys(editor).find(key=>key.startsWith('__reactFiber$'));"
                + "for(let fiber=editor[key];fiber;fiber=fiber.return){const props=fiber.memoizedProps;"
                + "if(props&&typeof props.addFiles==='function'&&props.keyboard&&props.sessionId)return props}return null};"
                + "const input=current();if(!input)return false;window.__droyInputFixture={current,keyboard:input.keyboard,"
                + "sessionId:input.sessionId,editor:document.querySelector('[data-composer-input]')};return true})()"));
    }

    private void assertDraftIdentity(String sessionId, String draft) throws Exception {
        DeviceUi.await("Navigation did not restore the original session input and unsent content", 5000,
                () -> ui.truth("(()=>{const fixture=window.__droyInputFixture,input=fixture.current();return !!input"
                        + "&&input.sessionId===" + JSONObject.quote(sessionId) + "&&input.keyboard===fixture.keyboard"
                        + "&&input.keyboard.snapshot.draft===" + JSONObject.quote(draft) + "})()"));
        assertEquals("Navigation selected another conversation", sessionId, selectedSession());
        if (!draft.isEmpty()) assertTrue("The retained DSH draft is missing from the actual editor", composerText().contains(draft));
    }

    private void clearDraftWithAndroidKeys() throws Exception {
        try {
        ui.tap("[data-composer-input]");
        DeviceUi.await("The restored draft is no longer editable", 10000, ui::keyboardVisible);
        long now = SystemClock.uptimeMillis();
        instrumentation.sendKeySync(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeySync(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON));
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);
        DeviceUi.await("Android Select all/Delete did not clear the original draft", 5000,
                () -> composerText().trim().isEmpty());
        ui.evaluate("document.activeElement?.blur()");
        ui.hideKeyboard();
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "; safe restored-input state=" + restoredInputMetadata(), failure);
        }
    }

    private JSONObject restoredInputMetadata() throws Exception {
        JSONObject result = (JSONObject) ui.evaluate("(()=>{const root=document.getElementById('root'),center=document.querySelector('[data-droy-center]'),"
                + "selected=document.querySelector('[data-droy-session-row][aria-selected=true]')?.getAttribute('data-droy-session-row');"
                + "const marker=e=>{if(!(e instanceof Element))return null;return {tag:e.tagName,id:e.id,className:typeof e.className==='string'?e.className:null,"
                + "role:e.getAttribute('role'),root:e===root,center:e===center,inert:!!e.inert,ariaHidden:e.getAttribute('aria-hidden'),"
                + "droy:Object.fromEntries([...e.attributes].filter(a=>a.name.startsWith('data-droy-')).map(a=>[a.name,a.value.slice(0,80)]))}};"
                + "const geometry=e=>{if(!e)return null;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {left:r.left,top:r.top,width:r.width,height:r.height,"
                + "display:s.display,visibility:s.visibility,contentEditable:e.contentEditable,connected:e.isConnected}};"
                + "const input=e=>{const key=Object.keys(e).find(k=>k.startsWith('__reactFiber$'));for(let f=e[key];f;f=f.return){"
                + "const p=f.memoizedProps;if(p&&typeof p.addFiles==='function'&&p.keyboard)return {sessionId:p.sessionId,"
                + "originalKeyboard:p.keyboard===window.__droyInputFixture?.keyboard,phase:p.keyboard.snapshot.phase}}return null};"
                + "const editors=[...document.querySelectorAll('[data-composer-input]')].slice(0,4).map((e,index)=>{"
                + "const owner=e.closest('[inert]');"
                + "const session=e.closest('[data-conversation-session]')?.getAttribute('data-conversation-session');"
                + "return {index,session,matchesSelected:session===selected,input:input(e),...geometry(e),inertOwner:marker(owner)}});"
                + "const knownTitle=e=>{const title=e.querySelector('h2')?.textContent.trim();return ['添加一个 API Key 开始使用','Add an API key to get started'].includes(title)?'model-key':"
                + "['欢迎使用 DroyAgent','Welcome to DroyAgent','预览版说明','Preview Notice'].includes(title)?'welcome':'other'};"
                + "const dialogs=[...document.querySelectorAll('[role=dialog],[role=alertdialog]')].filter(e=>e.getClientRects().length>0).slice(0,8)"
                + ".map(e=>({...marker(e),...geometry(e),knownTitle:knownTitle(e),knownLaterButton:[...e.querySelectorAll('button')]"
                + ".some(b=>['稍后配置','Configure later'].includes(b.textContent.trim()))}));"
                + "return {selected,editorCount:document.querySelectorAll('[data-composer-input]').length,editors,dialogs,root:marker(root),center:marker(center),"
                + "active:marker(document.activeElement),activeGeometry:geometry(document.activeElement),viewport:{width:innerWidth,height:innerHeight},"
                + "drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]')}})()");
        return result.put("nativeNavigation", ui.nativeNavigationState());
    }

    private void awaitWorkspacePicker(boolean open) throws Exception {
        DeviceUi.await("The original workspace menu did not become " + (open ? "open" : "closed"), 5000,
                () -> ui.truth("document.querySelector('[data-droy-workspace-chip]')?.getAttribute('aria-expanded')==='true'") == open);
        DeviceUi.await("Workspace menu left an interactive stale surface", 5000,
                () -> ui.truth("[...document.querySelectorAll('.droy-workspace-picker')].some(e=>e.getClientRects().length>0"
                        + "&&!e.closest('[inert]')&&e.getAttribute('aria-hidden')!=='true')") == open);
    }

    private void assertTouchHighlightTransparent(String selector) throws Exception {
        assertTrue("Android's default colored touch highlight remains on " + selector,
                ui.truth("(()=>{const element=document.querySelector(" + JSONObject.quote(selector) + ");if(!element)return false;"
                        + "const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');"
                        + "ctx.fillStyle=getComputedStyle(element).webkitTapHighlightColor;ctx.fillRect(0,0,1,1);"
                        + "return ctx.getImageData(0,0,1,1).data[3]===0})()"));
    }

    private void assertRightPanelGeometry(String panel) throws Exception {
        DeviceUi.await("The right panel did not fill the mobile viewport with safe content and return controls", 5000,
                () -> ui.truth("(()=>{const panel=document.querySelector(" + JSONObject.quote(panel) + ");if(!panel)return false;"
                        + "const body=panel.querySelector('[data-droy-mobile-panel-body]'),chrome=panel.querySelector('[data-droy-panel-chrome]'),"
                        + "toggle=panel.querySelector('[data-sidebar-right-toggle]');if(!body||!chrome||!toggle)return false;"
                        + "const p=panel.getBoundingClientRect(),b=body.getBoundingClientRect(),c=chrome.getBoundingClientRect(),t=toggle.getBoundingClientRect(),"
                        + "style=getComputedStyle(document.documentElement),px=name=>parseFloat(style.getPropertyValue(name))||0,"
                        + "top=px('--droy-safe-top'),bottom=Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom')),"
                        + "left=px('--droy-safe-left'),right=px('--droy-safe-right');"
                        + "const safe=r=>r.width>0&&r.height>0&&r.top>=top-.75&&r.bottom<=innerHeight-bottom+.75&&r.left>=left-.75&&r.right<=innerWidth-right+.75;"
                        + "return Math.abs(p.left)<.75&&Math.abs(p.right-innerWidth)<.75&&Math.abs(p.top)<.75&&Math.abs(p.bottom-innerHeight)<.75"
                        + "&&safe(b)&&safe(c)&&safe(t)&&Math.abs(b.bottom-(innerHeight-bottom))<1.5"
                        + "&&[...panel.querySelectorAll('[data-dockkit-tab]')].filter(e=>e.getClientRects().length>0).every(e=>safe(e.getBoundingClientRect()))})()"));
    }

    private void assertAttachmentLightboxGeometry() throws Exception {
        DeviceUi.await("The actual image lightbox or its controls overlap Android's safe area", 5000,
                () -> ui.truth("(()=>{const root=document.querySelector('[data-droy-attachment-lightbox]'),"
                        + "image=root?.querySelector('[data-droy-attachment-image]'),close=root?.querySelector('[data-droy-attachment-close]');"
                        + "if(!root||!image||!close||root.closest('[inert]')||!image.complete||image.naturalWidth<=0)return false;"
                        + "const r=root.getBoundingClientRect(),i=image.getBoundingClientRect(),c=close.getBoundingClientRect(),"
                        + "style=getComputedStyle(document.documentElement),px=name=>parseFloat(style.getPropertyValue(name))||0,"
                        + "top=px('--droy-safe-top'),bottom=Math.max(px('--droy-safe-bottom'),px('--droy-ime-bottom')),left=px('--droy-safe-left'),right=px('--droy-safe-right');"
                        + "const safe=r=>r.width>0&&r.height>0&&r.top>=top-.75&&r.bottom<=innerHeight-bottom+.75&&r.left>=left-.75&&r.right<=innerWidth-right+.75;"
                        + "return Math.abs(r.left)<.75&&Math.abs(r.top)<.75&&Math.abs(r.right-innerWidth)<.75&&Math.abs(r.bottom-innerHeight)<.75&&safe(i)&&safe(c)})()"));
    }

    private String row(String id) { return "[data-droy-session-row='" + id + "']"; }

    private boolean drawerOpen() throws Exception {
        return ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')");
    }

    private void awaitDrawer(boolean open) throws Exception {
        DeviceUi.await("The real drawer did not become " + (open ? "open" : "closed"), 5000, () -> drawerOpen() == open);
        SystemClock.sleep(250);
    }

    private void openDrawer() throws Exception {
        if (!drawerOpen()) ui.tap("[data-droy-menu]");
        awaitDrawer(true);
        JSONObject pointer = (JSONObject) ui.evaluate("window.__droyTouchProbe");
        assertTrue("The UI must receive an actual pointerdown from Android", pointer.getInt("count") > 0);
        assertEquals("The dispatcher must deliver a finger touch, not a mouse", "touch", pointer.getString("pointerType"));
        assertTrue("Touch regressions require trusted WebView input events", pointer.getBoolean("isTrusted"));
        if (!ui.truth("!!document.querySelector(" + JSONObject.quote(row(FIRST)) + ")")) {
            String collapsed = "[data-droy-sidebar] [role=treeitem][aria-expanded=false]";
            if (ui.truth("!!document.querySelector(" + JSONObject.quote(collapsed) + ")")) ui.tap(collapsed);
        }
        DeviceUi.await("The actual fictional DSH workspace rows did not render", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-session-row^=droy-touch-]')"));
    }

    private float[] drawerHeaderBlankPoint() throws Exception {
        // Probe the actual hit target. Header children contain real buttons
        // that intentionally retain taps and must never initiate drawer drags.
        Object candidate = ui.evaluate("(()=>{const interactive='button,a,input,textarea,select,[contenteditable=\"true\"],[role=button],[role=menuitem]';"
                + "for(const header of document.querySelectorAll('[data-droy-drawer-header]')){const r=header.getBoundingClientRect();"
                + "for(let y=r.top+3;y<r.bottom-2;y+=4)for(let x=r.right-3;x>r.left+r.width*.45;x-=4){"
                + "const neutral=(px,py)=>{const hit=document.elementFromPoint(px,py);return hit&&hit.closest('[data-droy-drawer-header]')===header&&!hit.closest(interactive)};"
                + "if([[0,0],[-2,-2],[-2,2],[2,-2],[2,2]].every(([dx,dy])=>neutral(x+dx,y+dy)))return {x,y,viewportWidth:innerWidth};"
                + "}}return null})()");
        assertTrue("The real drawer header needs a noninteractive blank swipe target", candidate instanceof JSONObject);
        JSONObject point = (JSONObject) candidate;
        int[] screen = new int[3];
        android.webkit.WebView view = ui.web();
        instrumentation.runOnMainSync(() -> { view.getLocationOnScreen(screen);screen[2] = view.getWidth(); });
        double scale = screen[2] / point.getDouble("viewportWidth");
        return new float[]{screen[0] + (float) (point.getDouble("x") * scale),
                screen[1] + (float) (point.getDouble("y") * scale)};
    }

    private void selectFixture(String id) throws Exception {
        openDrawer();
        ui.tap(row(id) + " [data-droy-session-surface]");
        awaitDrawer(false);
        DeviceUi.await("The real DSH session did not become selected", 10000, () -> id.equals(selectedSession()));
        ui.hideKeyboard();
    }

    private String selectedSession() throws Exception {
        return String.valueOf(ui.evaluate("document.querySelector('[data-droy-session-row][aria-selected=true]')?.getAttribute('data-droy-session-row')||''"));
    }

    private boolean menuOpen(String id) throws Exception {
        return ui.truth("document.querySelector(" + JSONObject.quote(row(id)) + ")?.getAttribute('data-droy-session-menu-open')==='true'");
    }

    private void awaitMenu(String id, boolean open) throws Exception {
        DeviceUi.await("The real session menu did not become " + (open ? "open" : "closed"), 5000, () -> menuOpen(id) == open);
    }

    private void swipeRow(String id) throws Exception {
        try {
        Rect bounds = ui.visibleWebBounds(row(id) + " [data-droy-session-surface]");
        ui.swipe(bounds.left + bounds.width() * .65f, bounds.centerY(), bounds.left + bounds.width() * .12f, bounds.centerY(), 380);
        DeviceUi.await("Left swipe did not expose the real DSH archive button", 5000,
                () -> ui.truth("document.querySelector(" + JSONObject.quote(row(id)) + ")?.getAttribute('data-droy-row-expanded')==='true'"
                        + "&&!document.querySelector(" + JSONObject.quote(row(id) + " [data-droy-swipe-action]") + ")?.inert"));
        awaitRowSettled(id);
        } catch (AssertionError failure) {
            throw new AssertionError(failure.getMessage() + "; safe row geometry=" + rowGeometry(id), failure);
        }
    }

    private JSONObject rowGeometry(String id) throws Exception {
        return (JSONObject) ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(row(id))
                + "),surface=row?.querySelector('[data-droy-session-surface]');"
                + "const marker=e=>e?{tag:e.tagName,role:e.getAttribute('role'),sidebar:e.hasAttribute('data-droy-sidebar'),"
                + "settings:e.hasAttribute('data-droy-settings'),row:e.hasAttribute('data-droy-session-row'),surface:e.hasAttribute('data-droy-session-surface'),"
                + "action:e.hasAttribute('data-droy-swipe-action'),shortcut:e.hasAttribute('data-shortcut-modal'),trigger:e.hasAttribute('data-trigger-menu')}:null;"
                + "const layer=e=>{const titles=[...e.querySelectorAll('h2')].map(h=>h.textContent.trim());"
                + "const onboardingTitle=titles.some(t=>['添加一个 API Key 开始使用','Add an API key to get started'].includes(t))?'model-key':"
                + "titles.some(t=>['欢迎使用 DroyAgent','Welcome to DroyAgent','预览版说明','Preview Notice'].includes(t))?'welcome':'other';"
                + "const allowed=['继续','Continue','稍后配置','Configure later','归档','Archive','归档会话','Archive session','取消','Cancel','停止并归档','Stop and archive','确认','Confirm','确定','OK'];"
                + "const knownActions=[...e.querySelectorAll('button')].filter(b=>b.getClientRects().length>0).map(b=>b.textContent.trim()).filter(t=>allowed.includes(t)).slice(0,12);"
                + "return {...marker(e),onboardingTitle,knownActions}};"
                + "const describe=e=>{if(!e)return null;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {tag:e.tagName,role:e.getAttribute('role'),"
                + "display:s.display,inlineDisplay:e.style.display,visibility:s.visibility,transform:s.transform,pointerEvents:s.pointerEvents,"
                + "inert:!!e.inert,inertOwner:marker(e.closest('[inert]')),disabledType:typeof e.disabled,disabledBoolean:!!e.disabled,"
                + "ariaHidden:e.getAttribute('aria-hidden'),rect:{x:r.x,y:r.y,width:r.width,height:r.height},"
                + "scrollTop:e.scrollTop,scrollHeight:e.scrollHeight,clientHeight:e.clientHeight}};"
                + "const ancestors=[];for(let e=surface||row;e&&ancestors.length<24;e=e.parentElement)ancestors.push(describe(e));"
                + "return {row:describe(row),surface:describe(surface),ancestors,swipeLayerPresent:!!row?.querySelector('[data-droy-swipe-action]'),"
                + "viewport:{width:innerWidth,height:innerHeight,scrollY},cssIme:getComputedStyle(document.documentElement).getPropertyValue('--droy-ime-bottom'),"
                + "layers:[...document.querySelectorAll('[data-shortcut-modal],[aria-modal=\"true\"],[role=dialog],[role=alertdialog],[role=menu],[data-trigger-menu]')]"
                + ".filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(e).visibility!=='hidden'}).slice(0,12).map(layer),"
                + "touchInteraction:window.DroyTouch?.isTouchInteraction?.(),lastPointer:window.__droyTouchProbe,"
                + "drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]')}})()");
    }

    private void awaitRowSettled(String id) throws Exception {
        int[] stableSamples = {0};
        DeviceUi.await("The real row surface did not settle at its revealed-action position", 3000, () -> {
            boolean settled = ui.truth("(()=>{const row=document.querySelector(" + JSONObject.quote(row(id))
                    + "),surface=row?.querySelector('[data-droy-session-surface]'),action=row?.querySelector('[data-droy-swipe-action]');"
                    + "if(!row||!surface||!action||action.inert||row.getAttribute('data-droy-row-expanded')!=='true')return false;"
                    + "const width=action.getBoundingClientRect().width,transform=getComputedStyle(surface).transform;"
                    + "const actual=transform==='none'?0:new DOMMatrixReadOnly(transform).m41;"
                    + "const target=parseFloat(getComputedStyle(row).getPropertyValue('--droy-row-offset'));"
                    + "return width>0&&Math.abs(target+width)<.75&&Math.abs(actual-target)<.75"
                    + "&&!surface.getAnimations().some(animation=>animation.pending||animation.playState==='running')})()");
            if (!settled) { stableSamples[0] = 0;return false; }
            return ++stableSamples[0] >= 2;
        });
    }

    private void archiveVisibleRow(String id) throws Exception {
        swipeRow(id);
        String selector = row(id) + " [data-droy-swipe-action] [data-droy-session-archive]";
        ui.evaluate("(()=>{const selector=" + JSONObject.quote(selector) + ",rowSelector=" + JSONObject.quote(row(id)) + ";"
                + "const probe=window.__droyArchiveProbe={events:[],touches:[],scrolls:[],eventCounts:{},captureClicks:0,postCaptureClicks:0,bubbleClicks:0};"
                + "const boundedPush=(list,value)=>{list.push(value);if(list.length>12)list.shift()};"
                + "const describe=()=>{const row=document.querySelector(rowSelector),button=document.querySelector(selector),action=button?.closest('[data-droy-swipe-action]');"
                + "const r=button?.getBoundingClientRect(),style=button?getComputedStyle(button):null;"
                + "const hit=r?document.elementFromPoint(r.left+r.width/2,r.top+r.height/2):null;"
                + "const selection=window.getSelection(),anchor=selection?.anchorNode,anchorElement=anchor?.nodeType===Node.ELEMENT_NODE?anchor:anchor?.parentElement;"
                + "let scroller=row?.parentElement;while(scroller&&!(scroller.scrollHeight>scroller.clientHeight+2&&['auto','scroll'].includes(getComputedStyle(scroller).overflowY)))scroller=scroller.parentElement;"
                + "const hitAction=hit?.closest('[data-droy-session-archive]')?.getAttribute('data-droy-session-action');"
                + "return {rowPresent:!!row,drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]'),"
                + "expanded:row?.getAttribute('data-droy-row-expanded'),inert:!!action?.inert,ariaHidden:action?.getAttribute('aria-hidden'),"
                + "ariaBusy:row?.getAttribute('aria-busy'),buttonBusy:button?.getAttribute('aria-busy'),pending:!!row?.hasAttribute('data-droy-action-pending'),"
                + "scrollTop:scroller?.scrollTop,buttonDisabled:!!button?.disabled,buttonRect:r?{x:r.x,y:r.y,width:r.width,height:r.height}:null,"
                + "visibility:style?.visibility,display:style?.display,hit:{tag:hit?.tagName,action:['archive','unarchive'].includes(hitAction)?hitAction:null,"
                + "swipeAction:!!hit?.closest('[data-droy-swipe-action]'),surface:!!hit?.closest('[data-droy-session-surface]'),"
                + "sameButton:!!button&&(hit===button||button.contains(hit))},"
                + "knownArchiveConfirmation:[...document.querySelectorAll('[role=dialog] h2,[role=alertdialog] h2')].some(e=>e.getClientRects().length>0&&/归档|[Aa]rchive/.test(e.textContent)),"
                + "selection:{type:selection?.type,rangeCount:selection?.rangeCount,isCollapsed:selection?.isCollapsed,"
                + "anchorInHeader:!!anchorElement?.closest('[data-droy-drawer-header]'),anchorInRow:!!anchorElement?.closest('[data-droy-session-row]')},"
                + "lastPointer:window.__droyTouchProbe}};"
                + "probe.describe=describe;probe.before=describe();"
                + "probe.actionListener=event=>{if(event.detail?.sessionId!==" + JSONObject.quote(id) + ")return;"
                + "const {action,phase}=event.detail;boundedPush(probe.events,{action:['archive','unarchive'].includes(action)?action:'other',"
                + "phase:['pending','success','failed','needsConfirmation','cancelled'].includes(phase)?phase:'other'})};"
                + "probe.pointerListeners=[];for(const type of ['pointerdown','pointerup','pointercancel','lostpointercapture','touchstart','touchend','mousedown','mouseup','click'])for(const capture of [true,false]){"
                + "const target=capture?window:document,handler=event=>{const direct=event.target,geometry=describe();"
                + "const phase=capture?'window-capture':'document-bubble',countKey=type+':'+phase;probe.eventCounts[countKey]=(probe.eventCounts[countKey]||0)+1;"
                + "if(event.type==='click'){if(capture){probe.captureClicks++;queueMicrotask(()=>boundedPush(probe.touches,{type:'click-final',phase:'microtask',defaultPrevented:event.defaultPrevented}))}else probe.bubbleClicks++}"
                + "boundedPush(probe.touches,{type:event.type,phase:capture?'window-capture':'document-bubble',detail:event.detail,defaultPrevented:event.defaultPrevented,"
                + "pointerType:event.pointerType,isTrusted:event.isTrusted,x:event.clientX??event.changedTouches?.[0]?.clientX,y:event.clientY??event.changedTouches?.[0]?.clientY,"
                + "directTarget:{tag:direct?.tagName,role:direct?.getAttribute?.('role'),button:!!direct?.closest?.('button'),"
                + "archive:!!direct?.closest?.('[data-droy-session-archive]'),swipeAction:!!direct?.closest?.('[data-droy-swipe-action]'),"
                + "row:!!direct?.closest?.('[data-droy-session-row]'),"
                + "surface:!!direct?.closest?.('[data-droy-session-surface]')},geometry})};"
                + "target.addEventListener(type,handler,capture);probe.pointerListeners.push(()=>target.removeEventListener(type,handler,capture))}"
                + "const afterCapture=event=>{probe.postCaptureClicks++;boundedPush(probe.touches,{type:'click',phase:'document-capture',"
                + "detail:event.detail,defaultPrevented:event.defaultPrevented,pointerType:event.pointerType,isTrusted:event.isTrusted,"
                + "x:event.clientX,y:event.clientY,directTarget:{tag:event.target?.tagName,archive:!!event.target?.closest?.('[data-droy-session-archive]'),"
                + "row:!!event.target?.closest?.('[data-droy-session-row]')},geometry:describe()})};"
                + "document.addEventListener('click',afterCapture,true);probe.pointerListeners.push(()=>document.removeEventListener('click',afterCapture,true));"
                + "probe.scrollListener=()=>boundedPush(probe.scrolls,{scrollTop:describe().scrollTop,expanded:describe().expanded});"
                + "window.addEventListener('droy-mobile-session-action',probe.actionListener);"
                + "document.addEventListener('scroll',probe.scrollListener,true)})()");
        try {
            ui.tap(selector);
            DeviceUi.await("The genuine archive operation did not remove the row", 10000,
                    () -> !ui.truth("!!document.querySelector(" + JSONObject.quote(row(id)) + ")"));
        } catch (AssertionError failure) {
            JSONObject diagnostic = (JSONObject) ui.evaluate("(()=>{const p=window.__droyArchiveProbe;return {before:p.before,after:p.describe(),events:p.events,touches:p.touches,scrolls:p.scrolls,eventCounts:p.eventCounts,captureClicks:p.captureClicks,postCaptureClicks:p.postCaptureClicks,bubbleClicks:p.bubbleClicks}})()");
            diagnostic.put("nativeLayers", nativeTouchLayers());
            throw new AssertionError(failure.getMessage() + "; safe archive state=" + diagnostic, failure);
        } finally {
            ui.evaluate("(()=>{const p=window.__droyArchiveProbe;if(!p)return;window.removeEventListener('droy-mobile-session-action',p.actionListener);"
                    + "for(const remove of p.pointerListeners)remove();document.removeEventListener('scroll',p.scrollListener,true);delete window.__droyArchiveProbe})()");
        }
    }

    private void assertTouchTarget(String selector) throws Exception {
        ui.visibleWebBounds(selector);
        JSONObject dimensions = (JSONObject) ui.evaluate("(()=>{const r=document.querySelector(" + JSONObject.quote(selector)
                + ").getBoundingClientRect();return {width:r.width,height:r.height,ratio:window.__droyTouchConfig.dpToCss}})()");
        assertTrue("Touch target is narrower than 48 Android dp: " + selector,
                dimensions.getDouble("width") / dimensions.getDouble("ratio") >= 47.5);
        assertTrue("Touch target is shorter than 48 Android dp: " + selector,
                dimensions.getDouble("height") / dimensions.getDouble("ratio") >= 47.5);
    }

    private void assertKeyboardStaysClosed(String message) {
        long deadline = SystemClock.elapsedRealtime() + 700;
        do { assertFalse(message, ui.keyboardVisible()); SystemClock.sleep(50); }
        while (SystemClock.elapsedRealtime() < deadline);
    }

    private void traceTouchFlow(String name, TouchFlow flow) throws Exception {
        ui.evaluate("""
                (()=>{
                  const trace=window.__droyGestureTrace={events:[],restores:[],listeners:[],moves:0,backCalls:0};
                  const element=e=>e instanceof Element?{
                    tag:e.tagName,role:e.getAttribute('role'),inert:!!e.closest('[inert]'),
                    row:!!e.closest('[data-droy-session-row]'),surface:!!e.closest('[data-droy-session-surface]'),
                    header:!!e.closest('[data-droy-drawer-header]'),backdrop:!!e.closest('[data-droy-backdrop]'),
                    action:!!e.closest('[data-droy-swipe-action]'),button:!!e.closest('button'),
                    editable:!!e.closest('input,textarea,[contenteditable="true"]')
                  }:null;
                  const box=e=>{if(!e)return null;const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {
                    rect:{x:r.x,y:r.y,width:r.width,height:r.height},transform:s.transform,
                    visibility:s.visibility,display:s.display,pointerEvents:s.pointerEvents,touchAction:s.touchAction,
                    expanded:e.getAttribute('data-droy-row-expanded'),inert:!!e.inert,
                    offset:s.getPropertyValue('--droy-row-offset')
                  }};
                  const state=()=>{
                    const root=getComputedStyle(document.documentElement),expanded=document.querySelector('[data-droy-row-expanded]');
                    const selection=window.getSelection(),anchor=selection?.anchorNode,anchorElement=anchor?.nodeType===Node.ELEMENT_NODE?anchor:anchor?.parentElement;
                    return {
                      drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]'),
                      dragging:document.querySelector('[data-droy-frame]')?.getAttribute('data-droy-dragging'),
                      drawer:box(document.querySelector('[data-droy-sidebar]')),expanded:box(expanded),
                      other:box(document.querySelector('[data-droy-session-row="droy-touch-26"]')),
                      surface:box(expanded?.querySelector('[data-droy-session-surface]')||document.querySelector('[data-droy-session-row="droy-touch-26"] [data-droy-session-surface]')),
                      active:element(document.activeElement),
                      cssIme:root.getPropertyValue('--droy-ime-bottom'),safeBottom:root.getPropertyValue('--droy-safe-bottom'),
                      viewport:{width:innerWidth,height:innerHeight},lastPointer:window.__droyTouchProbe,
                      selection:{type:selection?.type,rangeCount:selection?.rangeCount,isCollapsed:selection?.isCollapsed,
                        anchorInHeader:!!anchorElement?.closest('[data-droy-drawer-header]'),anchorInRow:!!anchorElement?.closest('[data-droy-session-row]')},
                      layers:[...document.querySelectorAll('[data-shortcut-modal],[aria-modal="true"],[role=dialog],[role=alertdialog],[role=menu],[data-trigger-menu]')]
                        .filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0&&getComputedStyle(e).visibility!=='hidden'})
                        .slice(0,8).map(e=>({...element(e),settings:e.hasAttribute('data-droy-settings'),sidebar:e.hasAttribute('data-droy-sidebar'),shortcut:e.hasAttribute('data-shortcut-modal'),trigger:e.hasAttribute('data-trigger-menu')}))
                    };
                  };
                  const push=event=>{trace.events.push(event);if(trace.events.length>32)trace.events.shift()};
                  trace.state=state;trace.initial=state();
                  const listen=(target,type,handler)=>{target.addEventListener(type,handler,true);trace.listeners.push(()=>target.removeEventListener(type,handler,true))};
                  const pointer=event=>{
                    if(event.type==='pointermove'&&++trace.moves%4!==0)return;
                    const hit=document.elementFromPoint(event.clientX,event.clientY),row=document.querySelector('[data-droy-row-expanded]');
                    push({type:event.type,id:event.pointerId,pointerType:event.pointerType,trusted:event.isTrusted,
                      x:event.clientX,y:event.clientY,buttons:event.buttons,target:element(event.target),hit:element(hit),
                      targetCapture:event.target?.hasPointerCapture?.(event.pointerId),
                      drawerCapture:document.querySelector('[data-droy-sidebar]')?.hasPointerCapture?.(event.pointerId),
                      rowCapture:row?.hasPointerCapture?.(event.pointerId),
                      expanded:!!row,drawerOpen:!!document.querySelector('[data-droy-drawer-open=true]'),
                      drawerTransform:getComputedStyle(document.querySelector('[data-droy-sidebar]')).transform,
                      rowTransform:row?getComputedStyle(row.querySelector('[data-droy-session-surface]')).transform:null});
                  };
                  for(const type of ['pointerdown','pointermove','pointerup','pointercancel','gotpointercapture','lostpointercapture'])listen(window,type,pointer);
                  listen(document,'keydown',event=>{if(['Escape','Backspace','BrowserBack'].includes(event.key))push({type:'key',key:event.key,state:state()})});
                  const wrap=(owner,key)=>{if(typeof owner?.[key]!=='function')return;const original=owner[key];
                    const wrapped=function(...args){if(key==='handleBack')trace.backCalls++;const before=state();try{const result=original.apply(this,args);push({type:key,before,result:typeof result==='boolean'?result:typeof result,after:state()});return result}
                      catch(error){push({type:key,before,error:error?.name,after:state()});throw error}};
                    owner[key]=wrapped;trace.restores.push(()=>{if(owner[key]===wrapped)owner[key]=original});
                  };
                  wrap(window.DroyMobile,'handleBack');wrap(window.DroyTouch,'closeExpandedRow');
                })()
                """);
        try { flow.run(); }
        catch (Exception | AssertionError failure) {
            JSONObject diagnostic = (JSONObject) ui.evaluate("(()=>{const t=window.__droyGestureTrace;return {initial:t.initial,final:t.state(),events:t.events}})()");
            int[] ime = new int[3];
            instrumentation.runOnMainSync(() -> {
                try {
                    android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                    ime[0] = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime()) ? 1 : 0;
                    Field visible = MainActivity.class.getDeclaredField("imeVisible");
                    Field bottom = MainActivity.class.getDeclaredField("imeBottom");
                    visible.setAccessible(true);bottom.setAccessible(true);
                    ime[1] = visible.getBoolean(activity) ? 1 : 0;
                    ime[2] = bottom.getInt(activity);
                } catch (ReflectiveOperationException error) { throw new AssertionError("Could not inspect cached IME geometry", error); }
            });
            diagnostic.put("nativeImeVisible", ime[0] == 1).put("cachedImeVisible", ime[1] == 1).put("cachedImeBottom", ime[2]);
            diagnostic.put("nativeLayers", nativeTouchLayers());
            diagnostic.put("beforeBack", ui.beforeBack).put("afterBack", ui.nativeNavigationState());
            throw new AssertionError(failure.getMessage() + "; safe " + name + " touch trace=" + diagnostic, failure);
        } finally {
            ui.evaluate("(()=>{const t=window.__droyGestureTrace;if(!t)return;for(const restore of t.restores.reverse())restore();"
                    + "for(const remove of t.listeners)remove();delete window.__droyGestureTrace})()");
        }
    }

    private interface TouchFlow { void run() throws Exception; }

    private JSONObject nativeTouchLayers() throws Exception {
        JSONObject result = new JSONObject().put("imeVisible", ui.keyboardVisible());
        JSONArray windows = new JSONArray();
        try {
            List<AccessibilityWindowInfo> active = instrumentation.getUiAutomation().getWindows();
            for (int index = 0; index < active.size() && index < 8; index++) {
                AccessibilityWindowInfo window = active.get(index);
                try {
                    windows.put(new JSONObject().put("type", window.getType()).put("active", window.isActive())
                            .put("focused", window.isFocused()).put("nodes", nativeNodeMetadata(window.getRoot())));
                } finally { window.recycle(); }
            }
            if (active.isEmpty()) result.put("activeRoot", nativeNodeMetadata(instrumentation.getUiAutomation().getRootInActiveWindow()));
        } catch (RuntimeException error) {
            result.put("probeError", error.getClass().getSimpleName());
        }
        return result.put("windows", windows);
    }

    private JSONObject nativeNodeMetadata(AccessibilityNodeInfo root) throws Exception {
        JSONObject result = new JSONObject();
        if (root == null) return result.put("present", false);
        result.put("present", true).put("rootClass", String.valueOf(root.getClassName())).put("rootActions", root.getActions());
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        JSONArray systemActions = new JSONArray();
        pending.add(root);
        int visited = 0;
        try {
            while (!pending.isEmpty() && visited < 256) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    visited++;
                    String className = String.valueOf(node.getClassName());
                    if (classes.size() < 24) classes.add(className);
                    String id = node.getViewIdResourceName();
                    if (systemActions.length() < 32 && id != null && (id.startsWith("android:id/") || id.startsWith("com.android.systemui:id/")))
                        systemActions.put(new JSONObject().put("class", className).put("id", id).put("actions", node.getActions()));
                    for (int child = 0; child < node.getChildCount(); child++) {
                        AccessibilityNodeInfo item = node.getChild(child);
                        if (item != null) pending.addLast(item);
                    }
                } finally { node.recycle(); }
            }
            result.put("limited", !pending.isEmpty());
        } finally { while (!pending.isEmpty()) pending.removeFirst().recycle(); }
        return result.put("visited", visited).put("classes", new JSONArray(classes)).put("systemActions", systemActions);
    }

    private void tapVisibleText(String selector, String chinese, String english) throws Exception {
        String labels = "[" + JSONObject.quote(chinese) + "," + JSONObject.quote(english) + "]";
        markVisibleTarget("[...document.querySelectorAll(" + JSONObject.quote(selector) + ")].find(e=>visible(e)&&" + labels
                + ".some(label=>label.toLowerCase()===e.textContent.trim().toLowerCase()))");
        ui.tap("[data-droy-test-target]");
    }

    private void markVisibleTarget(String expression) throws Exception {
        DeviceUi.await("The original DSH action did not become touchable", 5000,
                () -> ui.truth("(()=>{document.querySelectorAll('[data-droy-test-target]').forEach(e=>e.removeAttribute('data-droy-test-target'));"
                        + "const visible=e=>e.getClientRects().length>0&&getComputedStyle(e).visibility!=='hidden'&&!e.closest('[inert]');const e=" + expression
                        + ";if(!e)return false;e.dataset.droyTestTarget='true';return true})()"));
    }

    private JSONObject runFixtureProfile(boolean seed) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        String script = """
                import {pathToFileURL} from 'node:url';
                const base=process.env.DROY_RUNTIME_DIR;
                await import(pathToFileURL(base+'/mobile/bootstrap.mjs'));
                const {runProfile}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh/lib/profile-boot.js'));
                const {createLaunchEnvironmentSnapshot}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh-launch-environment/lib/index.js'));
                const {createUserMessage}=await import(pathToFileURL(base+'/node_modules/@deepseek-ai/dsh-llm/lib/index.js'));
                const instance=await runProfile({profile:'mobile',patchFiles:[],args:['--no-open'],environment:createLaunchEnvironmentSnapshot([{source:'process',values:process.env}])});
                try {
                  const {ctx}=instance,workspace=ctx.workspaceRegistry.list()[0];
                  if(process.env.DROY_TOUCH_SEED==='1') {
                    if((await ctx.sessionPersistence.list()).length!==0)throw Error('Fixture home was not empty');
                    for(let i=0;i<28;i++) {
                      const id='droy-touch-'+String(i).padStart(2,'0');
                      const session=ctx.sessions.create(id,{meta:{cwd:workspace.path,agentPreset:'standard',createdAt:1700000000000+i}});
                      const writer=await ctx.sessionPersistence.create(session.header);
                      session.append('turn/start',{turn:1});
                      session.append('user/message',createUserMessage({source:{kind:'user'},content:[{type:'text',text:'Fictional historical touch fixture. Never submitted to a model.'}]}),{surfaceOp:'append'});
                      session.append('turn/end',{turn:1,reason:{kind:'blocked'}});
                      ctx.sessionTitle.rename(session,'Touch fixture '+String(i).padStart(2,'0'));
                      await ctx.sessions.flush(session);await writer.close();await workspace.attachSession(id);
                    }
                  }
                  const headers=(await ctx.sessionPersistence.list()).map(s=>s.header).filter(h=>h.id.startsWith('droy-touch-'));
                  let turns=0,modelOrToolEvents=0;
                  for(const header of headers) {
                    const handle=await ctx.sessionPersistence.open(header.id,'read');
                    try {
                      const {events}=await handle.read();
                      turns+=events.filter(e=>e.type==='turn/start').length;
                      modelOrToolEvents+=events.filter(e=>['request/header','assistant/attempt','assistant/message','tool/call'].includes(e.type)).length;
                    } finally {await handle.close();}
                  }
                  console.log('DROY_TOUCH_RESULT '+JSON.stringify({sessions:headers.length,workspaces:ctx.workspaceRegistry.list().length,accounted:workspace.sessionIds.filter(id=>id.startsWith('droy-touch-')).length,turns,modelOrToolEvents}));
                } finally {await instance.shutdown.shutdown(0);}
                """;
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder = new ProcessBuilder(nativeDir + "/libdroy_node.so", "--expose-internals", "--input-type=module", "-e", script);
        builder.directory(context.getFilesDir()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("DSH_HOME", isolatedHome.getAbsolutePath());
        env.put("DROY_RUNTIME_DIR", runtime.getAbsolutePath());
        env.put("DROY_NATIVE_DIR", nativeDir);
        env.put("LD_LIBRARY_PATH", nativeDir);
        env.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        env.put("DSH_TELEMETRY_DISABLED", "1");
        env.put("DROY_WEB_TOKEN", "fictional-touch-ui-fixture-token-123456789");
        env.put("DROY_BRIDGE_TOKEN", "fictional-touch-ui-fixture-token-123456789");
        env.put("DROY_WEB_PORT", Integer.toString(port));
        env.put("DROY_BRIDGE_PORT", "9");
        env.put("DROY_TOUCH_SEED", seed ? "1" : "0");
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) { RuntimeInstaller.copy(input, output); }
            catch (Exception error) { throw new AssertionError("Could not read the fictional fixture process", error); }
        }, "droy-touch-fixture-output");
        reader.start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();process.waitFor(5, TimeUnit.SECONDS);
            fail("The isolated public-API DSH fixture timed out");
        }
        reader.join(2000);
        String value = output.toString(StandardCharsets.UTF_8.name());
        // Only this process's isolated fictional profile contributes output.
        // Bound it so a fixture bootstrap failure remains useful to diagnose.
        assertEquals("The isolated public-API DSH fixture failed: "
                + value.substring(Math.max(0, value.length() - 2500)), 0, process.exitValue());
        String prefix = "DROY_TOUCH_RESULT ";
        int marker = value.lastIndexOf(prefix);
        assertTrue("The DSH fixture did not report its safe session counters", marker >= 0);
        return new JSONObject(value.substring(marker + prefix.length()).split("\\R", 2)[0]);
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return result;
        List<Path> paths = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) { stream.forEach(paths::add); }
        Collections.sort(paths);
        for (Path path : paths) {
            String relative = root.relativize(path).toString();
            if (Files.isSymbolicLink(path)) result.put(relative, "link:" + Files.readSymbolicLink(path));
            else if (Files.isDirectory(path)) result.put(relative, "directory");
            else result.put(relative, RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
        }
        return result;
    }
}
