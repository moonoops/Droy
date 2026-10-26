package io.droyagent.app;

import android.content.Context;
import android.os.SystemClock;
import android.webkit.ServiceWorkerController;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import fi.iki.elonen.NanoHTTPD;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Exercises real WebView proxy routing and guard initialization with only local synthetic fixtures. */
@RunWith(AndroidJUnit4.class)
public class BrowserEgressDeviceTest {
    private WebView view;
    private BrowserEgress egress;
    private BrowserNetworkProxy proxy;
    private NanoHTTPD source;
    private ServerSocket protectedTarget;
    private final AtomicInteger protectedConnections = new AtomicInteger();
    private final AtomicInteger redirects = new AtomicInteger();
    private final AtomicInteger workerRequests = new AtomicInteger();
    private final AtomicInteger sourceRequests = new AtomicInteger();
    private final AtomicInteger mainErrorCode = new AtomicInteger();
    private final AtomicInteger mainHttpStatus = new AtomicInteger();
    private final AtomicInteger resourceErrors = new AtomicInteger();
    private final java.util.List<String> sourcePaths = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private volatile boolean closed;
    private boolean previousWorkerBlock;

    @Before public void createFixture() throws Exception {
        assertTrue("Stop DSH before process-wide WebView proxy tests", RuntimeService.canManageHistory());
        assertTrue("Device must support mandatory proxy override", WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE));
        assertTrue("Device must support mandatory document-start guard", WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT));
        protectedTarget = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        Thread monitor = new Thread(() -> {
            while (!closed) try (Socket socket = protectedTarget.accept()) { protectedConnections.incrementAndGet(); }
            catch (Exception stopping) { if (!closed) throw new RuntimeException(stopping); }
        }); monitor.setDaemon(true); monitor.start();
        source = new NanoHTTPD("127.0.0.1", 0) {
            @Override public Response serve(IHTTPSession request) {
                sourceRequests.incrementAndGet();
                String path = request.getUri();
                if (sourcePaths.size() < 30) sourcePaths.add(path);
                if (path.startsWith("/redirect")) {
                    redirects.incrementAndGet();
                    Response response = newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "");
                    response.addHeader("Location", "http://127.0.0.1:" + protectedTarget.getLocalPort() + "/protected"); return noStore(response);
                }
                if (path.equals("/pending")) {
                    try { Thread.sleep(150); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return noStore(newFixedLengthResponse(Response.Status.OK, "text/html", "<html><body>pending frame fixture</body></html>"));
                }
                if (path.equals("/worker.js")) { workerRequests.incrementAndGet(); return noStore(newFixedLengthResponse(Response.Status.OK, "application/javascript", "postMessage('worker started')")); }
                String html = "<html><head><title>Network fixture</title></head><body>public fixture"
                    + "<img src='/redirect-image' onerror='window.imageDone=true'>"
                    + "<iframe src='/redirect-frame' onload='window.frameDone=true'></iframe>"
                    + "<iframe id='parser-blank'></iframe><iframe id='parser-pending' src='/pending'></iframe>"
                    + "<script>"
                    + "window.guardChecks={};"
                    + "const denied=(realm,name)=>{try{const arg=name==='RTCPeerConnection'?{iceServers:[]}:name==='WebTransport'?'https://public.fixture.test/transport':'http://public.fixture.test/worker.js';const value=new realm[name](arg);try{if(value.closed)value.closed.catch(()=>{});if(value.ready)value.ready.catch(()=>{});if(value.close)value.close();if(value.terminate)value.terminate()}catch(e){}return false}catch(e){return e.name==='NotAllowedError'}};"
                    + "for(const name of ['RTCPeerConnection','WebTransport','Worker','SharedWorker'])guardChecks[name]=denied(window,name);"
                    + "for(const [route,index] of [['parserBlank',1],['parserPending',2]])for(const name of ['RTCPeerConnection','WebTransport','Worker'])guardChecks[route+'_'+name]=denied(window.frames[index],name);"
                    + "const blank=document.createElement('iframe');document.body.append(blank);guardChecks.contentWindow=denied(blank.contentWindow,'RTCPeerConnection');"
                    + "for(const route of ['frames','index']){const index=window.frames.length;document.body.append(document.createElement('iframe'));const realm=route==='frames'?window.frames[index]:window[index];for(const name of ['RTCPeerConnection','WebTransport','Worker'])guardChecks[route+'_'+name]=denied(realm,name)}"
                    + "try{document.write('<iframe></iframe>');guardChecks.documentWrite=false}catch(e){guardChecks.documentWrite=e.name==='NotAllowedError'}"
                    + "try{const fragment=document.createDocumentFragment();fragment.append(document.createElement('iframe'));const code=document.createElement('script');code.textContent='window.compoundScriptRan=true';fragment.append(code);document.body.append(fragment);guardChecks.compoundFragment=false}catch(e){guardChecks.compoundFragment=e.name==='NotAllowedError'&&!window.compoundScriptRan}"
                    + "try{const frame=document.createElement('iframe'),code=document.createElement('script');code.textContent='window.compoundScriptRan=true';document.body.append(frame,code);guardChecks.compoundArguments=false}catch(e){guardChecks.compoundArguments=e.name==='NotAllowedError'&&!window.compoundScriptRan}"
                    + "const nestedIndex=window.frames.length;document.body.appendChild(document.createElement('iframe'));const outer=window.frames[nestedIndex];outer.document.body.appendChild(outer.document.createElement('iframe'));for(const name of ['RTCPeerConnection','WebTransport','Worker'])guardChecks['nested_'+name]=denied(outer.frames[0],name);"
                    + "window.captureSrcdoc=realm=>{for(const name of ['RTCPeerConnection','WebTransport','Worker'])guardChecks['srcdoc_'+name]=denied(realm,name);window.srcdocReady=true};"
                    + "const srcdoc=document.createElement('iframe');srcdoc.srcdoc=\"<html><body onload='parent.captureSrcdoc(window)'>srcdoc fixture</body></html>\";document.body.append(srcdoc);"
                    + "try{navigator.serviceWorker.register('/worker.js').then(()=>guardChecks.serviceWorker=false,()=>guardChecks.serviceWorker=true)}catch(e){guardChecks.serviceWorker=true}"
                    + "try{const ws=new WebSocket('ws://127.0.0.1:" + protectedTarget.getLocalPort() + "/socket');ws.onerror=()=>window.wsDenied=true;ws.onopen=()=>window.wsOpened=true}catch(e){window.wsDenied=true}"
                    + "</script></body></html>";
                return noStore(newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html));
            }
            private Response noStore(Response response) {
                response.addHeader("Cache-Control", "no-store, max-age=0");
                response.addHeader("Pragma", "no-cache");
                response.addHeader("Expires", "0");
                return response;
            }
        }; source.start(5000, false);
        proxy = new BrowserNetworkProxy(host -> {
            if (!host.equals("public.fixture.test")) return InetAddress.getAllByName(host);
            return new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte)184, (byte)216, 34})};
        }, (address, port) -> {
            assertArrayEquals(new byte[]{93, (byte)184, (byte)216, 34}, address.getAddress());
            return new Socket("127.0.0.1", source.getListeningPort());
        });
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        CountDownLatch ready = new CountDownLatch(1); AtomicReference<String> rejected = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            previousWorkerBlock = ServiceWorkerController.getInstance().getServiceWorkerWebSettings().getBlockNetworkLoads();
            view = new WebView(context); view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
            view.setWebViewClient(new WebViewClient() {
                @Override public void onReceivedError(WebView browser, android.webkit.WebResourceRequest request, android.webkit.WebResourceError error) {
                    if (request.isForMainFrame()) mainErrorCode.set(error.getErrorCode()); else resourceErrors.incrementAndGet();
                }
                @Override public void onReceivedHttpError(WebView browser, android.webkit.WebResourceRequest request, android.webkit.WebResourceResponse response) {
                    if (request.isForMainFrame()) mainHttpStatus.set(response.getStatusCode());
                }
            });
            egress = new BrowserEgress(view, proxy);
            egress.prepare(ready::countDown, reason -> { rejected.set(reason); ready.countDown(); });
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS)); assertNull(rejected.get());
    }

    @After public void tearDown() throws Exception {
        closed = true;
        if (source != null) source.stop();
        if (protectedTarget != null) protectedTarget.close();
        CountDownLatch cleared = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (egress != null) egress.close();
            if (view != null) view.destroy();
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(previousWorkerBlock);
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) ProxyController.getInstance().clearProxyOverride(Runnable::run, cleared::countDown); else cleared.countDown();
        });
        assertTrue(cleared.await(5, TimeUnit.SECONDS));
        if (proxy != null) proxy.close();
    }

    @Test public void mainFramePublicRedirectCannotBypassProxyToLoopback() throws Exception {
        load("http://public.fixture.test/redirect-main");
        await("Redirect did not reach the proxy", () -> redirects.get() > 0 && proxy.rejectedRequests() > 0);
        assertEquals(0, protectedConnections.get());
    }

    @Test public void subresourceSubframeAndWebSocketStayAwayFromProtectedTarget() throws Exception {
        load("http://public.fixture.test/page");
        await("Public fixture, redirects or WebSocket completion missing", () -> redirects.get() >= 2
            && "true".equals(script("Boolean(window.wsDenied && window.imageDone && window.frameDone)")));
        assertTrue("Both image and frame redirects must be exercised", redirects.get() >= 2);
        assertFalse("Private socket unexpectedly opened", "true".equals(script("Boolean(window.wsOpened)")));
        assertEquals(0, protectedConnections.get());
        assertTrue("Redirected HTTP resources must traverse the proxy", proxy.rejectedRequests() > 0);
    }

    @Test public void rtcWebTransportAndPristineWorkerRealmsAreDisabledBeforePageScripts() throws Exception {
        load("http://public.fixture.test/page");
        await("Document guard checks did not finish", () -> "true".equals(script("Boolean(window.guardChecks && guardChecks.serviceWorker && window.srcdocReady)")));
        String checks = script("JSON.stringify(guardChecks)");
        assertEquals("Guard checks: " + checks, "true", script("Object.values(guardChecks).every(Boolean) && Object.getOwnPropertyDescriptor(window,'WebTransport').configurable===false"));
        assertEquals("true", script("['frames','index','parserBlank','parserPending','nested','srcdoc'].every(route=>['RTCPeerConnection','WebTransport','Worker'].every(name=>guardChecks[route+'_'+name]===true))"));
        assertEquals(0, workerRequests.get());
        assertEquals(0, protectedConnections.get());
    }

    private void load(String url) { InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.loadUrl(url)); }
    private String script(String source) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> value = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> view.evaluateJavascript(source, result -> { value.set(result); done.countDown(); }));
        assertTrue(done.await(3, TimeUnit.SECONDS)); return value.get();
    }
    private interface Condition { boolean met() throws Exception; }
    private void await(String message, Condition condition) throws Exception {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < until) { if (condition.met()) return; SystemClock.sleep(100); }
        fail(message + "; " + diagnostics());
    }
    private String diagnostics() {
        return "proxyAccepted=" + (proxy == null ? -1 : proxy.acceptedRequests())
            + ", proxyConnected=" + (proxy == null ? -1 : proxy.connectedDestinations())
            + ", proxyRejected=" + (proxy == null ? -1 : proxy.rejectedRequests())
            + ", proxyLastRejection=" + (proxy == null ? "none" : proxy.lastRejection())
            + ", sourceRequests=" + sourceRequests.get() + ", sourcePaths=" + sourcePaths + ", redirects=" + redirects.get()
            + ", protectedConnections=" + protectedConnections.get()
            + ", mainErrorCode=" + mainErrorCode.get() + ", mainHttpStatus=" + mainHttpStatus.get()
            + ", subresourceErrorCount=" + resourceErrors.get();
    }
}
