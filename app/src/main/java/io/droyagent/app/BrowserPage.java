package io.droyagent.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.WebView;
import io.droyagent.androiduse.ActionException;
import io.droyagent.androiduse.Cancellation;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** A single DSH browser tab; references and navigation belong to this page only. */
final class BrowserPage {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    final String id;
    final WebView view;
    final Context context;
    volatile boolean ready;
    volatile boolean loading;
    volatile boolean interacting;
    volatile boolean closed;
    volatile String pageError;
    private long generation;
    private long navigationRequest;
    synchronized long beginNavigation() { invalidate(); return ++navigationRequest; }
    synchronized boolean acceptsNavigation(long request) { return !closed && navigationRequest == request; }
    private String currentSnapshot;
    private long snapshotAt;

    BrowserPage(Context context, String id, WebView view, boolean ready) {
        this.context = context; this.id = id; this.view = view; this.ready = ready;
    }
    synchronized void invalidate() { generation++; currentSnapshot = null; snapshotAt = 0; }
    void started() { loading = true; pageError = null; invalidate(); }
    void finished() { loading = false; }
    void failed(String error) { loading = false; pageError = error; invalidate(); }
    void userGesture(boolean active) { interacting = active; invalidate(); }
    JSONObject frame() throws Exception {
        return BrowserSession.onMain(() -> {}, () -> new JSONObject().put("sessionId", id).put("sessionOpen", !closed)
            .put("url", view.getUrl() == null ? "" : view.getUrl()).put("title", view.getTitle() == null ? "" : view.getTitle())
            .put("loading", loading).put("canGoBack", view.canGoBack()).put("canGoForward", view.canGoForward())
            .put("revision", generation).put("error", pageError == null ? JSONObject.NULL : new JSONObject().put("description", pageError)));
    }
    JSONObject execute(String action, JSONObject args, Cancellation cancellation) throws Exception {
        cancellation.check();
        switch (action) {
            case "browser_status": return frame();
            case "browser_open": return navigate(args.getString("url"), cancellation);
            case "browser_observe": return observe(cancellation);
            case "browser_click": return elementAction(args, false, cancellation);
            case "browser_input": return elementAction(args, true, cancellation);
            case "browser_wait": return waitFor(args, cancellation);
            case "browser_back": return history("back", cancellation);
            default: throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("不支持该浏览器操作", "Unsupported browser operation"));
        }
    }
    JSONObject navigate(String url, Cancellation cancellation) throws Exception {
        BrowserSession.UrlPolicy.parse(url);
        long request = beginNavigation();
        BrowserSession.UrlPolicy.validatePublic(context, url);
        return BrowserSession.onMain(cancellation, () -> {
            requireAvailable();
            if (!acceptsNavigation(request)) throw new ActionException("BROWSER_NAVIGATION_SUPERSEDED", io.droyagent.app.NativeLocale.text("网页导航已被更新的操作取代", "Web navigation was superseded by a newer operation"));
            started(); view.loadUrl(url);
            return receipt("navigation_dispatched", io.droyagent.app.NativeLocale.text("已发起网页导航，请等待并观察页面。", "Web navigation was requested. Please wait and observe the page."));
        });
    }
    JSONObject history(String action, Cancellation cancellation) throws Exception {
        return BrowserSession.onMain(cancellation, () -> {
            requireAvailable();
            if (action.equals("back") && !view.canGoBack() || action.equals("forward") && !view.canGoForward())
                throw new ActionException("NO_BROWSER_HISTORY", io.droyagent.app.NativeLocale.text("此标签没有对应浏览历史", "This tab has no corresponding browser history"));
            beginNavigation(); started();
            if (action.equals("back")) view.goBack(); else if (action.equals("forward")) view.goForward(); else view.reload();
            return receipt("navigation_dispatched", io.droyagent.app.NativeLocale.text("网页导航已发出，请观察结果。", "Web navigation was sent. Please observe the result."));
        });
    }
    private JSONObject observe(Cancellation cancellation) throws Exception {
        String snapshot = UUID.randomUUID().toString();
        long[] observedGeneration = new long[1];
        JSONObject result = evaluate(cancellation, () -> {
            synchronized (this) { observedGeneration[0] = generation; }
            return OBSERVE_SCRIPT.replace("SNAPSHOT_TOKEN", JSONObject.quote(snapshot));
        });
        synchronized (this) {
            requireAvailable();
            if (generation != observedGeneration[0]) throw new ActionException("BROWSER_CHANGED", io.droyagent.app.NativeLocale.text("网页在读取过程中发生变化，请重新观察", "The page changed while being read. Please observe again"));
            currentSnapshot = snapshot; snapshotAt = SystemClock.elapsedRealtime();
            result.put("sessionId", id).put("snapshotId", snapshot).put("loading", loading).put("goalVerified", false);
            if (pageError != null) result.put("pageError", pageError);
        }
        return result;
    }
    private JSONObject elementAction(JSONObject args, boolean input, Cancellation cancellation) throws Exception {
        String snapshot = args.getString("snapshotId"), ref = args.getString("ref");
        if (ref.length() > 24) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("网页元素标识无效", "Invalid web element ID"));
        String value = input ? args.getString("text") : "";
        if (value.length() > 10000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("输入文字过长", "Input text is too long"));
        JSONObject result = evaluate(cancellation, () -> {
            synchronized (this) {
                if (!snapshot.equals(currentSnapshot) || SystemClock.elapsedRealtime() - snapshotAt > 15000)
                    throw new ActionException("STALE_BROWSER_SNAPSHOT", io.droyagent.app.NativeLocale.text("页面依据已过期，请重新观察后再操作", "Page evidence expired. Please observe again before acting"));
                currentSnapshot = null;
            }
            return actionScript().replace("IS_INPUT", input ? "true" : "false")
                .replace("SNAPSHOT_TOKEN", JSONObject.quote(snapshot)).replace("ELEMENT_REF", JSONObject.quote(ref))
                .replace("INPUT_VALUE", JSONObject.quote(value));
        });
        if (!result.optBoolean("accepted")) throw new ActionException(result.optString("code", "BROWSER_ACTION_REJECTED"), result.optString("message", io.droyagent.app.NativeLocale.text("目标已变化，请重新观察", "The target changed. Please observe again")));
        JSONObject receipt = receipt("action_dispatched", io.droyagent.app.NativeLocale.text("网页操作已发出，请重新观察核实业务结果。", "The web action was sent. Please observe again to verify the result."));
        // Input acceptance is separate from dispatch and never verifies form submission.
        if (input) receipt.put("textVerified", result.optBoolean("textVerified")).put("submitActionRequested", false).put("verificationScope", "immediate_dom_value");
        return receipt;
    }
    private JSONObject waitFor(JSONObject args, Cancellation cancellation) throws Exception {
        long timeout = args.optLong("timeoutMs", 15000);
        if (timeout < 100 || timeout > 60000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("等待时间需为 100 至 60000 毫秒", "Wait duration must be 100–60,000 milliseconds"));
        String text = args.optString("text", "");
        if (text.length() > 1000) throw new IllegalArgumentException(io.droyagent.app.NativeLocale.text("等待文字过长", "Wait text is too long"));
        long until = SystemClock.elapsedRealtime() + timeout;
        JSONObject observation = null;
        String lastObservation = "condition_not_matched";
        do {
            cancellation.check();
            try {
                JSONObject current = observe(cancellation);
                synchronized (this) {
                    // Navigation/user input can invalidate a completed read before
                    // the waiting worker evaluates it. Do not match an old epoch.
                    if (!isCurrentObservation(current)) {
                        observation = null;
                        lastObservation = "browser_changed";
                    } else {
                        observation = current;
                        lastObservation = "condition_not_matched";
                        boolean matched = text.isEmpty() ? !current.optBoolean("loading") && current.optBoolean("documentReady") : current.optString("text").contains(text);
                        if (matched) return current.put("status", "condition_met").put("conditionVerified", true);
                        if (current.has("pageError")) return current.put("status", "page_error").put("conditionVerified", false);
                    }
                }
            } catch (ActionException error) {
                if (!"BROWSER_DOCUMENT_LOADING".equals(error.getCode()) && !"BROWSER_CHANGED".equals(error.getCode())) throw error;
                // Only reads are repeated. Prior clicks/inputs are never replayed.
                observation = null;
                lastObservation = "BROWSER_CHANGED".equals(error.getCode()) ? "browser_changed" : "document_loading";
            }
            if (SystemClock.elapsedRealtime() >= until) break;
            Thread.sleep(Math.min(250, Math.max(1, until - SystemClock.elapsedRealtime())));
        } while (true);
        cancellation.check();
        synchronized (this) {
            if (observation != null && isCurrentObservation(observation)) {
                return observation.put("status", "wait_timeout").put("conditionVerified", false)
                    .put("message", io.droyagent.app.NativeLocale.text("等待超时；此前动作可能已经发生，不自动重复执行。", "Wait timed out. Previous actions may already have occurred and will not be repeated automatically."));
            }
        }
        // A failed/finally invalidated read must not return refs from an earlier
        // document. The frame contains navigation state without actionable refs.
        return frame().put("goalVerified", false).put("lastObservation", lastObservation)
            .put("status", "wait_timeout").put("conditionVerified", false)
            .put("message", io.droyagent.app.NativeLocale.text("等待超时；此前动作可能已经发生，不自动重复执行。", "Wait timed out. Previous actions may already have occurred and will not be repeated automatically."));
    }

    private synchronized boolean isCurrentObservation(JSONObject observation) {
        return currentSnapshot != null && currentSnapshot.equals(observation.optString("snapshotId"))
            && SystemClock.elapsedRealtime() - snapshotAt <= 15000;
    }
    private JSONObject evaluate(Cancellation cancellation, Callable<String> script) throws Exception {
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        MAIN.post(() -> {
            try {
                if (future.isDone()) return;
                cancellation.check(); requireAvailable();
                String source = script.call(); cancellation.check();
                view.evaluateJavascript(source, raw -> {
                    try {
                        cancellation.check(); requireAvailable();
                        Object decoded = new org.json.JSONTokener(raw).nextValue();
                        if (!(decoded instanceof String)) throw new ActionException("BROWSER_SCRIPT_FAILED", io.droyagent.app.NativeLocale.text("页面可能已导航或关闭", "The page may have navigated or closed"));
                        JSONObject result = new JSONObject((String) decoded);
                        if ("document_loading".equals(result.optString("error")))
                            throw new ActionException("BROWSER_DOCUMENT_LOADING", io.droyagent.app.NativeLocale.text("网页文档尚未就绪，请等待后观察", "The web document is not ready. Please wait and observe"));
                        if (result.has("error")) throw new ActionException("BROWSER_SCRIPT_FAILED",
                            io.droyagent.app.NativeLocale.text("网页读取失败（", "Web page read failed (") + result.optString("failureType", "Error") + io.droyagent.app.NativeLocale.text("），请重新观察", "). Please observe again"));
                        future.complete(result);
                    } catch (Exception error) { future.completeExceptionally(error); }
                });
            } catch (Exception error) { future.completeExceptionally(error); }
        });
        return BrowserSession.await(future, cancellation, 8000);
    }
    private void requireAvailable() throws ActionException {
        if (closed) throw new ActionException("NO_BROWSER_SESSION", io.droyagent.app.NativeLocale.text("浏览器标签已关闭，请重新打开网页", "Browser tab closed. Please reopen the page"));
        if (!ready) throw new ActionException("BROWSER_NOT_READY", pageError == null ? io.droyagent.app.NativeLocale.text("网页容器尚未就绪，请稍后观察", "The web container is not ready. Please observe shortly") : pageError);
        if (interacting) throw new ActionException("BROWSER_BUSY", io.droyagent.app.NativeLocale.text("用户正在触摸网页，请重新观察后继续", "The user is touching the web page. Please observe again before continuing"));
    }
    private JSONObject receipt(String status, String message) throws Exception {
        return new JSONObject().put("sessionId", id).put("status", status).put("goalVerified", false).put("message", message);
    }
    private static final String OBSERVE_SCRIPT = """
        (()=>{try{
          if(!document.documentElement)return JSON.stringify({error:'document_loading'});
          const token=SNAPSHOT_TOKEN;
          const fingerprint=e=>{
            const form=e.form||e.closest('form'),item=e.closest('li,article,[role=listitem],[data-sku]');
            const attributes=['id','name','role','title','placeholder','href','aria-label','aria-labelledby','aria-describedby','formaction','formmethod','formtarget','autocomplete'];
            const labels=e.labels?Array.from(e.labels).map(n=>n.textContent):[];
            for(const key of ['aria-labelledby','aria-describedby'])for(const id of (e.getAttribute(key)||'').split(/\\s+/)){
              const label=id&&document.getElementById(id);if(label)labels.push(label.textContent);
            }
            return JSON.stringify([e.tagName,e.type,attributes.map(key=>e.getAttribute(key)),e.textContent,labels,
              form?[form.action,form.method,form.target,form.id]:null,
              item?[item.tagName,item.id,item.getAttribute('data-sku'),item.textContent]:null]).slice(0,12000);
          };
          const visible=e=>{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'};
          if(window.__droyBrowser&&window.__droyBrowser.observer)window.__droyBrowser.observer.disconnect();
          const state={token,revision:0,refs:{},url:location.href};
          window.__droyBrowser=state;
          let parts=[],size=0,truncated=false;
          const walker=document.createTreeWalker(document.body||document.documentElement,NodeFilter.SHOW_TEXT);
          let n,count=0;
          while((n=walker.nextNode())&&count++<12000){const p=n.parentElement;if(!p||p.closest('script,style,noscript,input[type=password]')||!visible(p))continue;
            const t=n.textContent.trim();if(!t)continue;const piece=t.slice(0,Math.max(0,6000-size));parts.push(piece);size+=piece.length+1;if(size>=6000){truncated=true;break}}
          let elements=[],seen=0;
          for(const e of document.querySelectorAll('a[href],button,input,textarea,select,[role=button],[role=link]')){
            if(!visible(e)||e.disabled||e.type==='password'||e.type==='hidden'||e.closest('[contenteditable=true]'))continue;
            if(elements.length>=60){truncated=true;break}const ref='b'+(++seen);
            const signature=fingerprint(e);
            const r=e.getBoundingClientRect();
            state.refs[ref]={element:e,fingerprint:signature,value:('value' in e)?e.value:undefined,bounds:[r.x,r.y,r.width,r.height]};
            elements.push({ref,tag:e.tagName.toLowerCase(),type:e.type||'',label:(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.innerText||'').slice(0,160),value:(e.tagName==='INPUT'||e.tagName==='TEXTAREA')?e.value.slice(0,300):undefined});
          }
          state.revision=0;
          return JSON.stringify({url:location.href.slice(0,4096),title:document.title.slice(0,300),text:parts.join('\\n'),elements,truncated,documentReady:document.readyState==='complete'});
        }catch(e){return JSON.stringify({error:'read_failed',failureType:['TypeError','ReferenceError','SyntaxError','SecurityError','NotSupportedError','InvalidStateError'].includes(e?.name)?e.name:'Error'})}})()
        """;

    private static String actionScript() {
        return ACTION_SCRIPT
            .replace("网页内容已变化，请重新观察", NativeLocale.text("网页内容已变化，请重新观察", "Web content changed. Please observe again"))
            .replace("滚动后目标已变化，请重新观察", NativeLocale.text("滚动后目标已变化，请重新观察", "The target changed after scrolling. Please observe again"))
            .replace("目标被遮挡或不可交互，请重新观察", NativeLocale.text("目标被遮挡或不可交互，请重新观察", "The target is obscured or cannot be interacted with. Please observe again"))
            .replace("该元素不能作为普通文字输入框", NativeLocale.text("该元素不能作为普通文字输入框", "This element cannot be used as a regular text input"));
    }

    private static final String ACTION_SCRIPT = """
        (()=>{try{
          const s=window.__droyBrowser;
          const fingerprint=e=>{
            const form=e.form||e.closest('form'),item=e.closest('li,article,[role=listitem],[data-sku]');
            const attributes=['id','name','role','title','placeholder','href','aria-label','aria-labelledby','aria-describedby','formaction','formmethod','formtarget','autocomplete'];
            const labels=e.labels?Array.from(e.labels).map(n=>n.textContent):[];
            for(const key of ['aria-labelledby','aria-describedby'])for(const id of (e.getAttribute(key)||'').split(/\\s+/)){
              const label=id&&document.getElementById(id);if(label)labels.push(label.textContent);
            }
            return JSON.stringify([e.tagName,e.type,attributes.map(key=>e.getAttribute(key)),e.textContent,labels,
              form?[form.action,form.method,form.target,form.id]:null,
              item?[item.tagName,item.id,item.getAttribute('data-sku'),item.textContent]:null]).slice(0,12000);
          };
          if(!s||s.token!==SNAPSHOT_TOKEN||s.url!==location.href)return JSON.stringify({accepted:false,code:'STALE_BROWSER_SNAPSHOT',message:'网页内容已变化，请重新观察'});
          const item=s.refs[ELEMENT_REF],e=item&&item.element;
          if(!e||!e.isConnected||e.disabled||e.type==='password')return JSON.stringify({accepted:false,code:'STALE_BROWSER_ELEMENT'});
          let r=e.getBoundingClientRect();const style=getComputedStyle(e);
          const signature=fingerprint(e);
          if(r.width<=0||r.height<=0||style.visibility==='hidden'||style.display==='none'||signature!==item.fingerprint||('value' in e&&e.value!==item.value)||[r.x,r.y,r.width,r.height].some((v,i)=>Math.abs(v-item.bounds[i])>1))return JSON.stringify({accepted:false,code:'STALE_BROWSER_ELEMENT'});
          let scrolled=false;
          if(r.x+r.width/2<0||r.y+r.height/2<0||r.x+r.width/2>=innerWidth||r.y+r.height/2>=innerHeight){
            e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});scrolled=true;r=e.getBoundingClientRect();
            if(!e.isConnected||fingerprint(e)!==item.fingerprint||('value' in e&&e.value!==item.value))return JSON.stringify({accepted:false,code:'STALE_BROWSER_ELEMENT',message:'滚动后目标已变化，请重新观察'});
          }
          const hit=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);
          if(!hit||!(hit===e||e.contains(hit)))return JSON.stringify({accepted:false,code:'STALE_BROWSER_ELEMENT',message:'目标被遮挡或不可交互，请重新观察'});
          if(IS_INPUT){
            if(!['INPUT','TEXTAREA'].includes(e.tagName)||e.readOnly||!['text','search','email','url','tel','number','textarea',''].includes(e.type||''))return JSON.stringify({accepted:false,code:'BROWSER_INPUT_UNSUPPORTED',message:'该元素不能作为普通文字输入框'});
            const proto=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
            Object.getOwnPropertyDescriptor(proto,'value').set.call(e,INPUT_VALUE);e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
          }else{e.click()}
          s.refs={};s.token=null;return JSON.stringify({accepted:true,textVerified:IS_INPUT?e.value===INPUT_VALUE:undefined,scrolledIntoView:scrolled});
        }catch(e){return JSON.stringify({accepted:false,code:'BROWSER_SCRIPT_FAILED'})}})()
        """;
}
