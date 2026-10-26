package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import fi.iki.elonen.NanoHTTPD;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises the actual WebView with a local fixture; never starts DSH or a model. */
@RunWith(AndroidJUnit4.class)
public class WebViewRecoveryDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private Fixture server;
    private String previousStatus, previousToken;
    private int previousPort;
    private boolean fixtureActive;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Stop the engine before running WebView fixture tests", RuntimeService.canManageHistory());
        assertFalse(RuntimeService.ready);
        previousStatus = RuntimeService.status;
        previousToken = RuntimeService.webToken;
        previousPort = RuntimeService.webPort;
        server = new Fixture();
        server.start(5000, false);
        RuntimeService.webPort = server.getListeningPort();
        RuntimeService.webToken = "synthetic-webview-recovery-token-123456789";
        RuntimeService.status = "本地页面恢复测试";
        RuntimeService.ready = true;
        fixtureActive = true;
    }

    @After public void tearDown() {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        if (!fixtureActive) return;
        RuntimeService.ready = false;
        RuntimeService.status = previousStatus;
        RuntimeService.webToken = previousToken;
        RuntimeService.webPort = previousPort;
        if (server != null) { server.releaseDelayed.countDown(); server.stop(); }
    }

    @Test public void mainFrameHttpFailureRequiresManualRetryAndPreservesNativeNavigation() throws Exception {
        server.failPage = true;
        openActivity();
        await("HTTP failure did not show native recovery", () -> retryButton() != null);
        assertFalse("The startup cover must not block HTTP recovery", deviceUi().loadingOverlayShown());
        WebView failed = currentWeb();
        int failedRequests = server.pageRequests;
        SystemClock.sleep(1500);
        assertEquals("Refresh must not retry failed requests automatically; safe requests=" + server.safeRequestHistogram(),
                failedRequests, server.pageRequests);
        DeviceUi ui = new DeviceUi(instrumentation, activity);
        ui.invoke("showDiagnostics");
        instrumentation.runOnMainSync(activity::onBackPressed);
        assertNotNull("Returning from diagnostics lost the recovery UI", retryButton());
        server.failPage = false;
        click("重新加载对话");
        assertPageLoaded();
        assertNotSame("Retry must isolate late callbacks from the failed page", failed, currentWeb());
        assertEquals("Only the manual retry should reload the page; safe requests=" + server.safeRequestHistogram(),
                failedRequests + 1, server.pageRequests);
    }

    @Test public void newEngineSessionIgnoresDelayedFailureFromPreviousNavigation() throws Exception {
        server.delayFirstPage = true;
        openActivity();
        await("First navigation did not reach the fixture", () -> server.pageRequests == 1);
        WebView previous = currentWeb();
        RuntimeService.webToken += "-new-session";
        assertPageLoaded();
        assertNotSame("A new engine session must replace the previous navigation", previous, currentWeb());
        server.releaseDelayed.countDown();
        assertTrue(server.delayedReturned.await(5, TimeUnit.SECONDS));
        SystemClock.sleep(1000);
        assertPageLoaded();
    }

    @Test public void subresourceHttpFailureDoesNotReplaceWorkingConversation() throws Exception {
        openActivity();
        assertPageLoaded();
        await("Fixture image failure was not requested", () -> server.imageRequests > 0);
        assertNull("A failed optional image must not replace the main page", retryButton());
    }

    @Test public void successfulDocumentWaitsForExplicitUiReadyBeforeRevealingSameWebView() throws Exception {
        server.deferUiReady = true;
        openActivity();
        await("The deferred fixture document did not load", this::fixtureDocumentLoaded);
        WebView original = currentWeb();
        DeviceUi ui = deviceUi();
        // HTTP success and onPageFinished do not establish that the composer,
        // theme and mobile layout are ready to show.
        SystemClock.sleep(500);
        assertFalse("HTTP success falsely completed UI readiness", ui.nativeUiReady());
        assertTrue("The unfinished conversation must remain covered", ui.loadingOverlayShown());
        instrumentation.runOnMainSync(() -> {
            View cover = activity.getWindow().getDecorView().findViewWithTag("droy-chat-loading");
            assertNotNull(cover);
            assertTrue("The startup cover must block touches to the unfinished page", cover.isClickable());
            assertEquals("The startup cover must be fully opaque", 1f, cover.getAlpha(), 0f);
            assertEquals("The startup cover must fill the conversation width", original.getWidth(), cover.getWidth());
            assertEquals("The startup cover must fill the conversation height", original.getHeight(), cover.getHeight());
            assertSame(original.getParent(), cover.getParent());
            ViewGroup parent = (ViewGroup) cover.getParent();
            assertTrue("The startup cover must be above the WebView", parent.indexOfChild(cover) > parent.indexOfChild(original));
            assertTrue("The startup cover needs an opaque background", cover.getBackground() instanceof ColorDrawable);
            int coverColor = ((ColorDrawable) cover.getBackground()).getColor();
            assertEquals("An unfinished sidebar must not show through the startup cover", 255, Color.alpha(coverColor));
            View root = (View) parent.getParent();
            assertTrue(root.getBackground() instanceof ColorDrawable);
            assertEquals("The cover and system insets must share one background", ((ColorDrawable) root.getBackground()).getColor(), coverColor);
            View decor = activity.getWindow().getDecorView();
            int[] coverOrigin = new int[2], windowOrigin = new int[2];
            cover.getLocationOnScreen(coverOrigin); decor.getLocationOnScreen(windowOrigin);
            assertArrayEquals("The startup cover must begin at the full window origin", windowOrigin, coverOrigin);
            assertEquals("The startup cover must paint behind both side safe areas", decor.getWidth(), cover.getWidth());
            assertEquals("The startup cover must paint behind both system bars", decor.getHeight(), cover.getHeight());
        });
        ui.evaluate("window.__fixtureReveal()");
        assertPageLoaded();
        assertSame("Revealing a ready conversation must preserve its WebView", original, currentWeb());
    }

    @Test public void rendererCrashIsHandledAndRetryCreatesUsableWebView() throws Exception {
        openActivity();
        assertPageLoaded();
        WebView crashed = currentWeb();
        instrumentation.runOnMainSync(() -> crashed.loadUrl("chrome://crash"));
        await("Renderer crash did not leave the activity recoverable", () -> retryButton() != null);
        assertFalse("The startup cover must not block crash recovery", deviceUi().loadingOverlayShown());
        assertFalse("Renderer crash destroyed the native activity", activity.isDestroyed());
        assertNull("Dead renderer must not remain attached", currentWeb());
        click("重新加载对话");
        assertPageLoaded();
        assertNotSame("A crashed WebView cannot be reused", crashed, currentWeb());
    }

    private void openActivity() {
        activity = (MainActivity) instrumentation.startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void assertPageLoaded() throws Exception {
        await("Fixture page did not finish loading and reveal", () -> fixtureDocumentLoaded()
                && deviceUi().nativeUiReady() && !deviceUi().loadingOverlayShown());
        assertNull("A working page still shows the native recovery UI", retryButton());
    }

    private boolean fixtureDocumentLoaded() throws Exception {
        WebView view = currentWeb();
        if (view == null) return false;
        AtomicReference<String> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> view.evaluateJavascript(
                "document.getElementById('fixture-ready')?.textContent || ''",
                value -> { result.set(value); done.countDown(); }));
        return done.await(2, TimeUnit.SECONDS) && "\"fixture ready\"".equals(result.get());
    }

    private DeviceUi deviceUi() { return new DeviceUi(instrumentation, activity); }

    private WebView currentWeb() {
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

    private Button retryButton() {
        AtomicReference<Button> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> result.set(findButton(activity.getWindow().getDecorView(), "重新加载对话")));
        return result.get();
    }

    private void click(String label) {
        instrumentation.runOnMainSync(() -> {
            Button button = findButton(activity.getWindow().getDecorView(), label);
            assertNotNull("Missing button: " + label, button);
            button.performClick();
        });
    }

    private static Button findButton(View view, String label) {
        if (view instanceof Button button && label.contentEquals(button.getText())) return button;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                Button found = findButton(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void await(String message, Condition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 20000;
        do {
            if (condition.check()) return;
            SystemClock.sleep(50);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail(message);
    }

    private interface Condition { boolean check() throws Exception; }

    private static final class Fixture extends NanoHTTPD {
        volatile boolean failPage;
        volatile boolean delayFirstPage;
        volatile boolean deferUiReady;
        volatile int pageRequests, imageRequests;
        private final Map<String, Integer> requestHistogram = new TreeMap<>();
        final CountDownLatch releaseDelayed = new CountDownLatch(1), delayedReturned = new CountDownLatch(1);
        Fixture() { super("127.0.0.1", 0); }
        private synchronized void recordRequest(IHTTPSession session) {
            String uri = session.getUri();
            int query = uri.indexOf('?');
            if (query >= 0) uri = uri.substring(0, query);
            String destination = session.getHeaders().getOrDefault("sec-fetch-dest", "empty");
            if (!java.util.Set.of("document", "image", "empty").contains(destination)) destination = "other";
            String key = session.getMethod().name() + " " + uri + " dest=" + destination;
            requestHistogram.merge(key, 1, Integer::sum);
        }
        synchronized String safeRequestHistogram() { return requestHistogram.toString(); }
        @Override public Response serve(IHTTPSession session) {
            recordRequest(session);
            Response response = serveResponse(session);
            // Isolate navigation from fixture keep-alive handling when WebView
            // cancels an error page. A real retry still reaches these counters.
            response.closeConnection(true);
            return response;
        }
        private Response serveResponse(IHTTPSession session) {
            if (session.getUri().equals("/missing-image")) {
                imageRequests++;
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "fixture image missing");
            }
            if (session.getUri().equals("/droy/preferences")) return newFixedLengthResponse("{}");
            if (!session.getUri().equals("/"))
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "fixture path missing");
            pageRequests++;
            if (delayFirstPage && pageRequests == 1) {
                try { releaseDelayed.await(15, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                delayedReturned.countDown();
                return newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "old navigation failed");
            }
            Response response = failPage
                    ? newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "fixture unavailable")
                    : newFixedLengthResponse(Response.Status.OK, "text/html",
                        "<!doctype html><html><head><meta name='viewport' content='width=device-width'></head>"
                        + "<body><p id='fixture-ready'>fixture ready</p><img src='/missing-image'>"
                        + "<script>window.__fixtureReveal=()=>{document.documentElement.dataset.droyUiReady='true';"
                        + "DroyNative.postMessage(" + JSONObject.quote("{\"id\":\"fixture-ui-ready\",\"action\":\"uiReady\",\"args\":{}}") + ");};"
                        + (deferUiReady ? "" : "requestAnimationFrame(()=>requestAnimationFrame(window.__fixtureReveal));")
                        + "</script></body></html>");
            response.addHeader("Cache-Control", "no-store");
            return response;
        }
    }
}
