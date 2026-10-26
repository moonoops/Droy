package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real public HTTPS navigation through the product bridge, browser IPC and validated CONNECT proxy. */
@RunWith(AndroidJUnit4.class)
public final class BrowserWorkflowDeviceTest {
    @Test public void externalTargetOpensTheDefaultSystemBrowser() throws Exception {
        org.junit.Assume.assumeTrue("Dedicated emulator external-browser regression",
                android.os.Build.FINGERPRINT.startsWith("generic") || android.os.Build.MODEL.contains("sdk"));
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        android.app.UiAutomation automation=instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        Context context=instrumentation.getTargetContext();
        android.content.pm.ResolveInfo preferred=context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://droy-browser-probe.invalid/"))
                        .addCategory(Intent.CATEGORY_BROWSABLE),android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("Configure a default general browser on the test emulator",preferred);
        String expected=preferred.activityInfo.packageName;
        assertNotEquals(context.getPackageName(),expected);
        SharedPreferences preferences=context.getSharedPreferences("product",Context.MODE_PRIVATE);
        String[] keys={"allowBrowser","allowBrowserControl"};
        Map<String,Boolean> previous=new HashMap<>();
        SharedPreferences.Editor enable=preferences.edit();
        for(String key:keys){if(preferences.contains(key))previous.put(key,preferences.getBoolean(key,false));enable.putBoolean(key,true);}
        assertTrue(enable.commit());
        ProductBridge bridge=new ProductBridge(context,"fictional-external-browser-workflow");
        bridge.policy.enable();
        try {
            JSONObject result=bridge.executeTool("browser_open",new JSONObject().put("url","https://example.com/")
                    .put("target","external"),"external-browser-workflow","open");
            assertEquals("external",result.getString("target"));
            assertEquals("navigation_dispatched",result.getString("status"));
            assertFalse(result.getBoolean("goalVerified"));
            DeviceUi.await("The external target did not open the configured system browser",10000,()->{
                android.view.accessibility.AccessibilityNodeInfo root=automation.getRootInActiveWindow();
                try{return root!=null&&root.getPackageName()!=null&&expected.contentEquals(root.getPackageName());}
                finally{if(root!=null)root.recycle();}
            });
        } finally {
            bridge.policy.stop();
            try(android.os.ParcelFileDescriptor command=automation.executeShellCommand("input keyevent KEYCODE_BACK")){SystemClock.sleep(300);}
            SharedPreferences.Editor restore=preferences.edit();
            for(String key:keys){if(previous.containsKey(key))restore.putBoolean(key,previous.get(key));else restore.remove(key);}
            assertTrue(restore.commit());
        }
    }

    @Test public void productCanOpenWaitAndReadPublicHttpsPage() throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        SharedPreferences preferences=context.getSharedPreferences("product",Context.MODE_PRIVATE);
        String[] keys={"allowBrowser","allowBrowserControl","browserEncryptedDns"};
        Map<String,Boolean> previous=new HashMap<>();
        SharedPreferences.Editor enable=preferences.edit();
        boolean encryptedDns="true".equals(InstrumentationRegistry.getArguments().getString("droyBrowserEncryptedDns","false"));
        for(String key:keys){if(preferences.contains(key))previous.put(key,preferences.getBoolean(key,false));enable.putBoolean(key,key.equals("browserEncryptedDns")?encryptedDns:true);}
        FullDshUiDeviceTest fixture=new FullDshUiDeviceTest();
        ProductBridge bridge=new ProductBridge(context,"fictional-public-browser-workflow");
        String sessionId=null;
        try {
            assertTrue(enable.commit());
            fixture.setUp();
            DeviceUi ui=fixture.browserFixtureUi();
            fixture.selectBlankBrowserFixture();
            bridge.policy.enable();
            JSONObject opened=bridge.executeTool("browser_open",new JSONObject().put("url","https://example.com/"),"browser-workflow","open");
            assertEquals(opened.toString(),"navigation_dispatched",opened.getString("status"));
            sessionId=opened.getString("sessionId");
            JSONObject page=bridge.executeTool("browser_wait",new JSONObject().put("text","Learn more").put("timeoutMs",20000),"browser-workflow","wait");
            assertTrue(page.toString(),page.getBoolean("conditionVerified"));
            assertTrue(page.getString("url").startsWith("https://example.com"));
            assertEquals("Example Domain",page.getString("title"));
            assertTrue(page.getString("text").contains("Learn more"));
            assertTrue(page.getJSONArray("elements").length()>0);
            DeviceUi.await("The browser tool did not reveal the actual shared DSH surface",15000,() -> BrowserUiProtection.isVisible(context));
            ui.evaluate("window.__publicBrowserFrame=false;requestAnimationFrame(()=>requestAnimationFrame(()=>window.__publicBrowserFrame=true))");
            DeviceUi.await("The shared browser did not reach a painted frame",5000,()->ui.truth("window.__publicBrowserFrame===true"));
            instrumentation.waitForIdleSync();
            SystemClock.sleep(300);
            android.graphics.Bitmap image=instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull("The public browser must allow a visual check",image);
            try {
                java.io.File output=new java.io.File(context.getCacheDir(),"public-browser-workflow.png");
                try(java.io.FileOutputStream stream=new java.io.FileOutputStream(output)) {
                    assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));
                }
            } finally {image.recycle();}
        } finally {
            try {
                if(sessionId!=null)new BrowserSession(context).execute("browser_ui",new JSONObject().put("operation","close").put("tabId",sessionId),()->{});
            } finally {
                bridge.policy.stop();
                try { fixture.tearDown(); }
                finally {
                    SharedPreferences.Editor restore=preferences.edit();
                    for(String key:keys){if(previous.containsKey(key))restore.putBoolean(key,previous.get(key));else restore.remove(key);}
                    assertTrue(restore.commit());
                }
            }
        }
    }
}
