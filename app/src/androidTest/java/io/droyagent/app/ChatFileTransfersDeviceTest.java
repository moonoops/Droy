package io.droyagent.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.MediaStore;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.json.JSONObject;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Confirms selected-file uploads work without enabling arbitrary content:// reads. */
@RunWith(AndroidJUnit4.class)
public final class ChatFileTransfersDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private Activity activity;
    private WebView web;
    private ChatFileTransfers transfers;
    private Uri file;
    private Instrumentation.ActivityMonitor picker;
    private final AtomicReference<Exception> error = new AtomicReference<>();
    private final CountDownLatch chooser = new CountDownLatch(1);

    @Before public void start() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, "droy-upload-test-" + UUID.randomUUID() + ".txt");
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/DroyAgentTests");
        file = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(file);
        try (var output = context.getContentResolver().openOutputStream(file)) {
            assertNotNull(output); output.write("Selected upload fixture 你好".getBytes(StandardCharsets.UTF_8));
        }
        context.grantUriPermission(context.getPackageName(), file, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity = instrumentation.startActivitySync(new Intent(context, TestPageActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        IntentFilter documentPicker = new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        documentPicker.addCategory(Intent.CATEGORY_OPENABLE);
        documentPicker.addDataType("*/*");
        picker = new Instrumentation.ActivityMonitor(documentPicker,
            new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        instrumentation.addMonitor(picker);
        CountDownLatch loaded = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> {
            transfers = new ChatFileTransfers(activity, 31234, () -> true, (title, failure) -> error.set(failure));
            web = new WebView(activity);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.setWebChromeClient(new WebChromeClient() {
                @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                    boolean handled = transfers.chooseFiles(callback, params);
                    chooser.countDown(); return handled;
                }
            });
            web.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView view, String address) { loaded.countDown(); }
            });
            activity.setContentView(web);
            web.loadDataWithBaseURL("http://127.0.0.1:31234/", "<meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0;padding:80px 24px'><input id='file' type='file' style='width:260px;height:48px;font-size:18px'><script>"
                + "window.uploadText='pending';document.getElementById('file').onchange=async e=>{try{window.uploadText=await e.target.files[0].text()}"
                + "catch(error){window.uploadText='error:'+error.message}};</script>", "text/html", "UTF-8", null);
        });
        assertTrue(loaded.await(10, TimeUnit.SECONDS));
        CountDownLatch painted=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->web.postVisualStateCallback(1,new WebView.VisualStateCallback(){
            @Override public void onComplete(long requestId){
                web.getViewTreeObserver().registerFrameCommitCallback(painted::countDown);
                web.requestFocus();web.invalidate();
            }
        }));
        assertTrue("File-input page was not committed to the visible surface",painted.await(10,TimeUnit.SECONDS));
    }

    @After public void stop() {
        if (picker != null) instrumentation.removeMonitor(picker);
        if (activity != null) instrumentation.runOnMainSync(() -> {
            if (transfers != null) transfers.close();
            if (web != null) web.destroy();
            activity.finish();
        });
        if (file != null) {
            context.revokeUriPermission(file, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.getContentResolver().delete(file, null, null);
        }
    }

    @Test public void selectedContentFileCanBeReadWhileGeneralContentAccessStaysDisabled() throws Exception {
        tapFileInput();
        assertTrue(chooser.await(5, TimeUnit.SECONDS));
        assertTrue("Picker must protect its human file-selection controls", ChatFileTransfers.isPickerOpen());
        assertTrue("Picker must keep its local upload/download endpoint running", ChatFileTransfers.hasRuntimeWork());
        instrumentation.runOnMainSync(() -> transfers.onActivityResult(601, Activity.RESULT_OK,
            new Intent().setData(file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        assertNull(error.get());
        DeviceUi.await("WebView could not read the explicitly selected file", 10000,
            () -> evaluate("window.uploadText").equals("\"Selected upload fixture 你好\""));
        assertFalse(ChatFileTransfers.isPickerOpen());
        assertFalse("Completed picker must release its runtime hold", ChatFileTransfers.hasRuntimeWork());
    }

    @Test public void cancelledChooserCompletesAndReleasesProtection() throws Exception {
        tapFileInput();
        assertTrue(chooser.await(5, TimeUnit.SECONDS));
        assertTrue(ChatFileTransfers.hasRuntimeWork());
        instrumentation.runOnMainSync(() -> transfers.onActivityResult(601, Activity.RESULT_CANCELED, null));
        assertFalse(ChatFileTransfers.isPickerOpen());
        assertFalse("Cancelled picker must permit normal background idling", ChatFileTransfers.hasRuntimeWork());
        assertEquals("0", evaluate("document.getElementById('file').files.length"));
        assertNull(error.get());
    }

    @Test public void nativeChooserCancellationRestoresNormalRuntimeIdling() throws Exception {
        AtomicReference<Uri[]> result=new AtomicReference<>(new Uri[]{file});
        instrumentation.runOnMainSync(()->{
            transfers.chooseFiles(result::set,new WebChromeClient.FileChooserParams(){
                @Override public int getMode(){return MODE_OPEN;}
                @Override public String[] getAcceptTypes(){return new String[]{"text/plain"};}
                @Override public boolean isCaptureEnabled(){return false;}
                @Override public CharSequence getTitle(){return "Synthetic fixture";}
                @Override public String getFilenameHint(){return null;}
                @Override public Intent createIntent(){return new Intent(Intent.ACTION_OPEN_DOCUMENT);}
            });
            assertTrue(ChatFileTransfers.isPickerOpen());assertTrue(ChatFileTransfers.hasRuntimeWork());
            transfers.onActivityResult(601,Activity.RESULT_CANCELED,null);
            assertFalse(ChatFileTransfers.isPickerOpen());assertFalse(ChatFileTransfers.hasRuntimeWork());
        });
        assertNull(result.get());assertNull(error.get());
    }

    @Test public void selectedDownloadHoldsRuntimeOnlyUntilResponseFinishes() throws Exception {
        CountDownLatch requested = new CountDownLatch(1), finish = new CountDownLatch(1);
        IntentFilter savePicker = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
        savePicker.addCategory(Intent.CATEGORY_OPENABLE); savePicker.addDataType("*/*");
        Instrumentation.ActivityMonitor saveMonitor = new Instrumentation.ActivityMonitor(savePicker,
            new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        instrumentation.addMonitor(saveMonitor);
        context.grantUriPermission(context.getPackageName(),file,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try (ServerSocket server = new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            AtomicReference<Exception> serverError = new AtomicReference<>();
            Thread response = new Thread(() -> {
                try (var socket=server.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader headers=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                    String line; while ((line=headers.readLine())!=null&&!line.isEmpty()) { }
                    requested.countDown();
                    if (!finish.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Fixture response was not released");
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntest".getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch(Exception failed) { serverError.set(failed); }
            },"download-fixture");
            response.start();
            instrumentation.runOnMainSync(() -> {
                transfers.close();
                transfers=new ChatFileTransfers(activity,server.getLocalPort(),()->true,(title,failed)->error.set(failed));
                transfers.download("http://127.0.0.1:"+server.getLocalPort()+"/droy/memory/archive/download/"+"a".repeat(64),
                    "attachment; filename=fixture.droy","application/octet-stream");
                assertTrue(ChatFileTransfers.hasRuntimeWork());
                transfers.onActivityResult(602,Activity.RESULT_OK,new Intent().setData(file).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            });
            assertTrue(requested.await(5,TimeUnit.SECONDS));
            assertFalse(ChatFileTransfers.isPickerOpen());
            assertTrue("An active document write must preserve the local server",ChatFileTransfers.hasRuntimeWork());
            finish.countDown(); response.join(5000);
            assertFalse(response.isAlive()); assertNull(serverError.get());
            DeviceUi.await("Completed download did not release its runtime hold",5000,()->!ChatFileTransfers.hasRuntimeWork());
            assertNull(error.get());
            try(var input=context.getContentResolver().openInputStream(file)) {
                assertNotNull(input); byte[] saved=new byte[4]; assertEquals(4,input.read(saved));
                assertEquals("test",new String(saved,StandardCharsets.UTF_8)); assertEquals(-1,input.read());
            }
        } finally {
            finish.countDown(); instrumentation.removeMonitor(saveMonitor);
            context.revokeUriPermission(file,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
    }

    private void tapFileInput() throws Exception {
        DeviceUi.await("The upload fixture has no focused window", 5000, () -> {
            boolean[] focused = {false};
            instrumentation.runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
            return focused[0];
        });
        JSONObject rect = new JSONObject(evaluate("(()=>{const r=document.getElementById('file').getBoundingClientRect();return {x:r.left+20,y:r.top+12,width:innerWidth}})()"));
        int[] location = new int[2], width = {0};
        instrumentation.runOnMainSync(() -> { web.getLocationOnScreen(location); width[0] = web.getWidth(); });
        double scale = width[0] / rect.getDouble("width");
        float x = location[0] + (float) (rect.getDouble("x") * scale);
        float y = location[1] + (float) (rect.getDouble("y") * scale);
        int deviceId = Integer.MIN_VALUE;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) { deviceId = id; break; }
        }
        assertNotEquals("The upload fixture requires touchscreen input", Integer.MIN_VALUE, deviceId);
        // File inputs require a trusted user gesture; evaluateJavascript().click()
        // is rejected by Chromium before onShowFileChooser is invoked.
        long downTime = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
            pointer.id = 0; pointer.toolType = MotionEvent.TOOL_TYPE_FINGER;
            MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
            coordinates.x = x; coordinates.y = y; coordinates.size = 1;
            coordinates.pressure = action == MotionEvent.ACTION_UP ? 0 : 1;
            MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
                0, 0, 1, 1, deviceId, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            try { assertTrue("Android rejected the file-input touch", instrumentation.getUiAutomation().injectInputEvent(event, true)); }
            finally { event.recycle(); }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40);
        }
    }

    private String evaluate(String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> web.evaluateJavascript(script, value -> { result.set(value); finished.countDown(); }));
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        return result.get();
    }
}
