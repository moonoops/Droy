package io.droyagent.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Non-destructive inspection of the installed chat's media command entries. */
@RunWith(AndroidJUnit4.class)
public final class MediaMenuDeviceTest {
    private static final String MENU = "[data-trigger-menu]";
    private static final String ALBUM = "[data-media-menu-test=album]";
    private static final String CAMERA = "[data-media-menu-test=camera]";
    private final List<Instrumentation.ActivityMonitor> monitors = new ArrayList<>();
    private Instrumentation instrumentation;
    private Context context;
    private DeviceUi ui;

    @After public void cleanUp() throws Exception {
        if (instrumentation != null) for (Instrumentation.ActivityMonitor monitor : monitors) instrumentation.removeMonitor(monitor);
        if (ui != null && ui.web() != null && ui.truth("!!document.querySelector('" + MENU + "')")) {
            ui.pressBack();
            DeviceUi.await("The media menu did not close during cleanup", 5000,
                () -> !ui.truth("!!document.querySelector('" + MENU + "')"));
        }
    }

    @Test public void mediaEntriesLeadWithReadableIconsAndCancelledPickersPreserveDraft() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertIdle();
        MainActivity activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        String before = draftHash();
        Instrumentation.ActivityMonitor album = cancelledPicker(MediaStore.ACTION_PICK_IMAGES, true, false);
        Instrumentation.ActivityMonitor document = cancelledPicker(Intent.ACTION_OPEN_DOCUMENT, true, true);
        Instrumentation.ActivityMonitor camera = cancelledPicker(MediaStore.ACTION_IMAGE_CAPTURE, false, false);

        openMenu();
        assertInitialMediaRows();
        captureMenuRows();
        ui.pressBack();
        DeviceUi.await("System Back did not close the add menu", 5000,
            () -> !ui.truth("!!document.querySelector('" + MENU + "')"));
        assertEquals("Opening and closing the menu changed the session or draft", before, draftHash());

        openMenu(); assertInitialMediaRows(); assertIdle();
        ui.tap(ALBUM);
        DeviceUi.await("Album did not invoke its Android picker", 5000,
            () -> album.getHits() + document.getHits() == 1);
        DeviceUi.await("Cancelling album selection changed the session or draft", 5000, () -> before.equals(draftHash()));
        openMenu(); assertInitialMediaRows(); assertIdle();
        ui.tap(CAMERA);
        DeviceUi.await("Camera did not invoke its Android capture intent", 5000, () -> camera.getHits() == 1);
        DeviceUi.await("Cancelling capture changed the session or draft", 5000, () -> before.equals(draftHash()));
        if (ui.keyboardVisible()) {
            ui.pressBack();
            DeviceUi.await("System Back did not dismiss the keyboard", 5000, () -> !ui.keyboardVisible());
        }
        assertEquals("Media menu inspection changed the session or draft", before, draftHash());
        Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEDIA_MENU_DRAFT_SHA256 " + before + "\n");
        instrumentation.sendStatus(2, status);
    }

    private void assertIdle() throws Exception {
        assertFalse("Wait for the active task to finish before testing the media menu", ToolProgress.current(context).getBoolean("active"));
    }

    private void openMenu() throws Exception {
        assertIdle();
        ui.hideKeyboard();
        ui.tap("[data-droy-composer-add]");
        DeviceUi.await("The real add-button touch did not open the menu", 5000,
            () -> ui.truth("document.querySelectorAll('" + MENU + " [role=option]').length>=2"));
        awaitPaint();
    }

    private void assertInitialMediaRows() throws Exception {
        assertTrue("The first two menu entries must be album then camera, initially visible with icons and complete descriptions", ui.truth("(()=>{"
            + "const menu=document.querySelector('" + MENU + "'),list=menu?.querySelector('[role=listbox]'),rows=[...menu.querySelectorAll('[role=option]')];"
            + "if(!list||rows.length<2||list.scrollTop>1)return false;const expected=[['相册','从相册选择图片上传','album'],['拍照','使用相机拍照上传','camera']];"
            + "return expected.every(([label,description,key],index)=>{const row=rows[index],b=list.getBoundingClientRect(),r=row.getBoundingClientRect();"
            + "const spans=[...row.querySelectorAll('span')],name=spans.find(e=>e.textContent.trim()===label),detail=spans.find(e=>e.textContent.trim()===description),icon=row.querySelector('svg');"
            + "if(!name||!detail||!icon||icon.getBoundingClientRect().width<=0||icon.getBoundingClientRect().height<=0||r.top<b.top-1||r.bottom>b.bottom+1)return false;"
            + "for(const text of [name,detail]){const range=document.createRange();range.selectNodeContents(text);const rects=[...range.getClientRects()];if(!rects.length)return false;"
            + "for(const rect of rects){if(rect.left<r.left-1||rect.right>r.right+1||rect.top<b.top-1||rect.bottom>b.bottom+1)return false;"
            + "for(let parent=text;parent&&parent!==menu.parentElement;parent=parent.parentElement){const s=getComputedStyle(parent),p=parent.getBoundingClientRect();"
            + "if(/hidden|clip|auto|scroll/.test(s.overflowX)&&(rect.left<p.left-1||rect.right>p.right+1))return false;"
            + "if(/hidden|clip|auto|scroll/.test(s.overflowY)&&(rect.top<p.top-1||rect.bottom>p.bottom+1))return false}}}"
            + "const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);if(!hit||!(row===hit||row.contains(hit)))return false;"
            + "row.setAttribute('data-media-menu-test',key);return true})})()"));
    }

    private Instrumentation.ActivityMonitor cancelledPicker(String action, boolean mime, boolean openable) throws Exception {
        IntentFilter filter = new IntentFilter(action);
        if (mime) filter.addDataType("*/*");
        if (openable) filter.addCategory(Intent.CATEGORY_OPENABLE);
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor(filter,
            new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        instrumentation.addMonitor(monitor); monitors.add(monitor);
        return monitor;
    }

    private String draftHash() throws Exception {
        String value = String.valueOf(ui.evaluate("JSON.stringify([...document.querySelectorAll('[data-conversation-session]')].filter(e=>e.getClientRects().length>0).map(e=>({"
            + "id:e.getAttribute('data-conversation-session'),drafts:[...e.querySelectorAll('[data-composer-input]')].map(n=>n.textContent),"
            + "attachments:[...e.querySelectorAll('[data-composer-seat] img')].map(n=>({src:n.getAttribute('src'),alt:n.getAttribute('alt')}))})))"));
        return RuntimeInstaller.hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void awaitPaint() throws Exception {
        DeviceUi.await("Finite menu animations have not finished", 5000,
            () -> ui.truth("document.getAnimations().every(a=>!((a.playState==='running'||a.pending)&&a.effect?.getTiming().iterations!==Infinity&&!/caret|cursor/i.test(a.animationName||'')))"));
        ui.evaluate("window.__mediaMenuCaptured=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__mediaMenuCaptured=true))");
        DeviceUi.await("The menu did not reach a painted frame", 5000, () -> ui.truth("window.__mediaMenuCaptured"));
        CountDownLatch painted = new CountDownLatch(1);
        WebView web = ui.web();
        instrumentation.runOnMainSync(() -> web.postVisualStateCallback(0, new WebView.VisualStateCallback() {
            @Override public void onComplete(long id) { painted.countDown(); }
        }));
        assertTrue("The menu frame did not commit", painted.await(5, TimeUnit.SECONDS));
    }

    private void captureMenuRows() throws Exception {
        awaitPaint();
        Rect region = ui.webBounds(ALBUM); region.union(ui.webBounds(CAMERA));
        Bitmap full = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(full);
        Bitmap cropped = null;
        try {
            assertTrue("The media rows are outside the screenshot", region.intersect(0, 0, full.getWidth(), full.getHeight()));
            cropped = Bitmap.createBitmap(full, region.left, region.top, region.width(), region.height());
            File directory = new File(context.getExternalFilesDir(null), "media-menu-evidence");
            assertTrue(directory.isDirectory() || directory.mkdirs());
            File output = new File(directory, "media-menu-" + UUID.randomUUID() + ".png");
            try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
            Bundle status = new Bundle(); status.putString("stream", "\nDROY_MEDIA_MENU_EVIDENCE " + output.getAbsolutePath() + "\n");
            instrumentation.sendStatus(2, status);
        } finally { if (cropped != null && cropped != full) cropped.recycle(); full.recycle(); }
    }
}
