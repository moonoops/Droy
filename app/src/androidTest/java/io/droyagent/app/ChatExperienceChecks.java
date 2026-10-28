package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Measurements and UI checks run inside SetupFlowDeviceTest's isolated local-only fixture. */
final class ChatExperienceChecks {
    private final Instrumentation instrumentation;
    private final Context context;
    private final MainActivity activity;
    private final DeviceUi ui;
    private final File evidence;
    private final String label;
    private final JSONArray scenarios = new JSONArray();
    private final List<Double> nativeFrames = Collections.synchronizedList(new ArrayList<>());
    private HandlerThread framesThread;
    private Window.OnFrameMetricsAvailableListener framesListener;
    private long sampleStartedNs;
    private long sampleWallTimeMs;
    private long sampleClockAnchorNs;
    private JSONArray gestureWindows;
    private JSONArray operationWindows;
    private int keyboardProbeSequence;
    private int keyboardCloseSequence;

    ChatExperienceChecks(Instrumentation instrumentation, Context context, MainActivity activity, DeviceUi ui) {
        this.instrumentation = instrumentation; this.context = context; this.activity = activity; this.ui = ui;
        label = InstrumentationRegistry.getArguments().getString("evidenceLabel", "final").replaceAll("[^A-Za-z0-9_-]", "_");
        evidence = new File(context.getExternalFilesDir(null), "chat-experience-evidence/" + label);
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }

    void performance() throws Exception {
        assertTrue("Performance acceptance runs on an emulator", android.os.Build.MODEL.contains("sdk")
            || android.os.Build.FINGERPRINT.contains("emulator"));
        JSONObject result = new JSONObject().put("label", label).put("device", android.os.Build.MODEL)
            .put("sdk", android.os.Build.VERSION.SDK_INT).put("cloudModel", false)
            .put("frameSources", "WebView requestAnimationFrame cadence plus separate Android Window FrameMetrics; not a substitute for presented-frame tracing")
            .put("refreshRate", activity.getDisplay().getRefreshRate()).put("scenarios", scenarios);
        try {
            for (int count : new int[]{100, 1000}) {
                request("/droy/setup-chat-fixture", new JSONObject().put("operation", "seed").put("count", count));
                reloadFixturePage();
                openChat("kaka");
                loadHistory(count);
                capture("history-" + count);
                measureScroll("chat-scroll-" + count, ".droy-im-messages");
                measureInput("chat-input-" + count);
                ui.hideKeyboard();
                ui.pressBack();
                DeviceUi.await("Performance navigation did not return to the list", 5000,
                    () -> ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"));
            }
            openGeneral();
            assertTrue(ui.truth("(()=>{const p=document.querySelector('[data-droy-settings-panel]');const e=[p,...p.querySelectorAll('*')].filter(e=>e.getClientRects().length&&e.scrollHeight>e.clientHeight+100&&['auto','scroll'].includes(getComputedStyle(e).overflowY)).sort((a,b)=>b.clientHeight-a.clientHeight)[0];if(!e)return false;e.setAttribute('data-chat-perf-scroll','');return true})()"));
            measureScroll("settings-scroll", "[data-chat-perf-scroll]");
            measureSetting();
            capture("settings");
            result.put("completed", true);
        } finally {
            stopNativeFrames();
            Files.write(new File(evidence, "performance.json").toPath(), result.toString(2).getBytes(StandardCharsets.UTF_8));
            report("DROY_CHAT_PERFORMANCE " + new JSONObject().put("label",label).put("completed",result.optBoolean("completed"))
                .put("scenarioCount",scenarios.length()).put("evidence",new File(evidence,"performance.json").getAbsolutePath()));
        }
        if ("true".equals(InstrumentationRegistry.getArguments().getString("chatPerformanceEnforceTargets", "false"))) {
            for (int i = 0; i < scenarios.length(); i++) {
                JSONObject scenario = scenarios.getJSONObject(i);
                if (scenario.getString("name").contains("scroll")) {
                    assertTrue("Too few frame samples for " + scenario, scenario.getInt("frameCount") >= 30);
                    assertTrue("Scrolling missed the 1% cadence target: " + scenario, scenario.getDouble("missedCadenceRatio") <= .01);
                }
                if (scenario.has("interactionP95Ms")) assertTrue("Interaction exceeded 100 ms: " + scenario,
                    scenario.getDouble("interactionP95Ms") <= 100);
            }
        }
    }

    private void reloadFixturePage() throws Exception {
        ui.evaluate("window.__chatFixtureReload=true");
        android.webkit.WebView view=ui.web();instrumentation.runOnMainSync(view::reload);
        DeviceUi.await("The fixture document did not reload",15000,()->ui.truth("window.__chatFixtureReload===undefined&&typeof window.DroyMobile?.openCompanion==='function'"));
        ui.awaitPage();
    }

    private void loadHistory(int expectedCount) throws Exception {
        for (int page = 0; page < 25 && ui.truth("!!document.querySelector('.droy-im-load-earlier')"); page++) {
            String before = String.valueOf(ui.evaluate("document.querySelector('.droy-im-message')?.dataset.messageId"));
            ui.evaluate("document.querySelector('.droy-im-messages').scrollTop=0");
            ui.tap(".droy-im-load-earlier");
            DeviceUi.await("The previous history page did not arrive", 10000,
                () -> !before.equals(String.valueOf(ui.evaluate("document.querySelector('.droy-im-message')?.dataset.messageId")))
                    || !ui.truth("!!document.querySelector('.droy-im-load-earlier')"));
        }
        assertFalse("The performance fixture did not load all history pages", ui.truth("!!document.querySelector('.droy-im-load-earlier')"));
        assertEquals("The history scenario did not render its exact synthetic dataset",expectedCount,
            ((Number)ui.evaluate("document.querySelectorAll('.droy-im-message').length")).intValue());
        assertTrue("The history scenario retained messages from a prior seed",ui.truth("[...document.querySelectorAll('.droy-im-message')].every(e=>e.dataset.messageId.startsWith('fixture-history-"+expectedCount+"-'))"));
        ui.evaluate("document.querySelector('.droy-im-messages').scrollTop=document.querySelector('.droy-im-messages').scrollHeight");
        ui.awaitPaint();
    }

    void appendSyntheticHistory() throws Exception {
        request("/droy/setup-chat-fixture",new JSONObject().put("operation","append").put("count",100));
    }

    private void measureScroll(String name, String selector) throws Exception {
        Rect bounds = ui.webBounds(selector);
        ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");e.scrollTop=e.scrollHeight/2;})()");
        ui.awaitPaint();
        startSamples(selector);
        try {
            for (int index = 0; index < 8; index++) {
                boolean down = index % 2 == 0;
                long begin=SystemClock.elapsedRealtimeNanos();
                ui.swipe(bounds.centerX(), down ? bounds.top + bounds.height() / 4 : bounds.bottom - bounds.height() / 4,
                    bounds.centerX(), down ? bounds.bottom - bounds.height() / 4 : bounds.top + bounds.height() / 4, 360);
                gestureWindows.put(new JSONObject().put("startElapsedRealtimeNs",begin).put("endElapsedRealtimeNs",SystemClock.elapsedRealtimeNanos()));
            }
        } finally { finishSamples(name, true); }
    }

    private void showKeyboardWithDiagnostic(String selector,String scenario) throws Exception {
        ui.evaluate("(()=>{const describe=e=>e?{tag:e.tagName,className:typeof e.className==='string'?e.className:'',isConnected:e.isConnected,disabled:e.disabled===true,inert:!!e.closest('[inert]')}:null;"
            + "const selector="+JSONObject.quote(selector)+",input=document.querySelector(selector),r=input?.getBoundingClientRect();const p=window.__chatImeProbe={events:[],input,selector,describe,before:{input:describe(input),rect:r?{x:r.x,y:r.y,width:r.width,height:r.height}:null,elementFromPoint:r?describe(document.elementFromPoint(r.x+r.width/2,r.y+r.height/2)):null,activeElement:describe(document.activeElement)}};"
            + "p.types=['pointerdown','pointerup','pointercancel','click','focusin','focusout'];p.listener=event=>{if(p.events.length>=64)return;const entry={type:event.type,target:describe(event.target),isTrusted:event.isTrusted,defaultPrevented:event.defaultPrevented,timeStamp:event.timeStamp};p.events.push(entry);queueMicrotask(()=>entry.defaultPrevented=event.defaultPrevented)};"
            + "for(const type of p.types)document.addEventListener(type,p.listener,true);return true})()");
        Throwable keyboardFailure=null;
        try {
            ui.showKeyboard(selector);
        } catch(Exception|AssertionError failure) {
            keyboardFailure=failure;
            try {
                JSONObject diagnostic=(JSONObject)ui.evaluate("(()=>{const p=window.__chatImeProbe;return {selector:p.selector,before:p.before,events:p.events,after:{activeElement:p.describe(document.activeElement),input:p.describe(document.querySelector(p.selector)),originalInputConnected:p.input?.isConnected===true}}})()");
                diagnostic.put("scenario",scenario).put("nativeNavigationState",ui.nativeNavigationState());
                Files.write(new File(evidence,scenario+"-ime-failure.json").toPath(),diagnostic.toString(2).getBytes(StandardCharsets.UTF_8));
                report("DROY_CHAT_IME_FAILURE "+diagnostic);
            } catch(Exception|AssertionError diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        } finally {
            try { ui.evaluate("(()=>{const p=window.__chatImeProbe;if(p)for(const type of p.types)document.removeEventListener(type,p.listener,true);delete window.__chatImeProbe;return true})()"); }
            catch(Exception|AssertionError cleanupFailure) {if(keyboardFailure!=null)keyboardFailure.addSuppressed(cleanupFailure);else throw cleanupFailure;}
        }
    }

    private void measureInput(String name) throws Exception {
        showKeyboardWithDiagnostic(".droy-im-input",name);
        ui.awaitPaint();
        startSamples(".droy-im-messages");
        try {
            for (int index = 0; index < 12; index++) {
                watchChange(".droy-im-input", "value", "input");
                String value = "Fixture input " + index + " with a short draft";
                int length = ((String)ui.evaluate("document.querySelector('.droy-im-input').value")).length();
                long began=SystemClock.elapsedRealtimeNanos();
                try {ui.replaceFocusedText(value, length);awaitInteraction();}
                finally {operationWindows.put(new JSONObject().put("kind","ime-commit").put("index",index)
                    .put("startElapsedRealtimeNs",began).put("endElapsedRealtimeNs",SystemClock.elapsedRealtimeNanos()));}
                assertEquals(value, ui.evaluate("document.querySelector('.droy-im-input').value"));
            }
            int length = ((String)ui.evaluate("document.querySelector('.droy-im-input').value")).length();
            ui.replaceFocusedText("", length);
        } finally { finishSamples(name, false); }
    }

    private void measureSetting() throws Exception {
        String selector = "[data-droy-general=returnToDroyOnCompletion] [role=switch]";
        ui.visibleWebBounds(selector);ui.awaitPaint();
        startSamples("[data-chat-perf-scroll]");
        try {
            for (int index = 0; index < 8; index++) {
                boolean previous = ProductPermissions.preferences(context).getBoolean("returnToDroyOnCompletion", false);
                watchChange(selector, "aria-checked", "pointerup");
                long began=SystemClock.elapsedRealtimeNanos();
                try {ui.tap(selector);awaitInteraction();}
                finally {operationWindows.put(new JSONObject().put("kind","setting-tap").put("index",index)
                    .put("startElapsedRealtimeNs",began).put("endElapsedRealtimeNs",SystemClock.elapsedRealtimeNanos()));}
                DeviceUi.await("The visible setting was not persisted", 5000,
                    () -> ProductPermissions.preferences(context).getBoolean("returnToDroyOnCompletion", false) != previous);
            }
        } finally { finishSamples("settings-toggle", false); }
    }

    private void startSamples(String selector) throws Exception {
        startNativeSamples();
        ui.evaluate("(()=>{const p=window.__chatPerf={active:true,frames:[],frameTimes:[],longTasks:[],interactions:[],interactionTimings:[],scrollEvents:0,scrollSamples:[],timeOrigin:performance.timeOrigin};let previous;"
            + "p.scroll=()=>{p.scrollEvents++;p.scrollSamples.push({time:performance.now(),top:p.scroller.scrollTop})};p.scroller=document.querySelector("+JSONObject.quote(selector)+");p.scroller.addEventListener('scroll',p.scroll,{passive:true});"
            + "const tick=t=>{if(!p.active)return;if(previous!==undefined){p.frames.push(t-previous);p.frameTimes.push(t)}previous=t;p.frame=requestAnimationFrame(tick)};p.frame=requestAnimationFrame(tick);"
            + "if(PerformanceObserver.supportedEntryTypes?.includes('longtask')){p.observer=new PerformanceObserver(list=>{for(const e of list.getEntries())p.longTasks.push({start:e.startTime,duration:e.duration})});p.observer.observe({type:'longtask'})}return true})()");
    }

    private void startNativeSamples() {
        nativeFrames.clear();sampleStartedNs=SystemClock.elapsedRealtimeNanos();gestureWindows=new JSONArray();operationWindows=new JSONArray();
        sampleWallTimeMs=System.currentTimeMillis();sampleClockAnchorNs=SystemClock.elapsedRealtimeNanos();
        framesThread = new HandlerThread("droy-chat-frame-metrics");framesThread.start();
        framesListener = (window, metrics, dropped) -> nativeFrames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000d);
        instrumentation.runOnMainSync(() -> activity.getWindow().addOnFrameMetricsAvailableListener(framesListener, new Handler(framesThread.getLooper())));
    }

    void renderingControl() throws Exception {
        assertTrue("Rendering controls run only on an emulator", android.os.Build.MODEL.contains("sdk")
            || android.os.Build.FINGERPRINT.contains("emulator"));
        android.webkit.WebView web=ui.web();
        java.util.concurrent.atomic.AtomicReference<android.webkit.WebViewClient> originalClient=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> originalUrl=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<android.view.ViewGroup> parent=new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<android.widget.ScrollView> nativeList=new java.util.concurrent.atomic.AtomicReference<>();
        boolean[] originalNetworkBlocking=new boolean[1];
        JSONObject result=new JSONObject().put("label",label).put("device",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT)
            .put("refreshRate",activity.getDisplay().getRefreshRate()).put("rows",1000).put("rowHeightDp",56)
            .put("cloudModel",false).put("webNetworkLoadsBlocked",true).put("applicationJavaScript",false)
            .put("host","MainActivity retained; original application document unloaded; only frame sampler JavaScript in WebView control")
            .put("frameSources","Window FrameMetrics and WebView-only rAF cadence; presented frames require Perfetto").put("scenarios",scenarios);
        StringBuilder html=new StringBuilder("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none';style-src 'unsafe-inline'\"><style>html,body{margin:0;background:#f5f5f5;font:14px sans-serif}#control{height:100vh;overflow-y:auto;overscroll-behavior:none}.row{box-sizing:border-box;height:56px;display:flex;align-items:center;padding:0 16px;border-bottom:1px solid #ddd;background:#fff}</style></head><body><div id='control'>");
        for(int index=0;index<1000;index++)html.append("<div class='row'>Synthetic rendering row ").append(index).append("</div>");
        html.append("</div></body></html>");
        try {
            instrumentation.runOnMainSync(()->{
                originalClient.set(web.getWebViewClient());originalUrl.set(web.getUrl());parent.set((android.view.ViewGroup)web.getParent());
                originalNetworkBlocking[0]=web.getSettings().getBlockNetworkLoads();
                web.stopLoading();web.setWebViewClient(new android.webkit.WebViewClient());web.getSettings().setBlockNetworkLoads(true);
                web.loadDataWithBaseURL(null,html.toString(),"text/html","UTF-8",null);
            });
            DeviceUi.await("The minimal WebView control did not load its fixed rows",10000,
                ()->ui.truth("document.querySelectorAll('#control>.row').length===1000&&typeof window.DroyMobile==='undefined'"));
            ui.awaitPaint();capture("control-webview");measureScroll("control-webview-scroll","#control");

            List<JSONObject> scrollSamples=Collections.synchronizedList(new ArrayList<>());
            instrumentation.runOnMainSync(()->{
                android.widget.ScrollView scroll=new android.widget.ScrollView(activity);nativeList.set(scroll);
                scroll.setBackgroundColor(android.graphics.Color.WHITE);scroll.setOverScrollMode(android.view.View.OVER_SCROLL_NEVER);
                android.widget.LinearLayout rows=new android.widget.LinearLayout(activity);rows.setOrientation(android.widget.LinearLayout.VERTICAL);
                float density=context.getResources().getDisplayMetrics().density;
                for(int index=0;index<1000;index++) {
                    android.widget.TextView row=new android.widget.TextView(activity);row.setText("Synthetic rendering row "+index);
                    row.setTextSize(14);row.setTextColor(android.graphics.Color.BLACK);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    row.setPadding(Math.round(16*density),0,Math.round(16*density),0);
                    rows.addView(row,new android.widget.LinearLayout.LayoutParams(-1,Math.round(56*density)));
                }
                scroll.addView(rows,new android.widget.ScrollView.LayoutParams(-1,-2));
                parent.get().addView(scroll,new android.widget.FrameLayout.LayoutParams(-1,-1));web.setVisibility(android.view.View.GONE);
            });
            DeviceUi.await("The native fixed-row list did not lay out",10000,()->{
                boolean[] ready=new boolean[1];instrumentation.runOnMainSync(()->ready[0]=nativeList.get().getHeight()>0&&nativeList.get().getChildAt(0).getHeight()>nativeList.get().getHeight()*2);return ready[0];
            });
            instrumentation.runOnMainSync(()->nativeList.get().scrollTo(0,nativeList.get().getChildAt(0).getHeight()/2));
            awaitNativeFrame(nativeList.get());captureSystem("control-native");
            Rect bounds=new Rect();instrumentation.runOnMainSync(()->nativeList.get().getGlobalVisibleRect(bounds));assertFalse(bounds.isEmpty());
            startNativeSamples();
            instrumentation.runOnMainSync(()->nativeList.get().setOnScrollChangeListener((android.view.View.OnScrollChangeListener)(view,x,y,oldX,oldY)->{
                try {scrollSamples.add(new JSONObject().put("elapsedRealtimeNs",SystemClock.elapsedRealtimeNanos()).put("top",y));}
                catch(org.json.JSONException failure){throw new AssertionError(failure);}
            }));
            try {
                for(int index=0;index<8;index++) {
                    boolean down=index%2==0;long began=SystemClock.elapsedRealtimeNanos();
                    ui.swipe(bounds.centerX(),down?bounds.top+bounds.height()/4:bounds.bottom-bounds.height()/4,
                        bounds.centerX(),down?bounds.bottom-bounds.height()/4:bounds.top+bounds.height()/4,360);
                    gestureWindows.put(new JSONObject().put("startElapsedRealtimeNs",began).put("endElapsedRealtimeNs",SystemClock.elapsedRealtimeNanos()));
                }
            } finally {
                long ended=SystemClock.elapsedRealtimeNanos();
                instrumentation.runOnMainSync(()->nativeList.get().setOnScrollChangeListener((android.view.View.OnScrollChangeListener)null));stopNativeFrames();
                scenarios.put(new JSONObject().put("name","control-native-scroll").put("startElapsedRealtimeNs",sampleStartedNs).put("endElapsedRealtimeNs",ended)
                    .put("gestureWindows",gestureWindows).put("scrollSamples",new JSONArray(new ArrayList<>(scrollSamples)))
                    .put("nativeWindowFrameDurationsMs",new JSONArray(new ArrayList<>(nativeFrames))).put("rAFApplicable",false));
            }
            assertTrue("The native control did not actually scroll",scrollSamples.size()>5);result.put("completed",true);
        } finally {
            stopNativeFrames();
            try {
                instrumentation.runOnMainSync(()->{
                    if(nativeList.get()!=null)parent.get().removeView(nativeList.get());
                    web.setVisibility(android.view.View.VISIBLE);web.getSettings().setBlockNetworkLoads(originalNetworkBlocking[0]);
                    if(originalClient.get()!=null)web.setWebViewClient(originalClient.get());
                    if(originalUrl.get()!=null)web.loadUrl(originalUrl.get());
                });
                ui.awaitPage();result.put("applicationPageRestored",true);
            } finally {
                Files.write(new File(evidence,"rendering-control.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
                report("DROY_RENDERING_CONTROL "+new JSONObject().put("completed",result.optBoolean("completed")).put("evidence",new File(evidence,"rendering-control.json").getAbsolutePath()));
            }
        }
    }

    private void awaitNativeFrame(android.view.View view) throws Exception {
        java.util.concurrent.CountDownLatch committed=new java.util.concurrent.CountDownLatch(1);
        instrumentation.runOnMainSync(()->view.postOnAnimation(()->{view.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);view.invalidate();}));
        assertTrue("The native control did not commit its layout frame",committed.await(5,java.util.concurrent.TimeUnit.SECONDS));
    }

    private void watchChange(String selector, String field, String event) throws Exception {
        ui.evaluate("(()=>{const p=window.__chatPerf,e=document.querySelector("+JSONObject.quote(selector)+"),read=()=>"
            + (field.equals("value") ? "e.value" : "e.getAttribute('aria-checked')")
            + ",before=read();p.interactionDone=false;const start=ev=>{const began=performance.now(),timing={eventType:ev.type,eventTimeStamp:ev.timeStamp,handledAt:began,changedObservedAt:null,feedbackRafAt:null};p.interactionTimings.push(timing);const check=()=>{if(!p.active)return;if(read()!==before){timing.changedObservedAt=performance.now();requestAnimationFrame(()=>{timing.feedbackRafAt=performance.now();p.interactions.push(timing.feedbackRafAt-began);p.interactionDone=true})}else requestAnimationFrame(check)};requestAnimationFrame(check)};"
            + "e.addEventListener("+JSONObject.quote(event)+",start,{once:true,capture:true});return true})()");
    }

    private void awaitInteraction() throws Exception {
        DeviceUi.await("The operation did not visibly update its control", 10000,
            () -> ui.truth("window.__chatPerf.interactionDone===true"));
    }

    private void finishSamples(String name, boolean requireScroll) throws Exception {
        JSONObject value = (JSONObject)ui.evaluate("(()=>{const p=window.__chatPerf;p.active=false;cancelAnimationFrame(p.frame);for(const e of p.observer?.takeRecords()||[])p.longTasks.push({start:e.startTime,duration:e.duration});p.observer?.disconnect();p.scroller.removeEventListener('scroll',p.scroll);"
            + "return {frames:p.frames,frameTimes:p.frameTimes,timeOrigin:p.timeOrigin,scrollSamples:p.scrollSamples,longTasks:p.longTasks,interactions:p.interactions,interactionTimings:p.interactionTimings,scrollEvents:p.scrollEvents,renderedMessages:document.querySelectorAll('.droy-im-message').length}})()");
        stopNativeFrames();
        JSONArray frames = value.getJSONArray("frames"), interactions = value.getJSONArray("interactions");
        double budget = 1000d / activity.getDisplay().getRefreshRate();
        int missed = 0;for (int index = 0; index < frames.length(); index++) if (frames.getDouble(index) > budget * 1.5) missed++;
        value.put("name", name).put("startElapsedRealtimeNs",sampleStartedNs).put("endElapsedRealtimeNs",SystemClock.elapsedRealtimeNanos())
            .put("gestureWindows",gestureWindows).put("expectedCadenceMs", budget).put("frameCount", frames.length())
            .put("operationWindows",operationWindows).put("clockAnchor",new JSONObject().put("currentTimeMillis",sampleWallTimeMs).put("elapsedRealtimeNanos",sampleClockAnchorNs))
            .put("missedCadenceRatio", frames.length() == 0 ? 1 : (double)missed / frames.length())
            .put("frameP95Ms", percentile(frames, .95)).put("frameP99Ms", percentile(frames, .99))
            .put("nativeWindowFrameDurationsMs", new JSONArray(new ArrayList<>(nativeFrames)));
        if (interactions.length() > 0) value.put("interactionP95Ms", percentile(interactions, .95));
        scenarios.put(value);
        if (requireScroll) assertTrue("The touch gestures did not actually scroll " + name, value.getInt("scrollEvents") > 5);
    }

    private static double percentile(JSONArray source, double percentile) throws Exception {
        if (source.length() == 0) return 0;
        List<Double> values = new ArrayList<>();for(int index=0;index<source.length();index++)values.add(source.getDouble(index));
        Collections.sort(values);return values.get(Math.max(0,(int)Math.ceil(values.size()*percentile)-1));
    }

    private void stopNativeFrames() {
        if (framesListener != null) {instrumentation.runOnMainSync(() -> activity.getWindow().removeOnFrameMetricsAvailableListener(framesListener));framesListener=null;}
        if (framesThread != null) {framesThread.quitSafely();framesThread=null;}
    }

    void uiChecks() throws Exception {
        assertTrue("The photo fixture is restricted to an emulator", android.os.Build.MODEL.contains("sdk")
            || android.os.Build.FINGERPRINT.contains("emulator"));
        Uri fixturePhoto = null;
        JSONObject result = new JSONObject().put("label", label).put("cloudModel", false).put("checks", new JSONArray())
            .put("locale",InstrumentationRegistry.getArguments().getString("setupLocale","zh"))
            .put("theme",InstrumentationRegistry.getArguments().getString("layoutTheme","light"));
        JSONArray checks = result.getJSONArray("checks");
        try {
            openChat("kaka");
            send(SetupFixtureProvider.KAKA_GREETING);
            awaitTextCount("kaka", 1);
            assertEquals("A normal reply must be one bubble", 1, textCount("kaka"));
            assertFalse("Normal replies must not automatically quote", ui.truth("!!document.querySelector('.droy-im-message-quote')"));
            assertMessageAppearance();checks.put("one-bubble-normal-reply-and-local-timestamps");
            assertLocalClock();checks.put("native-timezone-in-planner-context");
            capture("normal-chat");

            send(SetupFixtureProvider.HOLD_REQUEST);
            try {
                DeviceUi.await("The held reply did not expose the compact typing status", 10000,
                    () -> ui.truth("/输入中|Typing/.test(document.querySelector('.droy-im-chat-title>span[role=status]')?.textContent)"));
                assertFalse("An internal phase leaked into a chat bubble", ui.truth("!!document.querySelector('.droy-im-typing')"));
                assertFalse("Thinking text leaked into the title", ui.truth("/正在想|Thinking/.test(document.querySelector('.droy-im-chat-title')?.textContent)"));
                capture("typing-header");
            } finally { request("/droy/setup-chat-fixture", new JSONObject().put("operation", "release")); }
            awaitTextCount("kaka", 2);checks.put("typing-only-below-name");
            send(SetupFixtureProvider.QUOTE_REQUEST);awaitTextCount("kaka", 3);
            DeviceUi.await("The intentional reference was not displayed", 10000,
                () -> ui.truth("[...document.querySelectorAll('.droy-im-message-quote')].some(e=>e.textContent.includes(\"Hi Kaka, let's chat.\"))"));
            assertEquals("Only the explicit reply should quote", 1,
                ((Number)ui.evaluate("document.querySelectorAll('.droy-im-message.is-assistant .droy-im-message-quote').length")).intValue());
            ui.longPress(".droy-im-message.is-user", 650);
            tap("[data-droy-im-action=quote]");
            assertTrue("Manual user quoting stopped working", ui.truth("!!document.querySelector('.droy-im-compose-quote')"));
            tap(".droy-im-compose-quote button");checks.put("intentional-and-manual-quotes");

            type(".droy-im-input", "Unsent fixture draft");
            assertKeyboardGap();capture("keyboard");closeKeyboardWithBack();
            assertEquals("Keyboard closure erased the draft", "Unsent fixture draft", ui.evaluate("document.querySelector('.droy-im-input').value"));
            type(".droy-im-input", "");closeKeyboardWithBack();checks.put("keyboard-gap-and-draft");

            openProfile();
            assertTrue("The profile title was not renamed", ui.truth("/^(个人信息|Personal information)$/i.test(document.querySelector('.droy-im-page-title')?.textContent.trim())"));
            assertFalse("The profile retained its native model select", ui.truth("!!document.querySelector('.droy-im-profile-form select')"));
            assertFalse("The hidden memory entry remained visible", ui.truth("/查看与管理记忆|View and manage memor/i.test(document.querySelector('.droy-im-profile-form').textContent)"));
            assertFalse("The old long-term-memory explanation remained", ui.truth("/这位伙伴的记忆独立|This companion.s memories are stored/i.test(document.querySelector('.droy-im-profile-form').textContent)"));
            assertFalse("Civil language should default off", checked("civilLanguage"));
            assertTrue("Do not disturb should default on", checked("dnd"));
            assertEquals("22:00", ui.evaluate("document.querySelector('[data-droy-im-setting=dndStart]').value"));
            assertEquals("09:00", ui.evaluate("document.querySelector('[data-droy-im-setting=dndEnd]').value"));
            tap("[data-droy-im-action=model-picker]");
            capture("model-dropdown");
            tap("[data-droy-im-model='"+SetupFixtureProvider.PROVIDER+":"+SetupFixtureProvider.ALTERNATE_MODEL+"']");
            tap("[data-droy-im-setting=civilLanguage]");tap("[data-droy-im-setting=dnd]");
            tap("[data-droy-im-action=profile-save]");
            DeviceUi.await("Profile settings did not persist",10000,()->{
                JSONObject p=profile("kaka"), settings=p.getJSONObject("settings");
                return !settings.getBoolean("allowProfanity")&&!settings.getJSONObject("quietHours").getBoolean("enabled")
                    &&p.getJSONObject("model").getString("model").equals(SetupFixtureProvider.ALTERNATE_MODEL);
            });
            JSONObject hours=profile("kaka").getJSONObject("settings").getJSONObject("quietHours");
            assertEquals("22:00", hours.getString("start"));assertEquals("09:00", hours.getString("end"));
            ui.pressBack();openProfile();assertFalse(checked("dnd"));assertTrue(checked("civilLanguage"));
            tap("[data-droy-im-setting=dnd]");tap("[data-droy-im-action=profile-save]");
            DeviceUi.await("Do not disturb did not re-enable",10000,()->profile("kaka").getJSONObject("settings").getJSONObject("quietHours").getBoolean("enabled"));
            checks.put("profile-model-civil-language-and-dnd-persistence");

            tap("[data-droy-im-action=personality]");
            type(".droy-im-personality-editor", "Fictional preference: speak plainly and ask concise questions.");ui.hideKeyboard();
            tap("[data-droy-im-dialog=personality] [data-droy-im-action=cancel]");
            assertEquals("Cancel saved a personality draft", "", profile("kaka").optString("personalityOverride", ""));
            tap("[data-droy-im-action=personality]");
            String personality="Fictional personality: calm, curious, and concise.";
            type(".droy-im-personality-editor",personality);ui.hideKeyboard();
            tap("[data-droy-im-dialog=personality] [data-droy-im-action=save]");
            DeviceUi.await("Personality did not persist",10000,()->personality.equals(profile("kaka").optString("personalityOverride")));
            tap("[data-droy-im-action=personality]");assertEquals(personality,ui.evaluate("document.querySelector('.droy-im-personality-editor').value"));
            capture("personality-editor");
            tap("[data-droy-im-dialog=personality] [data-droy-im-action=cancel]");checks.put("personality-save-cancel-and-reopen");
            capture("personal-information");

            tap("[data-droy-im-action=background]");
            assertEquals(6,((Number)ui.evaluate("document.querySelectorAll('[data-droy-im-background]').length")).intValue());
            assertTrue("Background choices contain visible names or descriptions",ui.truth("[...document.querySelectorAll('[data-droy-im-background]')].every(e=>e.textContent.trim()==='')"));
            tap("[data-droy-im-background='03']");tap("[data-droy-im-dialog=chatBackground] [data-droy-im-action=apply]");
            DeviceUi.await("Preset background did not persist",10000,()->"03".equals(profile("kaka").getJSONObject("chatBackground").optString("id")));
            assertEquals("Changing Kaka's background affected Maimai", "default", profile("maimai").getJSONObject("chatBackground").getString("type"));
            tap("[data-droy-im-action=background]");
            assertTrue(ui.truth("document.querySelector('[data-droy-im-background=\"03\"]').getAttribute('aria-pressed')==='true'"));
            capture("background-presets");tap("[data-droy-im-dialog=chatBackground] [data-droy-im-action=cancel]");
            checks.put("six-backgrounds-preset-save-and-partner-isolation");
            ui.pressBack();

            fixturePhoto=createFixturePhoto();
            checkAlbumCancel();checks.put("system-photo-picker-cancel-preserves-draft");
            openAlbum();selectFixturePhoto();
            DeviceUi.await("Selected photo did not return to the current draft",15000,()->ui.truth("document.querySelectorAll('.droy-im-draft-attachments img').length===1"));
            assertEquals("Picking a photo sent it without confirmation",3,textCount("kaka"));
            capture("photo-draft");tap(".droy-im-draft-attachments button");checks.put("system-photo-picker-selects-controlled-photo");

            tap("[data-droy-im-action=user-profile]");
            DeviceUi.await("The default cat avatar did not decode",10000,()->ui.truth("(()=>{const e=document.querySelector('[data-droy-im-dialog=profile] .droy-im-avatar img');return e?.src.endsWith('/assets/user-default.png')&&e.complete&&e.naturalWidth>0})()"));
            type("[data-droy-im-field=user-name]","River Fixture");ui.hideKeyboard();
            tap("[data-droy-im-action=user-avatar]");selectFixturePhoto();
            tap("[data-droy-im-dialog=profile] [data-droy-im-action=save]");
            DeviceUi.await("Global user information did not persist",15000,()->{
                JSONObject user=request("/droy/companions/user-profile",null);
                return user.optString("name").equals("River Fixture")&&user.optString("avatarUrl").contains("/user-profile/attachments/");
            });
            String userAvatar=request("/droy/companions/user-profile",null).getString("avatarUrl");
            assertTrue("The historical user avatar did not refresh",ui.truth("[...document.querySelectorAll('[data-droy-im-action=user-profile] img')].every(e=>new URL(e.src).pathname==="+JSONObject.quote(userAvatar)+")"));
            openChat("maimai");send(SetupFixtureProvider.MAIMAI_GREETING);awaitTextCount("maimai",1);
            tap("[data-droy-im-action=user-profile]");
            assertEquals("River Fixture",ui.evaluate("document.querySelector('[data-droy-im-field=user-name]').value"));
            assertTrue(ui.truth("new URL(document.querySelector('[data-droy-im-dialog=profile] .droy-im-avatar img').src).pathname==="+JSONObject.quote(userAvatar)));
            capture("global-user-profile");tap("[data-droy-im-dialog=profile] [data-droy-im-action=cancel]");checks.put("global-user-name-avatar-and-cross-companion-display");

            openChat("kaka");openProfile();tap("[data-droy-im-action=background]");
            tap("[data-droy-im-action=upload-background]");selectFixturePhoto();
            tap("[data-droy-im-dialog=chatBackground] [data-droy-im-action=apply]");
            DeviceUi.await("Uploaded background did not persist",15000,()->"upload".equals(profile("kaka").getJSONObject("chatBackground").getString("type")));
            tap("[data-droy-im-action=background]");capture("uploaded-background-preview");
            tap("[data-droy-im-background=default]");tap("[data-droy-im-dialog=chatBackground] [data-droy-im-action=apply]");
            DeviceUi.await("Default background did not restore",10000,()->"default".equals(profile("kaka").getJSONObject("chatBackground").getString("type")));
            ui.pressBack();capture("final-chat");checks.put("uploaded-background-and-default-restore");
            result.put("completed",true);
        } finally {
            if(fixturePhoto!=null)context.getContentResolver().delete(fixturePhoto,null,null);
            Files.write(new File(evidence,"ui-checks.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));
            report("DROY_CHAT_UI "+result);
        }
    }

    private boolean checked(String setting) throws Exception {
        return ui.truth("document.querySelector('[data-droy-im-setting="+setting+"]').checked===true");
    }

    private JSONObject profile(String id) throws Exception {
        JSONArray companions=request("/droy/companions",null).getJSONArray("companions");
        for(int i=0;i<companions.length();i++)if(companions.getJSONObject(i).getString("id").equals(id))return companions.getJSONObject(i);
        throw new AssertionError("Missing fixture companion "+id);
    }

    private int textCount(String id) throws Exception {
        JSONArray messages=request("/droy/companions/"+id+"/messages",null).getJSONArray("messages");int count=0;
        for(int i=0;i<messages.length();i++){JSONObject row=messages.getJSONObject(i);if(row.getString("role").equals("assistant")&&row.getString("type").equals("text"))count++;}
        return count;
    }

    private void awaitTextCount(String id,int count) throws Exception {
        DeviceUi.await("The local reply did not finish",20000,()->textCount(id)>=count
            &&request("/droy/companions/"+id+"/messages",null).getJSONObject("status").getString("phase").equals("idle"));
        DeviceUi.await("The actual reply was not rendered",10000,()->ui.truth("document.querySelectorAll('.droy-im-message.is-assistant .droy-im-bubble').length>0"));
        ui.awaitPaint();
    }

    private void send(String value) throws Exception {
        type(".droy-im-input",value);tap(".droy-im-send");
        DeviceUi.await("The user message was not accepted",10000,()->"".equals(ui.evaluate("document.querySelector('.droy-im-input').value")));
        ui.hideKeyboard();
    }

    private void type(String selector,String value) throws Exception {
        showKeyboardWithDiagnostic(selector,"ui-type-"+(++keyboardProbeSequence));
        int length=((String)ui.evaluate("document.querySelector("+JSONObject.quote(selector)+").value")).length();
        ui.replaceFocusedText(value,length);
        DeviceUi.await("The Android IME edit did not reach the form",5000,()->value.equals(ui.evaluate("document.querySelector("+JSONObject.quote(selector)+").value")));
    }

    private void closeKeyboardWithBack() throws Exception {
        String scenario="ui-keyboard-back-"+(++keyboardCloseSequence);
        List<JSONObject> events=Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.atomic.AtomicBoolean ended=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<android.view.View> observer=new java.util.concurrent.atomic.AtomicReference<>();
        android.webkit.WebView web=ui.web();
        instrumentation.runOnMainSync(()->{
            android.view.View view=new android.view.View(activity);observer.set(view);
            view.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                private final java.util.Set<android.view.WindowInsetsAnimation> prepared=new java.util.HashSet<>();
                private void record(String event,android.view.WindowInsetsAnimation animation,android.view.WindowInsets insets) {
                    try {
                        events.add(new JSONObject().put("event",event).put("elapsedRealtimeNs",SystemClock.elapsedRealtimeNanos())
                            .put("typeMask",animation.getTypeMask()).put("fraction",animation.getFraction()).put("durationMillis",animation.getDurationMillis())
                            .put("imeVisible",insets!=null&&insets.isVisible(android.view.WindowInsets.Type.ime()))
                            .put("imeBottom",insets==null?0:insets.getInsets(android.view.WindowInsets.Type.ime()).bottom));
                    } catch(org.json.JSONException failure){throw new AssertionError(failure);}
                }
                private boolean isIme(android.view.WindowInsetsAnimation animation){return (animation.getTypeMask()&android.view.WindowInsets.Type.ime())!=0;}
                @Override public void onPrepare(android.view.WindowInsetsAnimation animation) {
                    if(isIme(animation)){prepared.add(animation);record("prepare",animation,view.getRootWindowInsets());}
                }
                @Override public android.view.WindowInsetsAnimation.Bounds onStart(android.view.WindowInsetsAnimation animation,android.view.WindowInsetsAnimation.Bounds bounds) {
                    if(isIme(animation))record("start",animation,view.getRootWindowInsets());return bounds;
                }
                @Override public android.view.WindowInsets onProgress(android.view.WindowInsets insets,List<android.view.WindowInsetsAnimation> running) {
                    for(android.view.WindowInsetsAnimation animation:running)if(isIme(animation))record("progress",animation,insets);return insets;
                }
                @Override public void onEnd(android.view.WindowInsetsAnimation animation) {
                    if(isIme(animation)){record("end",animation,view.getRootWindowInsets());if(prepared.remove(animation))ended.set(true);}
                }
            });
            ((android.view.ViewGroup)web.getParent()).addView(view,new android.widget.FrameLayout.LayoutParams(0,0));
        });
        try {
            ui.pressBack();
            DeviceUi.await("Android Back did not finish the IME hide animation",5000,()->ended.get()
                &&!ui.nativeNavigationState().getBoolean("liveIme")&&ui.truth("document.documentElement.dataset.droyImeVisible!=='true'"));
        } finally {
            instrumentation.runOnMainSync(()->{
                android.view.View view=observer.get();view.setWindowInsetsAnimationCallback(null);
                if(view.getParent() instanceof android.view.ViewGroup parent)parent.removeView(view);
            });
            JSONObject diagnostic=new JSONObject().put("scenario",scenario).put("imeAnimationEnded",ended.get())
                .put("events",new JSONArray(new ArrayList<>(events))).put("nativeNavigationState",ui.nativeNavigationState());
            Files.write(new File(evidence,scenario+"-animation.json").toPath(),diagnostic.toString(2).getBytes(StandardCharsets.UTF_8));
            report("DROY_CHAT_IME_ANIMATION "+diagnostic);
        }
    }

    private void tap(String selector) throws Exception {ui.awaitPaint();ui.tap(selector);}

    private void openProfile() throws Exception {
        tap(".droy-im-chat-title");DeviceUi.await("Personal information did not open",5000,
            ()->ui.truth("!!document.querySelector('[data-droy-im-screen=profile]')"));ui.awaitPaint();
    }

    private void assertMessageAppearance() throws Exception {
        assertTrue("Some messages lack a readable minute timestamp",ui.truth("[...document.querySelectorAll('.droy-im-message')].every(e=>[...e.querySelectorAll('.droy-im-message-time')].some(t=>/^\\d{2}:\\d{2}$/.test(t.textContent.trim())))"));
        assertTrue("The user or companion avatar is not circular",ui.truth("[...document.querySelectorAll('.droy-im-message .droy-im-avatar')].every(e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return Math.abs(r.width-r.height)<1&&(s.borderRadius==='50%'||parseFloat(s.borderTopLeftRadius)>=r.width/2-1)})"));
        assertFalse("The chat overflows horizontally",ui.truth("document.documentElement.scrollWidth>innerWidth+1"));
        JSONArray times=(JSONArray)ui.evaluate("[...document.querySelectorAll('.droy-im-message-time')].map(e=>({at:e.dateTime,text:e.textContent.trim()}))");
        java.time.format.DateTimeFormatter minute=java.time.format.DateTimeFormatter.ofPattern("HH:mm",java.util.Locale.ROOT).withZone(java.time.ZoneId.systemDefault());
        for(int i=0;i<times.length();i++) {
            JSONObject time=times.getJSONObject(i);
            assertEquals("A bubble timestamp ignored the device timezone",minute.format(java.time.Instant.parse(time.getString("at"))),time.getString("text"));
        }
    }

    private void assertLocalClock() throws Exception {
        JSONObject clock=request("/droy/setup-chat-fixture",new JSONObject().put("operation","clock"));
        Files.write(new File(evidence,"model-clock-requests.json").toPath(),clock.toString(2).getBytes(StandardCharsets.UTF_8));
        java.time.OffsetDateTime actual=java.time.OffsetDateTime.parse(clock.getString("currentTime"));
        java.time.ZonedDateTime expected=java.time.ZonedDateTime.now();
        assertEquals("The model clock ignored Android's timezone",ProductBridge.deviceTimeZone(),clock.getString("timeZone"));
        assertEquals("The model clock has the wrong local offset",expected.getOffset(),actual.getOffset());
        assertTrue("The model clock is stale",Math.abs(expected.toInstant().toEpochMilli()-actual.toInstant().toEpochMilli())<10000);
        JSONObject planner=clock.getJSONObject("plannerRequest"),plannerClock=planner.getJSONObject("clockContext");
        assertEquals("The actual planner request omitted the device timezone",clock.getString("timeZone"),plannerClock.getString("timeZone"));
        assertEquals("The actual planner request omitted the local UTC offset",clock.getString("utcOffset"),plannerClock.getString("utcOffset"));
        assertEquals("The actual planner clock used the wrong local offset",expected.getOffset(),java.time.OffsetDateTime.parse(plannerClock.getString("currentTime")).getOffset());
        String replyer=clock.getJSONObject("replyerRequest").getString("context");
        assertTrue("The actual replyer request omitted the device timezone",replyer.contains(clock.getString("timeZone")));
        assertTrue("The actual replyer request omitted the local UTC offset",replyer.contains("UTC"+clock.getString("utcOffset")));
    }

    private void assertKeyboardGap() throws Exception {
        JSONObject value=(JSONObject)ui.evaluate("(()=>{const r=document.querySelector('.droy-im-composer').getBoundingClientRect(),v=visualViewport;return {bottom:r.bottom,viewportBottom:v.offsetTop+v.height,imeVisible:document.documentElement.dataset.droyImeVisible,safeBottom:parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-safe-bottom'))||0}})()");
        assertEquals("true",value.getString("imeVisible"));
        assertTrue("Composer is covered by the keyboard: "+value,value.getDouble("bottom")<=value.getDouble("viewportBottom")+1);
        android.view.WindowInsets insets=activity.getWindow().getDecorView().getRootWindowInsets();
        int ime=insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        int[] location=new int[2],decorLocation=new int[2],decorHeight=new int[1];android.webkit.WebView web=ui.web();
        instrumentation.runOnMainSync(()->{web.getLocationOnScreen(location);activity.getWindow().getDecorView().getLocationOnScreen(decorLocation);decorHeight[0]=activity.getWindow().getDecorView().getHeight();});
        JSONObject css=(JSONObject)ui.evaluate("({scale:devicePixelRatio,height:innerHeight})");
        double composerBottom=location[1]+value.getDouble("bottom")*css.getDouble("scale");
        int keyboardTop=decorLocation[1]+decorHeight[0]-ime;
        assertTrue("Composer leaves an extra keyboard gap: "+value+" nativeGap="+(keyboardTop-composerBottom),keyboardTop-composerBottom>=-3&&keyboardTop-composerBottom<=12*css.getDouble("scale"));
    }

    private Uri createFixturePhoto() throws Exception {
        ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,"droy-chat-fixture-"+UUID.randomUUID()+".png");
        values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/DroyChatFixture");
        values.put(MediaStore.Images.Media.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);assertNotNull(uri);
        Bitmap image=Bitmap.createBitmap(240,360,Bitmap.Config.ARGB_8888);image.eraseColor(android.graphics.Color.rgb(72,139,180));
        try(var output=context.getContentResolver().openOutputStream(uri)){assertNotNull(output);assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}
        finally{image.recycle();}
        values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);context.getContentResolver().update(uri,values,null,null);return uri;
    }

    private void openAlbum() throws Exception {
        if(!ui.truth("document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='attachments'"))tap(".droy-im-composer button[aria-label='更多'],.droy-im-composer button[aria-label='More']");
        tap(".droy-im-attachment-grid button:first-child");awaitPhotoPicker();
    }

    private void checkAlbumCancel() throws Exception {
        openAlbum();captureSystem("photo-picker");ui.pressBack();
        DeviceUi.await("Cancelling the photo picker did not return to chat",10000,()->ui.truth("!!document.querySelector('.droy-im-input')")&&foregroundIsDroy());
        assertFalse("Cancellation created a draft attachment",ui.truth("!!document.querySelector('.droy-im-draft-attachments')"));
    }

    private boolean foregroundIsDroy(){AccessibilityNodeInfo root=ui.automation().getRootInActiveWindow();return root!=null&&context.getPackageName().contentEquals(root.getPackageName());}

    private void awaitPhotoPicker() throws Exception {
        DeviceUi.await("The system photo picker did not open",10000,()->{
            AccessibilityNodeInfo root=ui.automation().getRootInActiveWindow();if(root==null)return false;
            String owner=String.valueOf(root.getPackageName());
            assertFalse("Photos opened the generic document picker",owner.contains("documentsui"));
            return owner.contains("photopicker")||owner.contains("providers.media");
        });
    }

    private void selectFixturePhoto() throws Exception {
        awaitPhotoPicker();
        String attempt="picker-selection-"+SystemClock.uptimeMillis();
        JSONObject diagnostic=new JSONObject();
        try {
            DeviceUi.await("Photo picker Photos tab was unavailable",5000,()->clickSystemText("Photos","照片"));
            DeviceUi.await("The Photos page did not expose one verified controlled image",5000,()->{
                AccessibilityNodeInfo root=ui.automation().getRootInActiveWindow();if(root==null)return false;
                JSONArray nodes=new JSONArray();List<Rect> candidates=new ArrayList<>();Bitmap screenshot=ui.automation().takeScreenshot();
                if(screenshot==null)return false;
                try {
                    inspectFixturePhotoNodes(root,screenshot,nodes,candidates);
                    diagnostic.put("nodes",nodes).put("matchingBounds",new JSONArray(candidates.stream().map(Rect::toShortString).toArray()));
                    assertTrue("The Photos page contains multiple matching images; refusing to choose",candidates.size()<=1);
                    if(candidates.size()!=1)return false;
                    Rect selected=candidates.get(0);
                    try(FileOutputStream output=new FileOutputStream(new File(evidence,attempt+".png"))){assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,output));}
                    diagnostic.put("selectedBounds",selected.toShortString()).put("identityCheck","nine interior pixels match synthetic RGB(72,139,180) or picker scrim RGB(58,111,145)");
                    ui.tapAt(selected.centerX(),selected.centerY());return true;
                } finally {screenshot.recycle();}
            });
        } finally {
            Files.write(new File(evidence,attempt+".json").toPath(),diagnostic.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        DeviceUi.await("The selected fixture photo did not return",15000,()->{
            if(foregroundIsDroy())return true;
            clickSystemText("Add","Add (1)","Done","添加","添加 (1)","完成");return false;
        });
        ui.awaitPaint();
    }

    private void inspectFixturePhotoNodes(AccessibilityNodeInfo node,Bitmap screenshot,JSONArray nodes,List<Rect> candidates) throws Exception {
        if(nodes.length()>=300)return;
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        String text=node.getText()==null?"":node.getText().toString();
        nodes.put(new JSONObject().put("class",String.valueOf(node.getClassName())).put("resourceId",String.valueOf(node.getViewIdResourceName()))
            .put("bounds",bounds.toShortString()).put("clickable",node.isClickable()).put("visible",node.isVisibleToUser()).put("children",node.getChildCount())
            .put("hasDescription",node.getContentDescription()!=null).put("label",java.util.Set.of("Photos","照片","Albums","相册","Add","Done","添加","完成").contains(text)?text:""));
        if(node.isVisibleToUser()&&node.isClickable()&&bounds.width()>=48&&bounds.height()>=48&&bounds.left>=0&&bounds.top>=0
            &&bounds.right<=screenshot.getWidth()&&bounds.bottom<=screenshot.getHeight()&&matchesFixtureBlue(screenshot,bounds)
            &&!candidates.contains(bounds))candidates.add(bounds);
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null)inspectFixturePhotoNodes(child,screenshot,nodes,candidates);}
    }

    private boolean matchesFixtureBlue(Bitmap screenshot,Rect bounds) {
        for(int[] expected:new int[][]{{72,139,180},{58,111,145}}) {
            boolean matches=true;
            for(int x:new int[]{25,50,75})for(int y:new int[]{25,50,75}) {
                int pixel=screenshot.getPixel(bounds.left+bounds.width()*x/100,bounds.top+bounds.height()*y/100);
                if(Math.abs(android.graphics.Color.red(pixel)-expected[0])>3||Math.abs(android.graphics.Color.green(pixel)-expected[1])>3
                    ||Math.abs(android.graphics.Color.blue(pixel)-expected[2])>3)matches=false;
            }
            if(matches)return true;
        }
        return false;
    }

    private boolean clickSystemText(String... labels){
        AccessibilityNodeInfo root=ui.automation().getRootInActiveWindow();if(root==null)return false;
        AccessibilityNodeInfo node=findSystemText(root,labels);if(node==null)return false;
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);if(bounds.isEmpty())return false;
        ui.tapAt(bounds.centerX(),bounds.centerY());return true;
    }

    private AccessibilityNodeInfo findSystemText(AccessibilityNodeInfo node,String[] labels){
        // Photo Picker's Compose virtual children are present in the tree but
        // are not consistently returned by findAccessibilityNodeInfosByText.
        if(node.isVisibleToUser())for(String label:labels)
            if(label.contentEquals(node.getText()==null?"":node.getText())
                ||label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))return node;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            AccessibilityNodeInfo found=findSystemText(child,labels);if(found!=null)return found;
        }return null;
    }

    private void captureSystem(String stage) throws Exception {
        Bitmap image=ui.automation().takeScreenshot();assertNotNull(image);
        try(FileOutputStream output=new FileOutputStream(new File(evidence,stage+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}finally{image.recycle();}
    }

    private void openChat(String id) throws Exception {
        ui.evaluate("window.DroyMobile.openCompanion(" + JSONObject.quote(id) + ");true");
        DeviceUi.await("The requested fixture chat did not open", 10000,
            () -> ui.truth("!!document.querySelector('[data-droy-im-screen=chat][data-companion-id="+id+"] .droy-im-input')"));
        DeviceUi.await("The history did not arrive", 10000,
            () -> ui.truth("!!document.querySelector('.droy-im-message,.droy-im-chat-empty')"));
        ui.awaitPaint();
    }

    private void openGeneral() throws Exception {
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("General settings did not open",10000,
            () -> ui.truth("!!document.querySelector('[data-droy-theme-choice=light]')"));
        ui.awaitPaint();
    }

    private JSONObject request(String path, JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+RuntimeService.webPort+path).openConnection();
        connection.setConnectTimeout(1000);connection.setReadTimeout(30000);
        connection.setRequestProperty("Cookie","droy_session="+RuntimeService.webToken);
        if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
            try(var output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
        try {
            int status=connection.getResponseCode();
            if(status!=200) {
                String detail="";
                if(path.equals("/droy/setup-chat-fixture") && connection.getErrorStream()!=null) {
                    try(InputStream input=connection.getErrorStream()) {
                        detail=" "+new String(input.readNBytes(1024),StandardCharsets.UTF_8);
                    }
                }
                fail("The authenticated fixture API failed: "+path+" status="+status+detail);
            }
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] chunk=new byte[8192];int count;while((count=input.read(chunk))>=0){assertTrue(bytes.size()+count<=4*1024*1024);bytes.write(chunk,0,count);}
                return new JSONObject(bytes.toString("UTF-8"));
            }
        }finally{connection.disconnect();}
    }

    private void capture(String stage) throws Exception {
        ui.awaitPaint();Bitmap image=ui.automation().takeScreenshot();assertNotNull(image);
        try(FileOutputStream output=new FileOutputStream(new File(evidence,stage+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}
        finally{image.recycle();}
    }

    private void report(String message) {
        Bundle status=new Bundle();status.putString("stream","\n"+message+"\n");instrumentation.sendStatus(2,status);
    }
}
