package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.WindowInsetsController;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static org.junit.Assert.*;

/** Invoked by SetupFlowDeviceTest so all mutations stay inside its preserved local-only home. */
final class NavigationMessagesDeviceTest {
    private final Instrumentation instrumentation;
    private final Context context;
    private final MainActivity activity;
    private final DeviceUi ui;
    private final File evidence;
    private final JSONObject result = new JSONObject();
    private final JSONArray diagnostics = new JSONArray();
    private double headerHeight, titleSize;

    NavigationMessagesDeviceTest(Instrumentation instrumentation, Context context, MainActivity activity, DeviceUi ui) {
        this.instrumentation=instrumentation;this.context=context;this.activity=activity;this.ui=ui;
        String label=InstrumentationRegistry.getArguments().getString("evidenceLabel","navigation").replaceAll("[^A-Za-z0-9_-]","_");
        evidence=new File(context.getExternalFilesDir(null),"navigation-messages-evidence/"+label);
        assertTrue(evidence.isDirectory()||evidence.mkdirs());
    }

    void run() throws Exception {
        assertTrue("Navigation layout acceptance uses an emulator",Build.FINGERPRINT.contains("generic")
            ||Build.FINGERPRINT.contains("emulator")||Build.MODEL.contains("sdk_gphone"));
        result.put("device",Build.MODEL).put("sdk",Build.VERSION.SDK_INT).put("cloudModel",false)
            .put("locale",InstrumentationRegistry.getArguments().getString("setupLocale","zh"))
            .put("theme",InstrumentationRegistry.getArguments().getString("layoutTheme","light"));
        try {
            awaitScreen("list");
            installTouchDiagnostics();
            verifyTheme("messages", ".droy-im-header");
            collectServedScriptFingerprint();
            boolean focusOnly="work-focus".equals(InstrumentationRegistry.getArguments().getString("navigationPhase",""));
            boolean settingsOnly="settings-overflow".equals(InstrumentationRegistry.getArguments().getString("navigationPhase",""));
            result.put("phase",focusOnly?"work-focus":settingsOnly?"settings-overflow":"full");
            if(focusOnly||settingsOnly) {
                headerHeight=number("document.querySelector('.droy-im-header').getBoundingClientRect().height");
                titleSize=number("parseFloat(getComputedStyle(document.querySelector('.droy-im-mode-trigger')).fontSize)");
                if(settingsOnly)messagesSettings();
            } else {
                listOperations();
                partnerProfileAndHeader();
                messagesSettings();
            }
            workNavigationAndKeyboard();
            result.put("completed",true);
        } finally {
            try {collectTouchDiagnostics("final");}
            catch(Exception|AssertionError failure) {result.put("touchDiagnosticsFailure",failure.getClass().getSimpleName());}
            try {collectProfileDiagnostics();}
            catch(Exception|AssertionError failure) {result.put("profileDiagnosticsFailure",failure.getClass().getSimpleName());}
            try {removeTouchDiagnostics();}
            catch(Exception|AssertionError failure) {result.put("touchDiagnosticsCleanupFailure",failure.getClass().getSimpleName());}
            try {capture(result.optBoolean("completed")?"final":"failure");}
            finally {Files.write(new File(evidence,"result.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
    }

    void settingsRenderingControl() throws Exception {
        assertTrue("Rendering controls run only on an emulator",Build.MODEL.contains("sdk")||Build.FINGERPRINT.contains("emulator"));
        result.put("device",Build.MODEL).put("sdk",Build.VERSION.SDK_INT).put("cloudModel",false).put("diagnosticOnly",true)
            .put("phase","settings-rendering-control").put("locale",InstrumentationRegistry.getArguments().getString("setupLocale","zh"))
            .put("theme",InstrumentationRegistry.getArguments().getString("layoutTheme","light"));
        try {
            awaitScreen("list");
            ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
            DeviceUi.await("The existing general settings event did not open its portal",5000,
                ()->ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
            ui.awaitPaint();collectSettingsVisualDiagnostics("settingsControl");result.put("settingsControlGeometry",viewportGeometry());
            capture("settings-rendering-control-open");
            ui.pressBack();awaitSettingsClosed();awaitScreen("list");capture("settings-rendering-control-closed");
            result.put("completed",true);
        } finally {
            try {capture(result.optBoolean("completed")?"final":"failure");}
            finally {Files.write(new File(evidence,"result.json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
    }

    private void installTouchDiagnostics() throws Exception {
        ui.evaluate("""
            (()=>{
              if(window.__navigationTouchProbe)return true;
              const p=window.__navigationTouchProbe={events:[],requests:[],focusCalls:[],focusEvents:[],fetch:window.fetch,focus:HTMLElement.prototype.focus};
              p.describe=e=>e?{tag:e.tagName,action:e.closest?.('[data-conversation-action]')?.dataset.conversationAction||null,
                companion:e.closest?.('[data-droy-companion]')?.dataset.droyCompanion||null,
                row:e.closest?.('[data-conversation-id]')?.dataset.conversationId||null}:null;
              p.focusType=e=>e?{tag:e.tagName,role:e.getAttribute?.('role')||null,
                composer:e.hasAttribute?.('data-composer-input')===true,editable:e.isContentEditable===true}:null;
              p.mode=()=>document.body.hasAttribute('data-droy-im-active')?'messages':document.body.hasAttribute('data-droy-work-active')?'work':'other';
              p.focusWrapped=function(){
                if(this.matches?.('[data-composer-input]')||this.closest?.('[data-composer-input]')||this.isContentEditable&&this.closest?.('[data-composer-seat]')){
                  if(p.focusCalls.length>=60)p.focusCalls.shift();
                  const stack=(new Error().stack||'').split('\\n').slice(1,9).map(line=>line.replace(/https?:\\/\\/[^\\s)]+/g,value=>{
                    const url=new URL(value);url.search='';url.hash='';return url.href;
                  }));
                  p.focusCalls.push({target:p.focusType(this),active:p.focusType(document.activeElement),mode:p.mode(),time:performance.now(),stack});
                }
                return p.focus.apply(this,arguments);
              };
              HTMLElement.prototype.focus=p.focusWrapped;
              p.listener=e=>{
                if(e.type==='focusin'||e.type==='focusout'){
                  if(p.focusEvents.length>=100)p.focusEvents.shift();
                  p.focusEvents.push({type:e.type,target:p.focusType(e.target),active:p.focusType(document.activeElement),mode:p.mode(),time:performance.now()});return;
                }
                if(p.events.length>=150)p.events.shift();
                p.events.push({type:e.type,trusted:e.isTrusted,x:e.clientX,y:e.clientY,
                  target:p.describe(e.target),hit:p.describe(document.elementFromPoint(e.clientX,e.clientY)),time:performance.now()});
              };
              p.types=['pointerdown','pointerup','pointercancel','click','focusin','focusout'];
              for(const type of p.types)document.addEventListener(type,p.listener,true);
              p.wrapped=function(input,options){
                let tracked;
                try{
                  const path=new URL(typeof input==='string'?input:input.url,location.href).pathname;
                  const match=path.match(/^\\/droy\\/companions\\/(kaka|maimai)\\/conversation$/);
                  if(match){
                    const candidate=typeof options?.body==='string'?JSON.parse(options.body)?.action:null;
                    const action=['pin','unpin','read','hide'].includes(candidate)?candidate:null;
                    tracked={companion:match[1],action,status:null,time:performance.now()};
                    if(p.requests.length>=30)p.requests.shift();p.requests.push(tracked);
                  }
                }catch{p.parseFailure=true;}
                const pending=p.fetch.apply(this,arguments);
                if(tracked)pending.then(response=>{tracked.status=response.status;},()=>{tracked.status='network-error';});
                return pending;
              };
              window.fetch=p.wrapped;return true;
            })()
            """);
    }

    private void collectServedScriptFingerprint() throws Exception {
        ui.evaluate("""
            (async()=>{
              const entries=performance.getEntriesByType('resource').filter(e=>e.initiatorType==='script');
              const paths=entries.map(e=>new URL(e.name,location.href).pathname);
              const scripts=entries.filter(e=>{const u=new URL(e.name,location.href);return u.origin===location.origin&&/dsh-client-ui-conversation/.test(u.pathname);}).slice(0,5);
              const verified=[];
              for(const entry of scripts){
                const response=await fetch(entry.name,{credentials:'same-origin'});
                if(!response.ok){verified.push({path:new URL(entry.name).pathname,status:response.status});continue;}
                const bytes=await response.arrayBuffer(),digest=await crypto.subtle.digest('SHA-256',bytes);
                verified.push({path:new URL(entry.name).pathname,status:response.status,bytes:bytes.byteLength,
                  sha256:[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,'0')).join('')});
              }
              window.__navigationServedScript={scriptPaths:paths,verified};
            })().catch(()=>{window.__navigationServedScript={diagnosticFailure:true}})
            """);
        DeviceUi.await("Static script fingerprint diagnostic did not finish",10000,
            ()->ui.truth("!!window.__navigationServedScript"));
        result.put("servedConversationScripts",ui.evaluate("window.__navigationServedScript"));
    }

    private void collectTouchDiagnostics(String stage) throws Exception {
        JSONObject probe=(JSONObject)ui.evaluate("""
            (()=>{
              const p=window.__navigationTouchProbe;
              const describe=p?.describe||((e)=>e?{tag:e.tagName}:null);
              const rect=e=>{const r=e.getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height};};
              return {events:p?.events||[],requests:p?.requests||[],focusCalls:p?.focusCalls||[],focusEvents:p?.focusEvents||[],active:p?.focusType(document.activeElement)||null,mode:p?.mode()||null,parseFailure:p?.parseFailure===true,
                rows:[...document.querySelectorAll('[data-conversation-id]')].map(e=>({id:e.dataset.conversationId,
                  revealed:e.classList.contains('is-open'),rect:rect(e)})),
                actions:[...document.querySelectorAll('[data-conversation-action]')].map(e=>{
                  const r=e.getBoundingClientRect();return {action:e.dataset.conversationAction,
                    row:e.closest('[data-conversation-id]')?.dataset.conversationId||null,disabled:e.disabled,
                    rect:rect(e),hit:describe(document.elementFromPoint(r.left+r.width/2,r.top+r.height/2))};})};
            })()
            """);
        probe.put("stage",stage);diagnostics.put(probe);result.put("touchDiagnostics",diagnostics);
        AtomicReference<JSONObject> nativeFocus=new AtomicReference<>();
        android.webkit.WebView web=ui.web();
        instrumentation.runOnMainSync(()->{
            try {
                android.view.View focus=activity.getCurrentFocus();
                android.view.inputmethod.InputMethodManager manager=context.getSystemService(android.view.inputmethod.InputMethodManager.class);
                nativeFocus.set(new JSONObject().put("currentFocusClass",focus==null?JSONObject.NULL:focus.getClass().getName())
                    .put("inputMethodActiveForWebView",manager!=null&&manager.isActive(web)));
            } catch(org.json.JSONException failure) {throw new AssertionError(failure);}
        });
        probe.put("nativeFocus",nativeFocus.get());
    }

    private void collectProfileDiagnostics() throws Exception {
        JSONArray profiles=request("/droy/companions",null).getJSONArray("companions"),safe=new JSONArray();
        for(int i=0;i<profiles.length();i++) {
            JSONObject row=profiles.getJSONObject(i);
            safe.put(new JSONObject().put("id",row.getString("id")).put("hidden",row.optBoolean("hidden"))
                .put("pinned",row.optBoolean("pinned")).put("unread",row.optInt("unread")));
        }
        result.put("finalServiceProfiles",safe);
    }

    private void removeTouchDiagnostics() throws Exception {
        ui.evaluate("(()=>{const p=window.__navigationTouchProbe;if(!p)return true;for(const type of p.types)document.removeEventListener(type,p.listener,true);if(window.fetch===p.wrapped)window.fetch=p.fetch;if(HTMLElement.prototype.focus===p.focusWrapped)HTMLElement.prototype.focus=p.focus;delete window.__navigationTouchProbe;return true})()");
    }

    private void verifyTheme(String stage,String header) throws Exception {
        String expected=InstrumentationRegistry.getArguments().getString("layoutTheme","light");
        assertTrue("Only explicit light and dark fixture themes are supported",expected.equals("light")||expected.equals("dark"));
        boolean dark=expected.equals("dark");
        DeviceUi.await("The resolved webpage theme did not match the requested fixture theme",10000,
            ()->ui.truth("document.body.hasAttribute('data-ds-dark-theme')==="+dark));
        DeviceUi.await("The resolved webpage theme did not synchronize with the native system bars",10000,
            ()->expected.equals(ProductPermissions.preferences(context).getString("themeColorScheme",""))
                &&ProductPermissions.preferences(context).getString("themeBackgroundColor","").matches("#[0-9A-Fa-f]{6}"));
        ui.awaitPaint();
        JSONObject colors=(JSONObject)ui.evaluate("(()=>{const probe=document.createElement('span');probe.style.backgroundColor='var(--dsw-alias-bg-base)';document.body.appendChild(probe);const base=getComputedStyle(probe).backgroundColor;probe.remove();return {dark:document.body.hasAttribute('data-ds-dark-theme'),base,header:getComputedStyle(document.querySelector("+JSONObject.quote(header)+")).backgroundColor,body:getComputedStyle(document.body).backgroundColor}})()");
        String cached=ProductPermissions.preferences(context).getString("themeBackgroundColor","");
        int base=cssColor(colors.getString("base")),headerColor=cssColor(colors.getString("header"));
        AtomicReference<JSONObject> nativeColors=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{
            try {
                WindowInsetsController controller=activity.getWindow().getInsetsController();
                assertNotNull("The system bar controller is unavailable",controller);
                int appearance=controller.getSystemBarsAppearance();
                assertTrue("The decor background must have a resolved theme color",activity.getWindow().getDecorView().getBackground() instanceof ColorDrawable);
                nativeColors.set(new JSONObject().put("lightStatusBars",(appearance&WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)!=0)
                    .put("lightNavigationBars",(appearance&WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)!=0)
                    .put("statusBarColor",activity.getWindow().getStatusBarColor())
                    .put("navigationBarColor",activity.getWindow().getNavigationBarColor())
                    .put("decorColor",((ColorDrawable)activity.getWindow().getDecorView().getBackground()).getColor()));
            } catch(org.json.JSONException error) {throw new AssertionError(error);}
        });
        JSONObject nativeState=nativeColors.get();
        colors.put("nativeCachedBackground",cached).put("native",nativeState);
        result.put(stage+"Theme",colors);
        assertEquals("The actual resolved body theme must match the requested theme",dark,colors.getBoolean("dark"));
        assertEquals("The page header must use the resolved DSH base color",base,headerColor);
        assertEquals("The body behind the system bars must use the resolved DSH base color",base,cssColor(colors.getString("body")));
        assertEquals("The native cached background must match the actual webpage header",headerColor,Color.parseColor(cached));
        assertEquals("The native decor behind transparent system bars must match the webpage",base,nativeState.getInt("decorColor"));
        assertEquals("System status icons must match the resolved theme",!dark,nativeState.getBoolean("lightStatusBars"));
        assertEquals("System navigation icons must match the resolved theme",!dark,nativeState.getBoolean("lightNavigationBars"));
        assertEquals("The status bar must remain transparent",Color.TRANSPARENT,nativeState.getInt("statusBarColor"));
        assertEquals("The navigation bar must remain transparent",Color.TRANSPARENT,nativeState.getInt("navigationBarColor"));
        capture(stage+"-theme-system-bars");
    }

    private static int cssColor(String value) {
        Matcher match=Pattern.compile("^rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)(?:,\\s*([\\d.]+))?\\)$").matcher(value);
        assertTrue("Expected an opaque resolved CSS color: "+value,match.matches());
        if(match.group(4)!=null)assertEquals("System bar surfaces must be opaque",1d,Double.parseDouble(match.group(4)),0d);
        return Color.rgb(Integer.parseInt(match.group(1)),Integer.parseInt(match.group(2)),Integer.parseInt(match.group(3)));
    }

    private void collectSettingsVisualDiagnostics(String stage) throws Exception {
        JSONObject state=(JSONObject)ui.evaluate("""
            (()=>{
              const panel=document.querySelector('[data-droy-settings-panel]');
              const labels=new Set(['默认进入','Open Droy in','语言','Language','编辑模式','Edit mode','默认权限模式','Default permission mode','外观','Appearance']);
              const describe=e=>{
                if(!e)return null;const s=getComputedStyle(e),r=e.getBoundingClientRect();
                return {tag:e.tagName,className:typeof e.className==='string'?e.className:'',visibility:s.visibility,display:s.display,
                  opacity:s.opacity,zIndex:s.zIndex,color:s.color,background:s.backgroundColor,inert:e.inert===true,
                  transform:s.transform,animationName:s.animationName,animationFillMode:s.animationFillMode,
                  overflowX:s.overflowX,overflowY:s.overflowY,isolation:s.isolation,contain:s.contain,
                  inertAncestor:!!e.closest('[inert]'),ariaHidden:e.getAttribute('aria-hidden'),
                  rect:{left:r.left,top:r.top,right:r.right,bottom:r.bottom,width:r.width,height:r.height}};
              };
              const ancestors=e=>{const rows=[];for(let n=e;n&&rows.length<12;n=n.parentElement)rows.push(describe(n));return rows;};
              const hit=(x,y)=>{const e=document.elementFromPoint(x,y);return {x,y,node:describe(e),
                owner:e?.closest('[data-droy-settings-panel]')?'settings':e?.closest('[data-conversation-session]')?'work':e?.closest('[data-droy-sidebar]')?'sidebar':'other',
                staticLabel:labels.has(e?.textContent.trim())?e.textContent.trim():null};};
              const rows=[...panel.querySelectorAll('*')].filter(e=>labels.has(e.textContent.trim())
                &&![...e.children].some(c=>c.textContent.trim()===e.textContent.trim())).map(e=>{
                  const r=e.getBoundingClientRect();return {label:e.textContent.trim(),ancestors:ancestors(e),
                    leftHit:hit(Math.max(8,Math.min(96,r.left+r.width/2)),Math.max(1,Math.min(innerHeight-1,r.top+r.height/2)))};
                });
              const options=panel.querySelector('[data-droy-settings-options]')?.getBoundingClientRect();
              return {panel:ancestors(panel),rows,workContents:[...document.querySelectorAll('[data-conversation-content]')].map(describe),leftHits:options?[hit(48,Math.min(innerHeight-24,options.top+60)),hit(48,Math.min(innerHeight-24,options.top+120))]:[]};
            })()
            """);
        result.put(stage,state);
    }

    private void diagnoseSettingsWorkAnimation() throws Exception {
        ui.evaluate("(()=>{window.__navigationWorkStyles=[...document.querySelectorAll('[data-conversation-content]')].map(element=>({element,style:element.getAttribute('style')}));for(const item of window.__navigationWorkStyles){item.element.style.animation='none';item.element.style.transform='none'}return true})()");
        try {
            ui.awaitPaint();collectSettingsVisualDiagnostics("settingsWithWorkAnimationDisabled");
            capture("settings-after-work-animation-disabled");
        } finally {
            ui.evaluate("(()=>{for(const item of window.__navigationWorkStyles||[]){if(item.style===null)item.element.removeAttribute('style');else item.element.setAttribute('style',item.style)}delete window.__navigationWorkStyles;return true})()");
            result.put("settingsAnimationAB",new JSONObject().put("diagnosticOnly",true).put("originalInlineStylesRestored",true));
            ui.awaitPaint();collectSettingsVisualDiagnostics("settingsAfterWorkAnimationRestored");
            capture("settings-after-work-animation-restored");
        }
    }

    private void pauseForSettingsCdp() throws Exception {
        File signal=new File(context.getFilesDir(),"navigation-rendering-cdp-complete");
        Files.deleteIfExists(signal.toPath());
        Files.write(new File(evidence,"settings-cdp-ready").toPath(),new byte[0]);
        try {
            DeviceUi.await("The external loopback WebView screenshot diagnostic did not finish",40000,signal::isFile);
        } finally {Files.deleteIfExists(signal.toPath());}
        result.put("settingsCdpHandshakeCompleted",true);
        captureRaw("settings-after-cdp-before-native-invalidate");
        android.webkit.WebView web=ui.web();CountDownLatch committed=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{
            web.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            web.invalidate();
        });
        result.put("settingsNativeInvalidateCommitted",committed.await(5,TimeUnit.SECONDS));
        collectSettingsVisualDiagnostics("settingsAfterNativeInvalidate");
        captureRaw("settings-after-native-invalidate");
    }

    private void diagnoseSettingsRendering() throws Exception {
        String[][] cases={
            {"root-overflow-hidden","[document.documentElement,document.body,document.getElementById('root')].filter(Boolean)","element.style.overflow='hidden'"},
            {"panel-paint-boundary","[document.querySelector('[data-droy-settings-panel]')].filter(Boolean)","element.style.isolation='isolate';element.style.transform='translateZ(0)';element.style.contain='paint'"},
            {"work-hidden","[...document.querySelectorAll('[data-conversation-content]')]","element.style.visibility='hidden'"}
        };
        JSONArray restored=new JSONArray();result.put("settingsRenderingAB",new JSONObject().put("diagnosticOnly",true).put("restores",restored));
        java.util.List<String> selected=java.util.Arrays.asList(InstrumentationRegistry.getArguments().getString("navigationRenderingCases","root-overflow-hidden,panel-paint-boundary,work-hidden").split(","));
        for(String[] probe:cases) {
            if(!selected.contains(probe[0]))continue;
            ui.evaluate("(()=>{window.__navigationRenderingStyles="+probe[1]+".map(element=>({element,style:element.getAttribute('style'),properties:[...element.style].map(name=>[name,element.style.getPropertyValue(name),element.style.getPropertyPriority(name)])}));for(const {element} of window.__navigationRenderingStyles){"+probe[2]+"}return true})()");
            try {
                ui.awaitPaint();collectSettingsVisualDiagnostics("settingsRendering_"+probe[0]);
                capture("settings-rendering-"+probe[0]);
            } finally {
                JSONObject exact=(JSONObject)ui.evaluate("(()=>{const entries=window.__navigationRenderingStyles||[];for(const item of entries){if(item.style===null)item.element.removeAttribute('style');else item.element.setAttribute('style',item.style)}const nodes=entries.map(item=>{const current=item.element.style,propertyNames=[...current],originalNames=item.properties.map(row=>row[0]),changed=item.properties.filter(([name,value,priority])=>current.getPropertyValue(name)!==value||current.getPropertyPriority(name)!==priority).map(row=>row[0]),extra=propertyNames.filter(name=>!originalNames.includes(name));if(item.style===null&&propertyNames.length===0)item.element.removeAttribute('style');return {tag:item.element.tagName,attributeByteEqual:item.element.getAttribute('style')===item.style,attributePresenceEqual:(item.element.getAttribute('style')===null)===(item.style===null),declarationsEqual:changed.length===0&&extra.length===0&&propertyNames.length===item.properties.length,changedProperties:changed,extraProperties:extra}});delete window.__navigationRenderingStyles;return {restored:nodes.every(node=>node.declarationsEqual),nodes}})()");
                restored.put(new JSONObject().put("case",probe[0]).put("originalInlineStylesRestored",exact.getBoolean("restored")).put("verification",exact));
                assertTrue("The diagnostic did not exactly restore inline declarations and priorities: "+probe[0]+" "+exact,exact.getBoolean("restored"));
                ui.awaitPaint();collectSettingsVisualDiagnostics("settingsRenderingRestored_"+probe[0]);
                capture("settings-rendering-restored-"+probe[0]);
            }
        }
    }

    private JSONObject viewportGeometry() throws Exception {
        JSONObject state=(JSONObject)ui.evaluate("""
            (()=>{const style=getComputedStyle(document.documentElement),rect=selector=>{const r=document.querySelector(selector)?.getBoundingClientRect();return r?{top:r.top,bottom:r.bottom}:null};
              const nodes=[document.documentElement,document.body,document.getElementById('root'),document.querySelector('[data-droy-frame]'),document.querySelector('[data-droy-center]'),document.querySelector('.wSkVaW_root'),document.querySelector('[data-conversation-content]')].filter(Boolean);
              return {documentTop:document.scrollingElement.scrollTop,windowTop:scrollY,visualViewportTop:visualViewport?.offsetTop||0,visualViewportHeight:visualViewport?.height||0,height:innerHeight,ime:parseFloat(style.getPropertyValue('--droy-ime-bottom'))||0,composer:rect('[data-composer-seat],.droy-im-composer-wrap'),nodes:nodes.map(element=>{const css=getComputedStyle(element);return {tag:element.tagName,id:element.id,className:element.className,scrollTop:element.scrollTop,overflowX:css.overflowX,overflowY:css.overflowY}})}})()
            """);
        int[] nativeScroll={0};android.webkit.WebView web=ui.web();
        instrumentation.runOnMainSync(()->nativeScroll[0]=web.getScrollY());
        return state.put("nativeWebViewScrollY",nativeScroll[0]).put("nativeImeVisible",ui.nativeNavigationState().optBoolean("liveIme"));
    }

    private void diagnoseViewportOverflow(String screen) throws Exception {
        if(!"true".equals(InstrumentationRegistry.getArguments().getString("navigationViewportVisibleAB")))return;
        boolean reopen="true".equals(InstrumentationRegistry.getArguments().getString("navigationViewportReopen"));
        JSONArray cases=new JSONArray();result.put(screen+"ViewportOverflowAB",new JSONObject().put("diagnosticOnly",true).put("cases",cases));
        for(boolean includeRoot:new boolean[]{false,true}) {
            String label=includeRoot?"html-body-root-visible":"html-body-visible";
            JSONObject record=new JSONObject().put("case",label).put("before",viewportGeometry());cases.put(record);
            ui.evaluate("(()=>{const nodes=[document.documentElement,document.body"+(includeRoot?",document.getElementById('root')":"")+"].filter(Boolean);window.__navigationViewportStyles=nodes.map(element=>({element,style:element.getAttribute('style'),properties:[...element.style].map(name=>[name,element.style.getPropertyValue(name),element.style.getPropertyPriority(name)])}));for(const {element} of window.__navigationViewportStyles)element.style.overflow='visible';return true})()");
            try {
                if(reopen) {
                    ui.pressBack();awaitSettingsClosed();
                    String settings;
                    if(screen.startsWith("messages")) {
                        awaitScreen("list");settings="[data-droy-im-screen=list] .droy-im-header>.droy-im-icon";
                    } else {awaitWork();settings="[data-droy-global-settings]";}
                    awaitTouchCenter(settings);ui.tap(settings);
                    DeviceUi.await("The settings portal did not reopen with the viewport diagnostic still applied",5000,
                        ()->ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
                    record.put("settingsClosedAndReopened",true);
                }
                ui.awaitPaint();record.put("during",viewportGeometry());collectSettingsVisualDiagnostics(screen+"_"+label);
                capture(screen+(reopen?"-reopened-":"-")+label);
            } finally {
                JSONObject restored=(JSONObject)ui.evaluate("(()=>{const entries=window.__navigationViewportStyles||[];for(const item of entries){if(item.style===null)item.element.removeAttribute('style');else item.element.setAttribute('style',item.style)}const nodes=entries.map(item=>{const style=item.element.style,names=[...style],before=item.properties.map(row=>row[0]),changed=item.properties.filter(([name,value,priority])=>style.getPropertyValue(name)!==value||style.getPropertyPriority(name)!==priority).map(row=>row[0]),extra=names.filter(name=>!before.includes(name));if(item.style===null&&names.length===0)item.element.removeAttribute('style');return {tag:item.element.tagName,changedProperties:changed,extraProperties:extra,declarationsEqual:changed.length===0&&extra.length===0&&names.length===item.properties.length}});delete window.__navigationViewportStyles;return {restored:nodes.every(node=>node.declarationsEqual),nodes}})()");
                record.put("restore",restored);
                assertTrue("Viewport diagnosis did not restore its inline CSS declarations and priorities",restored.getBoolean("restored"));
                ui.awaitPaint();record.put("after",viewportGeometry());collectSettingsVisualDiagnostics(screen+"_restored_"+label);
                capture(screen+"-restored-"+label);
            }
        }
    }

    private void listOperations() throws Exception {
        awaitScreen("list");
        headerHeight=number("document.querySelector('.droy-im-header').getBoundingClientRect().height");
        titleSize=number("parseFloat(getComputedStyle(document.querySelector('.droy-im-mode-trigger')).fontSize)");
        request("/droy/setup-chat-fixture",new JSONObject().put("operation","seed").put("count",100));
        DeviceUi.await("Synthetic messages did not reach the message list",10000,
            ()->ui.truth("!!document.querySelector('[data-droy-companion=kaka] .droy-im-unread')"));
        request("/droy/companions/maimai/messages",new JSONObject().put("text",SetupFixtureProvider.MAIMAI_GREETING)
            .put("clientId","navigation-"+UUID.randomUUID()).put("attachments",new JSONArray()));
        DeviceUi.await("The local Maimai reply did not finish",30000,()->profile("maimai").optInt("unread")>0
            &&request("/droy/companions/maimai/messages",null).getJSONObject("status").optString("phase").equals("idle"));
        DeviceUi.await("Latest actual messages did not sort Maimai ahead of older Kaka history",10000,
            ()->ui.truth("document.querySelector('[data-droy-companion]')?.dataset.droyCompanion==='maimai'"));
        assertTrue("The switch entry must show the total unread count",ui.truth("Number(document.querySelector('.droy-im-mode-unread')?.textContent.replace('+',''))>0"));
        capture("list-recent-unread");

        ui.longPress("button[data-droy-companion=kaka]",650);
        DeviceUi.await("Long press did not expose pin",5000,
            ()->ui.truth("!!document.querySelector('[data-droy-conversation-menu] [data-conversation-action=pin]')"));
        capture("long-press-pin");
        ui.pressBack();
        DeviceUi.await("Android Back did not dismiss the list context menu",5000,
            ()->ui.truth("!document.querySelector('[data-droy-conversation-menu]')&&!!document.querySelector('[data-droy-im-screen=list]')"));
        ui.longPress("button[data-droy-companion=kaka]",650);
        ui.tap("[data-droy-conversation-menu] [data-conversation-action=pin]");
        DeviceUi.await("Pin did not persist or move the conversation first",5000,()->profile("kaka").optBoolean("pinned")
            &&ui.truth("document.querySelector('[data-droy-companion]')?.dataset.droyCompanion==='kaka'"));
        ui.longPress("button[data-droy-companion=kaka]",650);
        ui.tap("[data-droy-conversation-menu] [data-conversation-action=unpin]");
        DeviceUi.await("Unpin did not restore recent-time sorting",5000,()->!profile("kaka").optBoolean("pinned")
            &&ui.truth("document.querySelector('[data-droy-companion]')?.dataset.droyCompanion==='maimai'"));

        swipeRow("kaka");capture("swipe-actions");
        ui.tap("[data-conversation-id=kaka] [data-conversation-action=read]");
        DeviceUi.await("Swipe read did not clear the existing unread messages",5000,()->profile("kaka").optInt("unread")==0
            &&ui.truth("!document.querySelector('[data-droy-companion=kaka] .droy-im-unread')"));
        request("/droy/setup-chat-fixture",new JSONObject().put("operation","append").put("count",100));
        DeviceUi.await("A fresh delivery did not become unread",5000,()->profile("kaka").optInt("unread")==50);
        Set<String> retained=messageIds("kaka");assertEquals(200,retained.size());
        swipeRow("kaka");
        Rect hideTarget=ui.webBounds("[data-conversation-id=kaka] [data-conversation-action=hide]");
        result.put("hideNativeTargetBefore",new JSONObject().put("left",hideTarget.left).put("top",hideTarget.top)
            .put("right",hideTarget.right).put("bottom",hideTarget.bottom));
        collectTouchDiagnostics("before-hide-touch");
        ui.tap("[data-conversation-id=kaka] [data-conversation-action=hide]");
        collectTouchDiagnostics("after-hide-touch");
        DeviceUi.await("Swipe delete did not hide the conversation and clear its old unread count",5000,
            ()->profile("kaka").optBoolean("hidden")&&profile("kaka").optInt("unread")==0
                &&ui.truth("!document.querySelector('[data-droy-companion=kaka]')"));
        assertEquals("Removing a conversation must preserve its messages",retained,messageIds("kaka"));
        capture("deleted-list-records-retained");
        reload();
        assertTrue("Reload must preserve hidden state",ui.truth("!document.querySelector('[data-droy-companion=kaka]')"));
        request("/droy/setup-chat-fixture",new JSONObject().put("operation","append").put("count",100));
        DeviceUi.await("A new delivery did not restore the hidden conversation",10000,
            ()->!profile("kaka").optBoolean("hidden")&&profile("kaka").optInt("unread")==50
                &&ui.truth("!!document.querySelector('[data-droy-companion=kaka] .droy-im-unread')"));
        assertTrue(messageIds("kaka").containsAll(retained));
        result.put("messageList",new JSONObject().put("recentOrder",true).put("pinUnpinTrustedTouch",true)
            .put("swipeReadDeleteTrustedTouch",true).put("hiddenSurvivesReload",true).put("historyPreserved",true)
            .put("oldUnreadCleared",true).put("newDeliveryRestoresConversation",true));
    }

    private void partnerProfileAndHeader() throws Exception {
        ui.tap("[data-droy-companion=kaka]");awaitScreen("chat");
        DeviceUi.await("Conversation messages did not load",10000,()->ui.truth("!!document.querySelector('.droy-im-message')"));
        assertHeader(".droy-im-header",".droy-im-chat-title strong");
        assertTrue("The companion name must be centered on the screen",ui.truth("(()=>{const r=document.querySelector('.droy-im-chat-title strong').getBoundingClientRect();return Math.abs((r.left+r.right)/2-innerWidth/2)<=2})()"));
        ui.tap("[data-droy-im-action=companion-profile]");awaitScreen("profile");
        assertHeader(".droy-im-header",".droy-im-page-title");capture("avatar-opens-profile");
        ui.pressBack();awaitScreen("chat");
        ui.showKeyboard(".droy-im-input");ui.replaceFocusedText("Navigation fixture unsent draft",0);
        assertPinnedComposer(".droy-im-composer-wrap",true);
        Rect messages=ui.webBounds(".droy-im-messages");
        for(int i=0;i<3;i++)ui.swipe(messages.centerX(),messages.bottom-20,messages.centerX(),messages.top+20,250);
        assertPinnedComposer(".droy-im-composer-wrap",true);capture("partner-keyboard-scroll");
        ui.hideKeyboard();ui.pressBack();awaitScreen("list");
        ui.tap("[data-droy-companion=kaka]");awaitScreen("chat");
        assertEquals("Navigation must retain the companion draft","Navigation fixture unsent draft",ui.evaluate("document.querySelector('.droy-im-input').value"));
        ui.hideKeyboard();ui.pressBack();awaitScreen("list");
        result.put("partnerProfile",true).put("centeredName",true).put("partnerKeyboardAnchored",true);
    }

    private void messagesSettings() throws Exception {
        awaitScreen("list");
        String settings="[data-droy-im-screen=list] .droy-im-header>.droy-im-icon";
        awaitTouchCenter(settings);ui.tap(settings);
        DeviceUi.await("The message list settings button did not open settings",5000,
            ()->ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.awaitPaint();collectSettingsVisualDiagnostics("messagesSettings");capture("global-messages-settings");
        diagnoseViewportOverflow("messages-settings");
        ui.pressBack();awaitSettingsClosed();awaitScreen("list");
        result.put("messagesSettingsReturnsToList",true);
    }

    private void workNavigationAndKeyboard() throws Exception {
        ui.tap(".droy-im-mode-trigger");awaitWork();
        verifyTheme("work", ".droy-work-header");
        assertFalse("Direct switching must not render a dropdown",ui.truth("!!document.querySelector('.droy-im-mode-menu')"));
        assertHeader(".droy-work-header",".droy-work-header .droy-im-mode-trigger");
        assertTrue("Work header action order must be conversations, resources, settings",ui.truth("(()=>{const h=document.querySelector('.droy-work-header-actions');const b=[...h.querySelectorAll('button')];return b.length===3&&b[0].matches('[data-droy-menu]')&&b[1].matches('[data-droy-resource-toggle]')&&b[2].matches('[data-droy-global-settings]')})()"));
        ui.tap("[data-droy-menu]");
        awaitConversationsPage();
        assertTrue("Conversations should be a full-width page",ui.truth("(()=>{const r=document.querySelector('[data-droy-sidebar]').getBoundingClientRect();return Math.abs(r.width-innerWidth)<=2})()"));
        assertTrue("The former sidebar must not retain a global settings entry",ui.truth("![...document.querySelectorAll('[data-droy-sidebar] button')].some(b=>/^(设置|Settings)$/.test((b.getAttribute('aria-label')||b.textContent).trim()))"));
        assertTrue("Available space groups must follow user, default, ungrouped, companion order",ui.truth("(()=>{const rank={user:0,default:1,ungrouped:2,companion:3};const kinds=[...document.querySelectorAll('[data-droy-sidebar] [data-droy-workspace-kind]')].map(e=>e.dataset.droyWorkspaceKind);return kinds.length>0&&kinds.every((k,i)=>Object.hasOwn(rank,k)&&(i===0||rank[kinds[i-1]]<=rank[k]))})()"));
        result.put("visibleWorkspaceKinds",ui.evaluate("[...document.querySelectorAll('[data-droy-sidebar] [data-droy-workspace-kind]')].map(e=>e.dataset.droyWorkspaceKind)"));
        capture("conversations-full-page");
        ui.tap("[data-droy-drawer-close]");awaitWork();
        ui.tap("[data-droy-menu]");
        awaitConversationsPage();
        assertTrue(ui.truth("(()=>{const b=[...document.querySelectorAll('[data-droy-sidebar] button')].find(b=>[b.textContent.trim(),b.getAttribute('aria-label')||''].some(t=>/^(新会话|New Session)$/i.test(t)));if(!b)return false;b.dataset.navigationNewSession='true';return true})()"));
        awaitTouchCenter("[data-navigation-new-session]");
        ui.tap("[data-navigation-new-session]");awaitWork();
        String editor="[data-composer-input][contenteditable=true]";
        DeviceUi.await("New work has no usable editor",10000,()->ui.truth("!!document.querySelector("+JSONObject.quote(editor)+")"));
        for(int index=0;index<6;index++) {
            ui.showKeyboard(editor);
            int length=((String)ui.evaluate("document.querySelector("+JSONObject.quote(editor)+").textContent")).length();
            String text=SetupFixtureProvider.NEUTRAL_WORK_REQUEST;
            ui.replaceFocusedText(text,length);
            ui.tap("button[aria-label='发送消息'],button[aria-label='Send message']");
            final int replies=index+1;
            DeviceUi.await("The local work history did not receive its bounded reply",30000,
                ()->ui.truth("(document.querySelector('[data-conversation-session]')?.textContent.split("+JSONObject.quote(SetupFixtureProvider.NEUTRAL_WORK_REPLY)+").length||1)-1>="+replies));
        }
        ui.showKeyboard(editor);ui.replaceFocusedText("Unsent work navigation draft",0);
        assertPinnedComposer("[data-composer-seat]",true);
        assertTrue("The work check must use a genuinely scrollable long conversation",ui.truth("(()=>{const s=document.querySelector('[data-conversation-session]');return [s,...s.querySelectorAll('*')].some(e=>e.clientHeight>50&&e.scrollHeight>e.clientHeight+300&&['auto','scroll'].includes(getComputedStyle(e).overflowY))})()"));
        Rect work=ui.webBounds("[data-conversation-session]");
        for(int i=0;i<4;i++)ui.swipe(work.centerX(),Math.min(work.bottom-30,work.top+220),work.centerX(),work.top+35,260);
        assertPinnedComposer("[data-composer-seat]",true);capture("work-keyboard-long-history");
        collectTouchDiagnostics("before-work-to-messages");
        ui.tap(".droy-work-header .droy-im-mode-trigger");awaitScreen("list");
        DeviceUi.await("Mode switching must dismiss the keyboard",5000,()->!ui.nativeNavigationState().optBoolean("liveIme"));
        collectTouchDiagnostics("after-work-to-messages");
        ui.tap(".droy-im-mode-trigger");awaitWork();
        collectTouchDiagnostics("after-messages-to-work");
        assertTrue("The work draft must survive both mode switches",ui.truth("document.querySelector('[data-composer-input]')?.textContent.includes('Unsent work navigation draft')"));
        DeviceUi.await("Returning to Work must not reopen the keyboard",5000,()->!ui.nativeNavigationState().optBoolean("liveIme"));
        ui.tap("[data-droy-global-settings]");
        DeviceUi.await("The permanent work settings button did not open settings",5000,
            ()->ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        ui.awaitPaint();collectSettingsVisualDiagnostics("settingsBeforeTab");
        capture("global-work-settings");
        diagnoseViewportOverflow("work-settings");
        boolean renderingAB="true".equals(InstrumentationRegistry.getArguments().getString("navigationRenderingAB"));
        if(renderingAB)pauseForSettingsCdp();
        awaitTouchCenter("[data-droy-settings-section=models]");
        ui.tap("[data-droy-settings-section=models]");
        DeviceUi.await("The real model settings tab did not become active",5000,
            ()->ui.truth("document.querySelector('[data-droy-settings-section=models]')?.getAttribute('aria-current')==='true'"));
        awaitTouchCenter("[data-droy-settings-section=general]");
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The real general settings tab did not become active again",5000,
            ()->ui.truth("document.querySelector('[data-droy-settings-section=general]')?.getAttribute('aria-current')==='true'"));
        ui.awaitPaint();collectSettingsVisualDiagnostics("settingsAfterTab");capture("global-work-settings-after-tab");
        if("true".equals(InstrumentationRegistry.getArguments().getString("navigationAnimationAB")))diagnoseSettingsWorkAnimation();
        if(renderingAB)diagnoseSettingsRendering();
        ui.pressBack();
        awaitSettingsClosed();
        awaitWork();
        awaitTouchCenter("[data-droy-resource-toggle]");
        ui.tap("[data-droy-resource-toggle]");
        String resource="[data-droy-mobile-panel][data-sidebar-right-open]";
        DeviceUi.await("The moved resource button did not open its full-screen panel",5000,
            ()->ui.truth("(()=>{const e=document.querySelector("+JSONObject.quote(resource)+"),r=e?.getBoundingClientRect();return !!r&&Math.abs(r.left)<=1&&Math.abs(r.top)<=1&&Math.abs(r.right-innerWidth)<=1&&Math.abs(r.bottom-innerHeight)<=1&&getComputedStyle(e).visibility!=='hidden'})()"));
        String resourceBack=resource+" [data-sidebar-right-toggle]";
        awaitTouchCenter(resourceBack);
        assertTrue("The resource return control must stay below the system status bar",ui.truth("(()=>{const r=document.querySelector("+JSONObject.quote(resourceBack)+").getBoundingClientRect(),safe=parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--droy-safe-top'))||0;return r.top>=safe-1})()"));
        result.put("resourcePanel",ui.evaluate("(()=>{const p=document.querySelector("+JSONObject.quote(resource)+").getBoundingClientRect(),b=document.querySelector("+JSONObject.quote(resourceBack)+").getBoundingClientRect();return {left:p.left,top:p.top,right:p.right,bottom:p.bottom,returnControl:{left:b.left,top:b.top,right:b.right,bottom:b.bottom}}})()"));
        capture("moved-resource-panel");
        ui.pressBack();
        DeviceUi.await("Android Back did not close the resource panel",5000,
            ()->ui.truth("!document.querySelector('[data-droy-mobile-panel][data-sidebar-right-open]')"));
        awaitWork();
        assertTrue("Returning from resources must retain the original work draft",ui.truth("document.querySelector('[data-composer-input]')?.textContent.includes('Unsent work navigation draft')"));
        String retainedDraft=(String)ui.evaluate("document.querySelector('[data-composer-input]').textContent");
        awaitTouchCenter(editor);
        ui.showKeyboard(editor);
        assertTrue("An explicit editor tap must restore the editable input focus",ui.truth("document.activeElement?.matches('[data-composer-input]')===true&&document.activeElement.isContentEditable"));
        String suffix=" Controlled edit after navigation.";
        android.webkit.WebView workWeb=ui.web();
        instrumentation.runOnMainSync(()->{
            android.view.inputmethod.InputConnection connection=workWeb.onCreateInputConnection(new android.view.inputmethod.EditorInfo());
            assertNotNull("The explicitly tapped work editor has no input connection",connection);
            assertTrue("The restored editor rejected its retained draft selection",connection.setSelection(retainedDraft.length(),retainedDraft.length()));
            assertTrue("The restored editor rejected appended native input",connection.commitText(suffix,1));
            connection.finishComposingText();
        });
        DeviceUi.await("The restored work editor did not append input while preserving its draft",5000,
            ()->ui.truth("document.querySelector('[data-composer-input]')?.textContent==="+JSONObject.quote(retainedDraft+suffix)));
        assertTrue("Explicit input must keep the work keyboard visible",ui.nativeNavigationState().optBoolean("liveIme"));
        capture("work-input-after-navigation");
        result.put("workNavigation",new JSONObject().put("directModeSwitch",true).put("uniformHeader",true)
            .put("actionOrder",true).put("fullPageConversations",true).put("permanentSettings",true)
            .put("longWorkKeyboardAnchored",true).put("modeSwitchHidesKeyboard",true).put("workDraftRetained",true)
            .put("movedResourceButton",true).put("resourceBackPreservesDraft",true).put("explicitInputAfterNavigation",true));
    }

    private void assertPinnedComposer(String selector,boolean keyboard) throws Exception {
        ui.awaitPaint();
        JSONObject geometry=(JSONObject)ui.evaluate("(()=>{const r=document.querySelector("+JSONObject.quote(selector)+").getBoundingClientRect(),h=document.querySelector('.droy-work-header,.droy-im-header').getBoundingClientRect(),css=getComputedStyle(document.documentElement);return {documentTop:document.scrollingElement.scrollTop,windowTop:scrollY,visualViewportTop:visualViewport?.offsetTop||0,frameTop:document.querySelector('[data-droy-frame]').scrollTop,centerTop:document.querySelector('[data-droy-center]').scrollTop,composerBottom:r.bottom,composerTop:r.top,headerTop:h.top,safeTop:parseFloat(css.getPropertyValue('--droy-safe-top'))||0,ime:parseFloat(css.getPropertyValue('--droy-ime-bottom'))||0,height:innerHeight}})()");
        int[] nativeScroll={0};android.webkit.WebView web=ui.web();
        instrumentation.runOnMainSync(()->nativeScroll[0]=web.getScrollY());
        geometry.put("nativeWebViewScrollY",nativeScroll[0]);
        result.put(selector.contains("seat")?"workKeyboardGeometry":"partnerKeyboardGeometry",geometry);
        assertEquals("The outer document scrolled above the system status bar",0d,geometry.getDouble("documentTop"),1d);
        assertEquals("The whole viewport moved",0d,geometry.getDouble("windowTop"),1d);
        assertEquals("The visual viewport moved above the app shell",0d,geometry.getDouble("visualViewportTop"),1d);
        assertEquals("The native WebView scrolled the whole app",0d,geometry.getDouble("nativeWebViewScrollY"),1d);
        assertEquals("The outer frame scrolled instead of the transcript",0d,geometry.getDouble("frameTop"),1d);
        assertEquals("The center shell scrolled instead of the transcript",0d,geometry.getDouble("centerTop"),1d);
        assertTrue("Header overlapped the status bar",geometry.getDouble("headerTop")>=geometry.getDouble("safeTop")-2);
        if(keyboard)assertTrue("The keyboard must actually be visible",ui.nativeNavigationState().optBoolean("liveIme"));
        double gap=geometry.getDouble("height")-geometry.getDouble("ime")-geometry.getDouble("composerBottom");
        assertTrue("Unexpected composer-to-keyboard gap: "+geometry,gap>=-3&&gap<=24);
    }

    private void assertHeader(String header,String title) throws Exception {
        ui.awaitPaint();assertEquals("Page headers must have the same height",headerHeight,
            number("document.querySelector("+JSONObject.quote(header)+").getBoundingClientRect().height"),1d);
        assertEquals("Page titles must have the same text size",titleSize,
            number("parseFloat(getComputedStyle(document.querySelector("+JSONObject.quote(title)+")).fontSize)"),.1d);
    }
    private double number(String js) throws Exception {return ((Number)ui.evaluate(js)).doubleValue();}
    private void swipeRow(String id) throws Exception {
        String selector="button[data-droy-companion="+id+"]";Rect row=ui.visibleWebBounds(selector);
        assertEquals("Conversation rows must reserve horizontal gestures for their actions","pan-y",
            ui.evaluate("getComputedStyle(document.querySelector("+JSONObject.quote(selector)+")).touchAction"));
        ui.swipe(row.right-48,row.centerY(),row.left+48,row.centerY(),300);
        DeviceUi.await("Left swipe did not expose conversation operations",5000,
            ()->ui.truth("(()=>{const b=document.querySelector('[data-conversation-id="+id+"] [data-conversation-action=hide]'),r=b?.getBoundingClientRect();return !!r&&r.width>0&&document.elementFromPoint(r.left+r.width/2,r.top+r.height/2)?.closest('[data-conversation-action=hide]')===b})()"));
    }
    private void awaitConversationsPage() throws Exception {
        DeviceUi.await("The conversations page did not finish entering the viewport",5000,
            ()->ui.truth("(()=>{const e=document.querySelector('[data-droy-sidebar]'),r=e?.getBoundingClientRect();return !!document.querySelector('[data-droy-drawer-open=true] .droy-conversations-header')&&!!r&&Math.abs(r.left)<=1&&Math.abs(r.right-innerWidth)<=1})()"));
        awaitTouchCenter("[data-droy-drawer-close]");
        ui.awaitPaint();
    }
    private void awaitSettingsClosed() throws Exception {
        DeviceUi.await("The settings portal did not close",5000,
            ()->ui.truth("(()=>{const e=document.querySelector('[data-droy-settings-panel]');return !e||!e.getClientRects().length||getComputedStyle(e).visibility==='hidden'||getComputedStyle(e).display==='none'})()"));
    }
    private void awaitTouchCenter(String selector) throws Exception {
        DeviceUi.await("The target center is not hit-testable in the viewport: "+selector,5000,
            ()->ui.truth("(()=>{const e=document.querySelector("+JSONObject.quote(selector)+"),r=e?.getBoundingClientRect();if(!r||r.width<=0||r.height<=0||r.left< -1||r.right>innerWidth+1||r.top< -1||r.bottom>innerHeight+1)return false;const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return !!hit&&e.contains(hit)})()"));
    }
    private void awaitScreen(String screen) throws Exception {DeviceUi.await("Expected IM screen "+screen,10000,
        ()->ui.truth("!!document.querySelector('[data-droy-im-screen="+screen+"]')"));ui.awaitPaint();}
    private void awaitWork() throws Exception {DeviceUi.await("Expected the workbench",10000,
        ()->ui.truth("!!document.querySelector('.droy-work-header')&&!document.querySelector('[data-droy-im]')&&!document.querySelector('[data-droy-drawer-open=true]')"));ui.awaitPaint();}
    private void reload() throws Exception {
        collectTouchDiagnostics("before-reload");removeTouchDiagnostics();
        ui.evaluate("window.__navigationFixtureReload=true");android.webkit.WebView web=ui.web();instrumentation.runOnMainSync(web::reload);
        DeviceUi.await("The fixture document did not reload",15000,
            ()->ui.truth("window.__navigationFixtureReload===undefined&&typeof window.DroyMobile?.openCompanion==='function'"));ui.awaitPage();awaitScreen("list");installTouchDiagnostics();
    }
    private JSONObject profile(String id) throws Exception {
        JSONArray companions=request("/droy/companions",null).getJSONArray("companions");
        for(int i=0;i<companions.length();i++)if(companions.getJSONObject(i).getString("id").equals(id))return companions.getJSONObject(i);
        throw new AssertionError("The isolated companion profile is missing: "+id);
    }
    private Set<String> messageIds(String id) throws Exception {
        Set<String> ids=new HashSet<>();String before="";
        for(int page=0;page<20;page++) {
            JSONObject result=request("/droy/companions/"+id+"/messages"+(before.isEmpty()?"":"?before="+before),null);
            JSONArray messages=result.getJSONArray("messages");for(int i=0;i<messages.length();i++)ids.add(messages.getJSONObject(i).getString("id"));
            if(!result.optBoolean("hasMore"))return ids;
            assertTrue("Paginated history cannot be empty before its end",messages.length()>0);before=messages.getJSONObject(0).getString("id");
        }
        throw new AssertionError("Synthetic message history exceeded the bounded fixture");
    }
    private JSONObject request(String path,JSONObject body) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+RuntimeService.webPort+path).openConnection();
        connection.setConnectTimeout(1000);connection.setReadTimeout(30000);connection.setRequestProperty("Cookie","droy_session="+RuntimeService.webToken);
        if(body!=null) {connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json");
            try(var output=connection.getOutputStream()){output.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
        int expected=body!=null&&path.equals("/droy/companions/maimai/messages")?202:200;
        try {assertEquals("The isolated fixture request failed: "+path,expected,connection.getResponseCode());
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] chunk=new byte[8192];int size;while((size=input.read(chunk))>=0){assertTrue(bytes.size()+size<=4*1024*1024);bytes.write(chunk,0,size);}
                return new JSONObject(bytes.toString("UTF-8"));
            }
        }finally{connection.disconnect();}
    }
    private void capture(String name) throws Exception {
        ui.awaitPaint();captureRaw(name);
    }
    private void captureRaw(String name) throws Exception {
        Bitmap image=ui.automation().takeScreenshot();assertNotNull(image);
        try(FileOutputStream output=new FileOutputStream(new File(evidence,name+".png"))){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));}
        finally{image.recycle();}
    }
}
