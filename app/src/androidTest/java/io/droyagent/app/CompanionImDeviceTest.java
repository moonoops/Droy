package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Emulator-only inspection of the real bundled host. No model calls or data reset. */
@RunWith(AndroidJUnit4.class)
public class CompanionImDeviceTest {
    private Instrumentation instrumentation;
    private DeviceUi ui;
    private String evidenceLabel;
    private SharedPreferences installation;
    private Boolean savedCompletion;
    private SharedPreferences product;
    private final Map<String,Object> savedAppearance=new HashMap<>();
    private static final String[] APPEARANCE_KEYS={"languagePreference","themePreference","themeColorScheme","themeBackgroundColor","darkTheme","resetTheme"};

    @After public void restoreInstallationFixture() {
        if(installation==null)return;
        SharedPreferences.Editor editor=installation.edit();
        if(savedCompletion==null)editor.remove("completed");else editor.putBoolean("completed",savedCompletion);
        assertTrue("The original setup completion flag was not restored",editor.commit());
        SharedPreferences.Editor appearance=product.edit();
        for(String key:APPEARANCE_KEYS) {
            Object value=savedAppearance.get(key);
            if(value==null)appearance.remove(key);
            else if(value instanceof String)appearance.putString(key,(String)value);
            else if(value instanceof Boolean)appearance.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)appearance.putInt(key,(Integer)value);
            else if(value instanceof Long)appearance.putLong(key,(Long)value);
            else throw new AssertionError("Unexpected appearance preference type: "+key);
        }
        assertTrue("The original native appearance preferences were not restored",appearance.commit());
    }

    @Test public void bundledHostAndImBecomeReadyWithoutRoot() throws Exception {
        start();
        String savedLanguage=(String)ui.evaluate("document.documentElement.lang.startsWith('zh')?'zh':'en'");
        String savedTheme=(String)ui.evaluate("document.documentElement.getAttribute('data-ds-theme-source')||'system'");
        String language=InstrumentationRegistry.getArguments().getString("imLanguage","en");
        String theme=InstrumentationRegistry.getArguments().getString("imTheme","light");
        assertTrue(language.equals("zh")||language.equals("en"));
        assertTrue(theme.equals("light")||theme.equals("dark"));
        evidenceLabel=language+"-"+theme;
        try {
            selectAppearance(language,theme);
            messages();
            assertTrue(ui.truth("document.querySelectorAll('[data-droy-companion]').length===2"));
            capture("list");
            ui.tap("[data-droy-companion='kaka']");
            awaitScreen("chat");
            assertFalse(ui.truth("!!document.querySelector('[data-droy-im-screen=chat] .droy-im-mode-trigger')"));
            capture("kaka-chat");

            ui.tap(".droy-im-composer button[aria-label='表情'],.droy-im-composer button[aria-label='Stickers']");
            DeviceUi.await("The sticker panel did not open",5000,()->ui.truth("document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='stickers'"));
            assertEquals(3,((Number)ui.evaluate("document.querySelectorAll('.droy-im-sticker-grid button').length")).intValue());
            capture("stickers");
            ui.pressBack();
            DeviceUi.await("Back did not close the sticker panel",5000,()->ui.truth("document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='keyboard'"));
            awaitScreen("chat");settle();

            ui.tap(".droy-im-composer button[aria-label='更多'],.droy-im-composer button[aria-label='More']");
            DeviceUi.await("The attachment panel did not open",5000,()->ui.truth("document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='attachments'"));
            assertEquals(3,((Number)ui.evaluate("document.querySelectorAll('.droy-im-attachment-grid button').length")).intValue());
            capture("attachments");
            ui.pressBack();
            DeviceUi.await("Back did not close the attachment panel",5000,()->ui.truth("document.querySelector('[data-droy-im-input-mode]')?.dataset.droyImInputMode==='keyboard'"));
            settle();
            String draft=(String)ui.evaluate("document.querySelector('.droy-im-input').value");
            ui.showKeyboard(".droy-im-input");
            try {
                ui.replaceFocusedText(language.equals("zh")?"尚未发送的测试草稿":"Unsent interaction test draft",draft.length());
                DeviceUi.await("The actual IME draft did not reach the composer",5000,()->ui.truth("!!document.querySelector('.droy-im-send')"));
                assertComposerFits();
                capture("keyboard");
                ui.replaceFocusedText(draft,((String)ui.evaluate("document.querySelector('.droy-im-input').value")).length());
            } finally {ui.hideKeyboard();}

            ui.tap(".droy-im-chat-title");
            awaitScreen("profile");
            String expectedPersonality=localizedPersonality("kaka");
            DeviceUi.await("The profile kept a stale personality after changing language",10000,()->ui.truth("document.querySelector('.droy-im-personality p')?.textContent.trim()==="+JSONObject.quote(expectedPersonality.trim())));
            capture("kaka-profile");
            ui.pressBack();awaitScreen("chat");
            ui.tap("[data-droy-im-screen=chat] .droy-im-header>.droy-im-icon:first-child");
            awaitScreen("list");
            ui.tap("[data-droy-companion='maimai']");
            awaitScreen("chat");
            capture("maimai-chat");
            ui.pressBack();awaitScreen("list");
            ui.tap(".droy-im-mode-trigger");
            DeviceUi.await("The workbench did not become the mode destination",5000,()->ui.truth("!document.querySelector('[data-droy-im]')&&!!document.querySelector('.droy-work-mode-switch .droy-im-mode-trigger')"));
            capture("workbench");
            messages();
            awaitScreen("list");
        } finally {
            ui.hideKeyboard();
            selectAppearance(savedLanguage,savedTheme);
            messages();
        }
    }

    @Test public void listSettingsReturnsToTheSameImDestination() throws Exception {
        start();evidenceLabel="settings";messages();
        ui.tap("[data-droy-im-screen=list] button[aria-label='设置'],[data-droy-im-screen=list] button[aria-label='Settings']");
        DeviceUi.await("The IM list settings button did not open General settings",5000,()->ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        capture("open");
        if(InstrumentationRegistry.getArguments().getString("inspectGeneralLabels","false").equals("true")) {
            inspectGeneralLabels("first");
            ui.awaitPaint();
            capture("open-next-paint");
            inspectGeneralLabels("next-paint");
        }
        ui.pressBack();
        DeviceUi.await("Back did not close General settings",5000,()->ui.truth("!document.querySelector('[data-droy-settings-panel]')"));
        awaitScreen("list");capture("returned-list");
    }

    private void inspectGeneralLabels(String frame) throws Exception {
        Object result=ui.evaluate("""
            (()=>{
              const panel=document.querySelector('[data-droy-settings-panel]');
              const pattern=/^(Permissions?|Permission mode|Language|Open Droy in|权限模式|权限|语言|默认进入|Appearance|外观)$/i;
              const identity=e=>({tag:e.tagName,className:typeof e.className==='string'?e.className:'',id:e.id});
              const details=(e,includeText=true)=>{
                const r=e.getBoundingClientRect(),s=getComputedStyle(e);
                return {...identity(e),...(includeText?{text:e.textContent.trim()}:{}),rect:{left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height},
                  style:Object.fromEntries(['display','visibility','opacity','color','backgroundColor','fontFamily','fontSize','fontWeight','lineHeight','webkitTextFillColor','position','zIndex','overflow','clipPath','filter','transform','contentVisibility'].map(k=>[k,s[k]]))};
              };
              const labels=[...panel.querySelectorAll('*')].filter(e=>pattern.test(e.textContent.trim())&&![...e.children].some(c=>c.textContent.trim()===e.textContent.trim()));
              return {frame:"""+JSONObject.quote(frame)+"""
                ,viewport:{width:innerWidth,height:innerHeight},labels:labels.map(e=>{
                  const r=e.getBoundingClientRect(),x=Math.min(innerWidth-1,Math.max(0,r.left+r.width/2)),y=Math.min(innerHeight-1,Math.max(0,r.top+r.height/2));
                  const ancestors=[];for(let a=e.parentElement;a&&ancestors.length<4;a=a.parentElement)ancestors.push(details(a,false));
                  return {...details(e),ancestors,centerStack:document.elementsFromPoint(x,y).slice(0,8).map(identity)};
                })};
            })()
            """);
        File directory=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"companion-im-evidence");
        assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,"settings-labels-"+frame+".json"))) {
            output.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        android.os.Bundle status=new android.os.Bundle();
        status.putString("stream","\nDROY_GENERAL_LABELS "+result+"\n");
        instrumentation.sendStatus(2,status);
    }

    @Test public void bundledCompanionStartupProbe() throws Exception {start(true);}

    @Test public void emptyCompanionWorkspaceRefusesManualCreation() throws Exception {
        start();evidenceLabel="readonly";
        ui.tap(".droy-im-mode-trigger");
        DeviceUi.await("The workbench did not open",5000,()->ui.truth("!!document.querySelector('.droy-work-mode-switch')&&!document.querySelector('[data-droy-im]')"));
        settle();
        String workspace=(String)ui.evaluate("document.querySelector('[data-droy-workspace-trigger]').textContent.trim()");
        ui.tap("[data-droy-workspace-trigger]");
        DeviceUi.await("The companion workspace was not offered by the workspace picker",5000,()->ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&['Kaka','咔咔'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-empty-companion-workspace','');return true})()"));
        settle();ui.tap("[data-empty-companion-workspace]");
        DeviceUi.await("A manual session was opened in the companion workspace",5000,()->workspace.equals(ui.evaluate("document.querySelector('[data-droy-workspace-trigger]').textContent.trim()")));
        DeviceUi.await("The refused workspace action did not show a visible explanation",5000,()->ui.truth("(()=>{const e=[...document.querySelectorAll('[role=alert],[role=note],[role=status]')].find(e=>e.getClientRects().length&&!e.closest('[inert]')&&/This workspace is managed by|管理的工作空间/.test(e.textContent));if(!e)return false;const a=document.getAnimations().find(a=>a.animationName?.includes('dsh-toast-fade')&&(a.effect?.target?.contains(e)||e.contains(a.effect?.target)));(a?.effect?.target||e).setAttribute('data-readonly-toast-evidence','');return true})()"));
        // This case captures the expected Toast while its deliberate fade is
        // running; the rest of the page must finish its finite transitions.
        capture("kaka-create-refused","[data-readonly-toast-evidence]");
    }

    private void start() throws Exception {start(false);}

    private void start(boolean probeOnly) throws Exception {
        instrumentation=InstrumentationRegistry.getInstrumentation();
        assertEquals("Use the development application", "io.droyagent.app.dev", instrumentation.getTargetContext().getPackageName());
        assertTrue("Use an emulator for this non-destructive fixture", android.os.Build.FINGERPRINT.contains("generic")
            || android.os.Build.MODEL.contains("sdk") || android.os.Build.PRODUCT.contains("sdk"));
        // This fixture verifies IM; installation and its model test are separate
        // flows. Preserve the original flag rather than invoking a paid request.
        installation=instrumentation.getTargetContext().getSharedPreferences("installation",Context.MODE_PRIVATE);
        savedCompletion=installation.contains("completed")?installation.getBoolean("completed",false):null;
        product=instrumentation.getTargetContext().getSharedPreferences("product",Context.MODE_PRIVATE);
        for(String key:APPEARANCE_KEYS)if(product.contains(key))savedAppearance.put(key,product.getAll().get(key));
        assertTrue(installation.edit().putBoolean("completed",true).commit());
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline=SystemClock.elapsedRealtime()+90000;
        String lastResponse="";
        boolean startupFailed=false;
        while(SystemClock.elapsedRealtime()<deadline && !RuntimeService.ready&&!startupFailed) {
            if(RuntimeService.webPort>0 && !RuntimeService.webToken.isEmpty()) {
                try {
                    HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+RuntimeService.webPort+"/droy/ready").openConnection();
                    connection.setConnectTimeout(500);connection.setReadTimeout(1000);
                    connection.setRequestProperty("Cookie","droy_session="+RuntimeService.webToken);
                    try {
                        int status=connection.getResponseCode();
                        InputStream input=status>=400?connection.getErrorStream():connection.getInputStream();
                        if(input!=null)try(input;ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                            byte[] chunk=new byte[1024];int count;
                            while((count=input.read(chunk))>=0){if(bytes.size()+count>8192)break;bytes.write(chunk,0,count);}
                            String json=bytes.toString("UTF-8");lastResponse=status+" "+json;
                            JSONObject response=new JSONObject(json),companions=response.optJSONObject("companions");
                            startupFailed=companions!=null&&"failed".equals(companions.optString("phase"));
                        }
                    } finally {connection.disconnect();}
                } catch(Exception ignored) { /* Startup can precede the loopback listener. */ }
            }
            SystemClock.sleep(250);
        }
        if(probeOnly) {
            File directory=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"companion-im-evidence");
            assertTrue(directory.isDirectory()||directory.mkdirs());
            try(FileOutputStream output=new FileOutputStream(new File(directory,"startup-probe.txt"))) {
                output.write(lastResponse.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        assertTrue("Host readiness: "+lastResponse+"\n"+RuntimeService.status+"\n"+RuntimeService.diagnosticsSnapshot(),RuntimeService.ready);
        assertFalse("The non-Root fixture must not have an authorized Root channel",
            MemoryRuntime.get(instrumentation.getTargetContext()).status().getJSONObject("root").getBoolean("authorized"));
        if(probeOnly)return;
        ui=new DeviceUi(instrumentation,activity);
        try {ui.awaitPage();}
        catch(AssertionError failure) {
            Object state=ui.web()==null?"No WebView":ui.evaluate("({urlPath:location.pathname,ready:document.documentElement.dataset.droyUiReady,im:!!document.querySelector('[data-droy-im]'),setup:!!document.querySelector('[data-droy-setup]'),bridge:!!window.DroyMobile,root:!!document.getElementById('root'),inert:!!document.getElementById('root')?.inert,bodyChildren:document.body.children.length,scriptCount:document.scripts.length})");
            throw new AssertionError(failure.getMessage()+"; nativeReady="+ui.nativeUiReady()+"; overlay="+ui.loadingOverlayShown()+"; document="+state+"; "+RuntimeService.diagnosticsSnapshot(),failure);
        }
        DeviceUi.await("The setup fixture still covers the IM page",10000,()->ui.truth("!document.querySelector('[data-droy-setup]')"));
        messages();
    }

    private void messages() throws Exception {
        if(ui.truth("!!document.querySelector('[data-droy-im-screen=list]')"))return;
        if(ui.truth("!!document.querySelector('[data-droy-im]')")) {
            for(int index=0;index<3&&!ui.truth("!!document.querySelector('[data-droy-im-screen=list]')");index++)ui.pressBack();
        } else {
            ui.tap(".droy-work-mode-switch .droy-im-mode-trigger");
        }
        awaitScreen("list");
    }

    private void awaitScreen(String screen) throws Exception {
        DeviceUi.await("IM did not show "+screen,5000,()->ui.truth("!!document.querySelector('[data-droy-im-screen="+screen+"]')"));
        if(screen.equals("chat"))DeviceUi.await("IM messages did not finish loading",10000,()->ui.truth("!!document.querySelector('.droy-im-chat-empty,.droy-im-message')"));
        settle();
        assertTrue("The IM page overflows its viewport",ui.truth("document.documentElement.scrollWidth<=innerWidth+1"));
    }

    private void selectAppearance(String language,String theme) throws Exception {
        ui.evaluate("window.dispatchEvent(new CustomEvent('droy-mobile-open-settings',{detail:{section:'general'}}))");
        DeviceUi.await("General settings did not open",10000,()->ui.truth("!!document.querySelector('[data-droy-theme-choice=light]')"));
        assertTrue(ui.truth("(()=>{const e=[...document.querySelectorAll('[data-droy-settings-panel] button[aria-haspopup=menu]')].find(e=>['中文','English'].includes(e.textContent.trim()));if(!e)return false;e.setAttribute('data-im-language','');return true})()"));
        ui.tap("[data-im-language]");
        String label=language.equals("zh")?"中文":"English";
        DeviceUi.await("Language option did not open",5000,()->ui.truth("(()=>{const e=[...document.querySelectorAll('[role=menuitem],[role=menuitemradio]')].find(e=>e.getClientRects().length&&e.textContent.trim()==="+JSONObject.quote(label)+");if(!e)return false;e.setAttribute('data-im-language-option','');return true})()"));
        ui.tap("[data-im-language-option]");
        DeviceUi.await("The selected language was not applied",10000,()->ui.truth("document.documentElement.lang.startsWith("+JSONObject.quote(language)+")"));
        ui.tap("[data-droy-theme-choice="+theme+"]");
        DeviceUi.await("The selected theme was not applied",10000,()->ui.truth("document.documentElement.getAttribute('data-ds-theme-source')==="+JSONObject.quote(theme)));
        ui.tap("[data-droy-settings-back]");
        DeviceUi.await("Settings did not close",5000,()->ui.truth("!document.querySelector('[data-droy-settings-panel]')"));
    }

    private void assertComposerFits() throws Exception {
        assertTrue("The composer is covered by the keyboard or clipped horizontally",ui.truth("(()=>{const e=document.querySelector('.droy-im-composer'),r=e.getBoundingClientRect(),v=visualViewport;return r.left>=-1&&r.right<=innerWidth+1&&r.bottom<=v.offsetTop+v.height+1&&r.height>=44})()"));
    }

    private String localizedPersonality(String id) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+RuntimeService.webPort+"/droy/companions").openConnection();
        connection.setConnectTimeout(1000);connection.setReadTimeout(3000);
        connection.setRequestProperty("Cookie","droy_session="+RuntimeService.webToken);
        try {
            assertEquals(200,connection.getResponseCode());
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()) {
                byte[] chunk=new byte[8192];int count;
                while((count=input.read(chunk))>=0) {assertTrue("The profile response was unexpectedly large",bytes.size()+count<=1024*1024);bytes.write(chunk,0,count);}
                org.json.JSONArray companions=new JSONObject(bytes.toString("UTF-8")).getJSONArray("companions");
                for(int index=0;index<companions.length();index++)if(companions.getJSONObject(index).getString("id").equals(id))return companions.getJSONObject(index).getString("personality");
                throw new AssertionError("The expected companion profile was missing");
            }
        } finally {connection.disconnect();}
    }

    private void capture(String stage) throws Exception {
        capture(stage,null);
    }

    private void capture(String stage,String movingSelector) throws Exception {
        ui.awaitPaint(movingSelector);
        DeviceUi.await("The visible images did not finish loading: "+stage,10000,()->ui.truth("[...document.querySelectorAll('[data-droy-im] img')].filter(e=>e.getClientRects().length).every(e=>e.complete&&e.naturalWidth>0)"));
        ui.awaitPaint(movingSelector);instrumentation.waitForIdleSync();
        Bitmap image=ui.automation().takeScreenshot();assertNotNull(image);
        File directory=new File(instrumentation.getTargetContext().getExternalFilesDir(null),"companion-im-evidence");
        assertTrue(directory.isDirectory()||directory.mkdirs());
        try(FileOutputStream output=new FileOutputStream(new File(directory,evidenceLabel+"-"+stage+".png"))) {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));
        } finally {image.recycle();}
    }

    private void settle() throws Exception {
        DeviceUi.await("The finite IM transition did not settle",5000,()->ui.truth("!document.getAnimations().some(a=>a.playState==='running'&&a.effect?.getComputedTiming().iterations!==Infinity)"));
        ui.awaitPaint();
    }
}
