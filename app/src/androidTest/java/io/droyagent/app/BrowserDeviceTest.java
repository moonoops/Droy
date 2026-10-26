package io.droyagent.app;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ValueCallback;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual DOM tests with a synthetic page; no model or external website is contacted. */
@RunWith(AndroidJUnit4.class)
public class BrowserDeviceTest {
    private WebView view;
    private BrowserSession session;
    private final AtomicInteger changesDuringRead = new AtomicInteger();
    private final AtomicInteger changedReadCount = new AtomicInteger();
    @Before public void createFixture() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        session = new BrowserSession(context);
        CountDownLatch loaded = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            view = new WebView(context) {
                @Override public void evaluateJavascript(String source, ValueCallback<String> callback) {
                    super.evaluateJavascript(source, raw -> {
                        // Deterministically change the page epoch after the real
                        // script ran, before BrowserPage receives its result.
                        int remaining = changesDuringRead.get();
                        if (remaining != 0) {
                            if (remaining > 0) changesDuringRead.decrementAndGet();
                            BrowserSession.userInteraction();
                            changedReadCount.incrementAndGet();
                        }
                        callback.onReceiveValue(raw);
                    });
                }
            };
            view.getSettings().setJavaScriptEnabled(true);
            view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(1600, android.view.View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 1080, 1600);
            view.setWebViewClient(new WebViewClient() { @Override public void onPageFinished(WebView browser, String url) { loaded.countDown(); } });
            BrowserSession.attach(null, view, true);
            view.loadDataWithBaseURL("https://fixture.example/", "<html><body><h1>浏览器练习</h1><form id='searchForm' action='/search' method='get'><input id='name' aria-label='姓名' placeholder='输入姓名'><input type='password' value='synthetic-secret'><button id='go' type='button' onclick=\"window.clicks=(window.clicks||0)+1;document.getElementById('result').innerText='已确认'\">确认</button></form><p id='result'>未确认</p><p id='ticker' style='position:absolute;top:500px'>动态内容</p></body></html>", "text/html", "UTF-8", null);
        });
        assertTrue(loaded.await(10, TimeUnit.SECONDS));
    }
    @After public void destroyFixture() {
        if (view != null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { BrowserSession.detach(view); view.destroy(); });
    }
    private JSONObject tool(String name, JSONObject args) throws Exception { return session.executeLocal(name, args, () -> {}); }
    private void script(String source) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(source, value -> done.countDown()));
        assertTrue("Fixture script did not complete", done.await(3, TimeUnit.SECONDS));
    }
    private JSONObject target(JSONObject observed, String tag) throws Exception {
        for (int index = 0; index < observed.getJSONArray("elements").length(); index++) {
            JSONObject element = observed.getJSONArray("elements").getJSONObject(index);
            if (tag.equals(element.optString("tag"))) return element;
        }
        throw new AssertionError("Fixture target is missing: " + tag);
    }
    private JSONObject action(JSONObject observed, String tag) throws Exception {
        return new JSONObject().put("ref", target(observed, tag).getString("ref")).put("snapshotId", observed.getString("snapshotId"));
    }
    private void assertRejected(String tool, JSONObject args, String code) throws Exception {
        try { tool(tool, args); fail("Changed target was accepted"); }
        catch (ActionException expected) { assertEquals(code, expected.getCode()); }
    }
    @Test public void observesThenInputsAndClicksWithoutReportingBusinessSuccess() throws Exception {
        JSONObject observed = tool("browser_observe", new JSONObject());
        assertTrue(observed.getString("text").contains("浏览器练习"));
        assertFalse(observed.toString().contains("synthetic-secret"));
        assertEquals("", target(observed, "input").getString("value"));
        JSONObject result = tool("browser_input", action(observed, "input").put("text", "中文姓名"));
        assertFalse(result.getBoolean("goalVerified"));
        assertTrue(result.getBoolean("textVerified"));
        assertFalse(result.getBoolean("submitActionRequested"));
        observed = tool("browser_observe", new JSONObject());
        assertEquals("中文姓名", target(observed, "input").getString("value"));
        tool("browser_click", action(observed, "button"));
        assertTrue(tool("browser_wait", new JSONObject().put("text", "已确认").put("timeoutMs", 1000)).getBoolean("conditionVerified"));
    }
    @Test public void inputPreservesLiteralTemplateTokensQuotesAndNewlines() throws Exception {
        script("const field=document.createElement('textarea');field.setAttribute('aria-label','字面文本');document.body.appendChild(field)");
        String text = "IS_INPUT INPUT_VALUE ELEMENT_REF SNAPSHOT_TOKEN \"字面文本\" \\路径\n第二行";
        JSONObject observed = tool("browser_observe", new JSONObject());
        JSONObject result = tool("browser_input", action(observed, "textarea").put("text", text));
        assertTrue(result.getBoolean("textVerified"));
        assertFalse(result.getBoolean("submitActionRequested"));
        assertEquals(text, target(tool("browser_observe", new JSONObject()), "textarea").getString("value"));
    }
    @Test public void userInteractionInvalidatesOldReferencesAndFreshObservationCanContinue() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(BrowserSession::userInteraction);
        assertRejected("browser_click", action(snapshot, "button"), "STALE_BROWSER_SNAPSHOT");
        JSONObject fresh = tool("browser_observe", new JSONObject());
        JSONObject input = tool("browser_input", action(fresh, "input").put("text", "继续输入"));
        assertTrue(input.getBoolean("textVerified"));
        assertFalse(input.getBoolean("submitActionRequested"));
    }
    @Test public void unrelatedDynamicUpdatesAllowOneObservationAndOneInputOrClick() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("window.tick=1;document.getElementById('ticker').textContent='动态'+window.tick;window.ticker=setInterval(()=>document.getElementById('ticker').textContent='动态'+(++window.tick),25)");
        JSONObject input = tool("browser_input", action(snapshot, "input").put("text", "动态页面输入"));
        assertTrue(input.getBoolean("textVerified"));
        snapshot = tool("browser_observe", new JSONObject());
        assertEquals("动态页面输入", target(snapshot, "input").getString("value"));
        script("document.getElementById('ticker').textContent='动态'+(++window.tick)");
        tool("browser_click", action(snapshot, "button"));
        assertTrue(tool("browser_wait", new JSONObject().put("text", "已确认").put("timeoutMs", 1000)).getBoolean("conditionVerified"));
        script("clearInterval(window.ticker)");
    }
    @Test public void stableOffscreenInputIsScrolledIntoViewAndVerifiedWithoutASecondObservation() throws Exception {
        script("document.body.style.minHeight='6000px';document.getElementById('name').style.cssText='position:absolute;left:20px;top:4000px'");
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        JSONObject input = tool("browser_input", action(snapshot, "input").put("text", "滚动后输入"));
        assertTrue(input.getBoolean("textVerified"));
        assertFalse(input.getBoolean("submitActionRequested"));
        assertEquals("滚动后输入", target(tool("browser_observe", new JSONObject()), "input").getString("value"));
    }
    @Test public void replacedTargetRejectsOldElement() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("document.getElementById('name').outerHTML=\"<input id='name' aria-label='姓名' placeholder='输入姓名'>\"");
        assertRejected("browser_input", action(snapshot, "input").put("text", "不应写入"), "STALE_BROWSER_ELEMENT");
        assertEquals("", target(tool("browser_observe", new JSONObject()), "input").getString("value"));
    }
    @Test public void userEditedValueRejectsInputWithoutOverwritingIt() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("document.getElementById('name').value='用户已修改'");
        assertRejected("browser_input", action(snapshot, "input").put("text", "不应覆盖"), "STALE_BROWSER_ELEMENT");
        assertEquals("用户已修改", target(tool("browser_observe", new JSONObject()), "input").getString("value"));
    }
    @Test public void coveredTargetRejectsClick() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("const r=document.getElementById('go').getBoundingClientRect(),cover=document.createElement('div');cover.style.cssText='position:fixed;z-index:9999;background:white;left:'+r.x+'px;top:'+r.y+'px;width:'+r.width+'px;height:'+r.height+'px';document.body.appendChild(cover)");
        assertRejected("browser_click", action(snapshot, "button"), "STALE_BROWSER_ELEMENT");
        assertFalse(tool("browser_observe", new JSONObject()).getString("text").contains("已确认"));
    }
    @Test public void changedButtonLabelRejectsClick() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("document.getElementById('go').innerText='另一个操作'");
        assertRejected("browser_click", action(snapshot, "button"), "STALE_BROWSER_ELEMENT");
        assertFalse(tool("browser_observe", new JSONObject()).getString("text").contains("已确认"));
    }
    @Test public void changedPlaceholderRejectsInput() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("document.getElementById('name').placeholder='输入账户密码'");
        assertRejected("browser_input", action(snapshot, "input").put("text", "不应写入"), "STALE_BROWSER_ELEMENT");
        assertEquals("", target(tool("browser_observe", new JSONObject()), "input").getString("value"));
    }
    @Test public void changedFormActionRejectsInput() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        script("document.getElementById('searchForm').action='https://fixture.example/other-action'");
        assertRejected("browser_input", action(snapshot, "input").put("text", "不应写入"), "STALE_BROWSER_ELEMENT");
        assertEquals("", target(tool("browser_observe", new JSONObject()), "input").getString("value"));
    }
    @Test public void documentLoadingDuringWaitRecoversWithoutRepeatingAnAction() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        tool("browser_click", action(snapshot, "button"));
        script("window.__loadingReads=0;window.__restoreScheduled=false;Object.defineProperty(document,'documentElement',{configurable:true,get(){"
            + "window.__loadingReads++;if(!window.__restoreScheduled){window.__restoreScheduled=true;window.__restoreDocument=setTimeout(()=>delete document.documentElement,200)}return null}})");
        try {
            try { tool("browser_observe", new JSONObject()); fail("A missing document root was not classified as loading"); }
            catch (ActionException expected) { assertEquals("BROWSER_DOCUMENT_LOADING", expected.getCode()); }
            JSONObject waited = tool("browser_wait", new JSONObject().put("text", "已确认").put("timeoutMs", 3000));
            assertTrue(waited.getBoolean("conditionVerified"));
        } finally { script("clearTimeout(window.__restoreDocument);delete document.documentElement"); }
        script("document.getElementById('result').textContent='点击次数：'+window.clicks+'，加载读取：'+window.__loadingReads");
        assertTrue(tool("browser_observe", new JSONObject()).getString("text").contains("点击次数：1"));
    }
    @Test public void changedEpochDuringWaitIsReobservedWithoutRepeatingAnAction() throws Exception {
        JSONObject before = tool("browser_observe", new JSONObject());
        tool("browser_click", action(before, "button"));
        changesDuringRead.set(1);
        JSONObject waited = tool("browser_wait", new JSONObject().put("text", "已确认").put("timeoutMs", 3000));
        assertEquals(1, changedReadCount.get());
        assertEquals("condition_met", waited.getString("status"));
        assertTrue(waited.getBoolean("conditionVerified"));
        assertFalse(waited.getString("snapshotId").isEmpty());
        assertTrue(tool("browser_input", action(waited, "input").put("text", "有效的新观察")).getBoolean("textVerified"));
        script("document.getElementById('result').textContent='点击次数：'+window.clicks");
        assertTrue(tool("browser_observe", new JSONObject()).getString("text").contains("点击次数：1"));
    }
    @Test public void continuouslyChangingEpochTimesOutWithoutReturningOldReferences() throws Exception {
        JSONObject before = tool("browser_observe", new JSONObject());
        tool("browser_click", action(before, "button"));
        changesDuringRead.set(-1);
        JSONObject waited;
        try {
            waited = tool("browser_wait", new JSONObject().put("text", "已确认").put("timeoutMs", 400));
        } finally { changesDuringRead.set(0); }
        assertTrue("Every attempted observation must hit the injected read race", changedReadCount.get() > 0);
        assertEquals("wait_timeout", waited.getString("status"));
        assertFalse(waited.getBoolean("conditionVerified"));
        assertEquals("browser_changed", waited.getString("lastObservation"));
        assertFalse(waited.has("snapshotId"));
        assertFalse(waited.has("elements"));
        assertFalse(waited.has("text"));
        assertRejected("browser_click", action(before, "button"), "STALE_BROWSER_SNAPSHOT");
        script("document.getElementById('result').textContent='点击次数：'+window.clicks");
        assertTrue(tool("browser_observe", new JSONObject()).getString("text").contains("点击次数：1"));
    }
    @Test public void directObservationStillRejectsAnEpochChangeDuringRead() throws Exception {
        changesDuringRead.set(1);
        assertRejected("browser_observe", new JSONObject(), "BROWSER_CHANGED");
        assertEquals(1, changedReadCount.get());
        assertTrue(tool("browser_observe", new JSONObject()).getString("text").contains("浏览器练习"));
    }
    @Test public void unexpectedPageScriptErrorRemainsAnImmediateFailure() throws Exception {
        script("window.__originalTreeWalker=document.createTreeWalker;document.createTreeWalker=()=>{throw new ReferenceError('fictional')}");
        long started = SystemClock.elapsedRealtime();
        try {
            try {
                tool("browser_wait", new JSONObject().put("text", "不会出现的文字").put("timeoutMs", 10000));
                fail("An unexpected script failure was treated as an ordinary wait");
            } catch (ActionException expected) {
                assertEquals("BROWSER_SCRIPT_FAILED", expected.getCode());
                assertTrue(expected.getMessage(), expected.getMessage().contains("ReferenceError"));
                assertTrue("Unexpected script failures must not consume the full wait timeout", SystemClock.elapsedRealtime() - started < 5000);
            }
        } finally { script("document.createTreeWalker=window.__originalTreeWalker;delete window.__originalTreeWalker"); }
        assertTrue(tool("browser_observe", new JSONObject()).getString("text").contains("浏览器练习"));
    }
    @Test public void waitTimeoutDoesNotRepeatPreviouslyDispatchedAction() throws Exception {
        JSONObject snapshot = tool("browser_observe", new JSONObject());
        tool("browser_click", action(snapshot, "button"));
        JSONObject waited = tool("browser_wait", new JSONObject().put("text", "不会出现的文字").put("timeoutMs", 100));
        assertEquals("wait_timeout", waited.getString("status"));
        assertFalse(waited.getBoolean("conditionVerified"));
        script("document.getElementById('result').textContent='点击次数：'+window.clicks");
        assertTrue(tool("browser_observe", new JSONObject()).getString("text").contains("点击次数：1"));
    }
    @Test public void cancelledAndClosedSessionCannotExecute() throws Exception {
        try { session.executeLocal("browser_observe", new JSONObject(), () -> { throw new InterruptedException(); }); fail(); }
        catch (InterruptedException expected) { }
        BrowserSession.detach(view);
        try { tool("browser_observe", new JSONObject()); fail(); }
        catch (ActionException expected) { assertEquals("NO_BROWSER_SESSION", expected.getCode()); }
    }
}
