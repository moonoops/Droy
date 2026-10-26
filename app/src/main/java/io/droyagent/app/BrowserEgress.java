package io.droyagent.app;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ServiceWorkerController;
import android.webkit.WebView;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.Closeable;
import java.util.Collections;
import java.util.function.Consumer;

/** Installs the network boundary before a browser page or agent session is made available. */
final class BrowserEgress implements Closeable {
    private final WebView view;
    private final BrowserNetworkProxy proxy;
    private ScriptHandler guard;
    private boolean closed;
    private final boolean ownsProxy;

    BrowserEgress(WebView view) throws Exception { this(view, new BrowserNetworkProxy(host -> BrowserDns.resolve(view.getContext(), host))); }
    BrowserEgress(WebView view, BrowserNetworkProxy proxy) { this(view, proxy, true); }
    BrowserEgress(WebView view, BrowserNetworkProxy proxy, boolean ownsProxy) { this.view = view; this.proxy = proxy; this.ownsProxy = ownsProxy; }

    void prepare(Runnable ready, Consumer<String> rejected) {
        view.getSettings().setBlockNetworkLoads(true);
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
            || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            close(); rejected.accept(io.droyagent.app.NativeLocale.text("当前系统 WebView 不支持安全浏览所需的代理和页面初始化能力；请更新 Android System WebView 后重试。", "The system WebView does not support the proxy and page initialization features required for secure browsing. Update Android System WebView and retry.")); return;
        }
        try {
            // Workers cannot obtain unguarded WebTransport/RTC constructors in a separate JS realm.
            ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setBlockNetworkLoads(true);
            guard = WebViewCompat.addDocumentStartJavaScript(view, DOCUMENT_GUARD, Collections.singleton("*"));
            ProxyConfig configuration = new ProxyConfig.Builder()
                .addProxyRule("http://127.0.0.1:" + proxy.port())
                .removeImplicitRules().build();
            Handler main = new Handler(Looper.getMainLooper());
            // No direct fallback or bypass list, including Chromium's implicit loopback bypass.
            ProxyController.getInstance().setProxyOverride(configuration, main::post, () -> {
                if (closed) return;
                view.getSettings().setBlockNetworkLoads(false);
                ready.run();
            });
        } catch (Exception unavailable) {
            close(); rejected.accept(io.droyagent.app.NativeLocale.text("浏览器网络保护未能启用；本次未开放网页访问。", "Browser network protection could not start. Web access was not enabled."));
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        view.getSettings().setBlockNetworkLoads(true);
        view.stopLoading();
        if (guard != null) { guard.remove(); guard = null; }
        if (ownsProxy) proxy.close();
        // Retain the now-closed proxy override. Clearing it would allow late worker requests to go direct.
    }

    static final String DOCUMENT_GUARD = """
        (()=>{
          const define=Object.defineProperty, descriptor=Object.getOwnPropertyDescriptor, apply=Reflect.apply, stringify=String;
          const test=RegExp.prototype.test;
          const each=(values,action)=>{for(let i=0;i<values.length;i++)action(values[i])};
          const hasFrame=value=>apply(test,/<iframe(?:[\\s/>]|$)/i,[stringify(value)]);
          const weakHas=WeakSet.prototype.has,weakAdd=WeakSet.prototype.add;
          const seen=new WeakSet();
          const unavailable=function(){throw new DOMException('Unavailable in the Droy browser network policy','NotAllowedError')};
          const names=['RTCPeerConnection','webkitRTCPeerConnection','RTCIceGatherer','RTCIceTransport','RTCDtlsTransport','RTCQuicTransport','WebTransport','Worker','SharedWorker'];
          const harden=w=>{
            if(!w||apply(weakHas,seen,[w]))return;
            const d=w.document;
            for(let i=0;i<names.length;i++)define(w,names[i],{value:unavailable,writable:false,configurable:false});
            if(w.navigator.serviceWorker)define(w.navigator.serviceWorker,'register',{value:()=>Promise.reject(new DOMException('Workers are disabled','NotAllowedError')),writable:false,configurable:false});
            const lengthGetter=descriptor(w,'length').get;
            const scan=()=>{
              const count=apply(lengthGetter,w,[]);
              for(let i=0;i<count;i++)try{harden(w[i])}catch(e){/* Cross-origin frames cannot expose their constructors to this page. */}
            };
            const nodeProto=w.Node.prototype,elementProto=w.Element.prototype,documentProto=w.Document.prototype;
            const connected=descriptor(nodeProto,'isConnected').get;
            const nodeType=descriptor(nodeProto,'nodeType').get;
            const tagName=descriptor(elementProto,'tagName').get;
            const elementQuery=elementProto.querySelector;
            const fragmentQuery=w.DocumentFragment.prototype.querySelector;
            const queryAll=elementProto.querySelectorAll,fragmentAll=w.DocumentFragment.prototype.querySelectorAll;
            const listLength=descriptor(w.NodeList.prototype,'length').get;
            const listItem=w.NodeList.prototype.item;
            const collect=node=>{
              let type;try{type=apply(nodeType,node,[])}catch(e){return {frames:false,scripts:false}}
              const query=type===1?elementQuery:type===11?fragmentQuery:null;
              const all=type===1?queryAll:type===11?fragmentAll:null;
              if(!query)return {frames:false,scripts:false};
              const tag=type===1?apply(tagName,node,[]):'';
              const flags={frames:tag==='IFRAME'||!!apply(query,node,['iframe']),scripts:tag==='SCRIPT'||!!apply(query,node,['script'])};
              const inspect=host=>{const check=host.__droyBrowserShadowCheck;if(typeof check==='function'){const nested=check();flags.frames=flags.frames||nested.frames;flags.scripts=flags.scripts||nested.scripts}};
              if(type===1)inspect(node);
              const descendants=apply(all,node,['*']);
              for(let i=0;i<apply(listLength,descendants,[]);i++)inspect(apply(listItem,descendants,[i]));
              return flags;
            };
            const rejectCompoundNodes=args=>{
              let frames=false,scripts=false;
              for(let i=0;i<args.length;i++){
                try{if(apply(connected,args[i],[]))continue}catch(e){continue}
                const flags=collect(args[i]);frames=frames||flags.frames;scripts=scripts||flags.scripts;
              }
              if(frames&&scripts)unavailable();
            };
            const attachShadow=descriptor(elementProto,'attachShadow');
            if(attachShadow&&attachShadow.value)define(elementProto,'attachShadow',{value:function(...args){
              scan();let root=null;
              define(this,'__droyBrowserShadowCheck',{value:()=>root?collect(root):{frames:false,scripts:false},writable:false,configurable:false});
              root=apply(attachShadow.value,this,args);scan();return root;
            },writable:false,configurable:false});
            const method=(proto,name,nodeArguments)=>{
              const original=descriptor(proto,name);if(!original||typeof original.value!=='function')return;
              define(proto,name,{value:function(...args){
                scan();
                if(nodeArguments)rejectCompoundNodes(args);
                const result=apply(original.value,this,args);scan();return result;
              },writable:false,configurable:false});
            };
            const htmlSetter=(proto,name)=>{
              const original=descriptor(proto,name);if(!original||!original.set)return;
              define(proto,name,{get:original.get,set:function(value){
                scan();
                // String parsers can invoke custom-element constructors before returning to a wrapper.
                // Keep ordinary frame insertion supported through createElement/appendChild.
                if(hasFrame(value))unavailable();
                apply(original.set,this,[value]);scan();
              },configurable:false});
            };
            const iframeProto=w.HTMLIFrameElement&&w.HTMLIFrameElement.prototype;
            if(iframeProto){
              const getter=descriptor(iframeProto,'contentWindow');
              if(getter&&getter.get)define(iframeProto,'contentWindow',{get:function(){const child=apply(getter.get,this,[]);try{harden(child)}catch(e){}return child},configurable:false});
              const documentGetter=descriptor(iframeProto,'contentDocument');
              if(documentGetter&&documentGetter.get)define(iframeProto,'contentDocument',{get:function(){const child=apply(documentGetter.get,this,[]);if(child)try{harden(child.defaultView)}catch(e){}return child},configurable:false});
              const srcdoc=descriptor(iframeProto,'srcdoc');
              if(srcdoc&&srcdoc.set)define(iframeProto,'srcdoc',{get:srcdoc.get,set:function(value){scan();apply(srcdoc.set,this,[value]);scan()},configurable:false});
            }
            each(['appendChild','insertBefore','replaceChild'],name=>method(nodeProto,name,true));
            each([elementProto,documentProto,w.DocumentFragment.prototype],proto=>
              each(['append','prepend','replaceChildren'],name=>method(proto,name,true)));
            each([elementProto,w.CharacterData.prototype,w.DocumentType.prototype],proto=>
              each(['before','after','replaceWith'],name=>method(proto,name,true)));
            method(elementProto,'insertAdjacentElement',true);
            method(w.Range.prototype,'insertNode',true);
            // Script-inserting synchronous parsers cannot be made safe after they have executed callbacks.
            each(['write','writeln'],name=>define(documentProto,name,{value:unavailable,writable:false,configurable:false}));
            each(['innerHTML','outerHTML'],name=>htmlSetter(elementProto,name));
            if(w.ShadowRoot)htmlSetter(w.ShadowRoot.prototype,'innerHTML');
            const stringParser=(proto,name)=>{
              const original=descriptor(proto,name);if(!original||!original.value)return;
              define(proto,name,{value:function(...args){scan();for(let i=0;i<args.length;i++)if(hasFrame(args[i]))unavailable();const result=apply(original.value,this,args);scan();return result},writable:false,configurable:false});
            };
            each(['insertAdjacentHTML','setHTMLUnsafe','setHTML'],name=>stringParser(elementProto,name));
            if(w.ShadowRoot)each(['setHTMLUnsafe','setHTML'],name=>stringParser(w.ShadowRoot.prototype,name));
            each(['parseHTMLUnsafe','parseHTML'],name=>stringParser(w.Document,name));
            stringParser(w.DOMParser.prototype,'parseFromString');
            stringParser(w.Range.prototype,'createContextualFragment');
            const capture=()=>scan();
            const listen=w.EventTarget.prototype.addEventListener;
            apply(listen,w,['load',capture,true]);apply(listen,d,['load',capture,true]);
            const nativeOpen=descriptor(documentProto,'open');
            if(nativeOpen&&nativeOpen.value)define(documentProto,'open',{value:function(...args){scan();const result=apply(nativeOpen.value,this,args);apply(listen,w,['load',capture,true]);apply(listen,this,['load',capture,true]);scan();return result},writable:false,configurable:false});
            const observer=new w.MutationObserver(()=>scan());
            observer.observe(d,{childList:true,subtree:true});
            const addPolicy=()=>{
              if(!d.documentElement)return false;
              let head=d.head;if(!head){head=d.createElement('head');d.documentElement.prepend(head)}
              const meta=d.createElement('meta');meta.httpEquiv='Content-Security-Policy';
              meta.content="worker-src 'none'; object-src 'none'";head.prepend(meta);return true;
            };
            if(!addPolicy()){
              const watcher=new w.MutationObserver(()=>{if(addPolicy())watcher.disconnect()});
              watcher.observe(d,{childList:true,subtree:true});
            }
            define(w,'__droyNetworkGuard',{value:true,writable:false,configurable:false});
            apply(weakAdd,seen,[w]);scan();
          };
          harden(window);
        })()
        """;
}
