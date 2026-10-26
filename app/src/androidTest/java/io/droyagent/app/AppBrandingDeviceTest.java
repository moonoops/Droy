package io.droyagent.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class AppBrandingDeviceTest {
    @Test public void savedBrandingOverridesPackagedDefaultsAndKeepsCompanionIdentitySeparate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String[] keys = {"assistantName", "petId", "petName", "brandSidebarLabel", "brandHeroBadge", "brandWelcomeTemplate", "brandComposerTemplate"};
        Map<String, String> previous = new HashMap<>();
        for (String key : keys) if (preferences.contains(key)) previous.put(key, preferences.getString(key, ""));
        try {
            SharedPreferences.Editor clear = preferences.edit();
            for (String key : keys) clear.remove(key);
            assertTrue(clear.commit());
            JSONObject defaults = AppBranding.state(context, preferences);
            assertEquals(context.getString(R.string.droy_app_name), defaults.getString("name"));
            assertEquals("Droy", defaults.getString("sidebarLabel"));
            assertEquals("", defaults.getString("heroBadge"));
            assertEquals("与{name}开始对话", defaults.getString("welcomeTemplate"));
            assertEquals("请描述你想让{name}执行的任务，或者你的对话", defaults.getString("composerTemplate"));
            assertEquals("default", defaults.getString("logoPetId"));
            assertEquals("/droy/branding/installed-logo", defaults.getString("logoUrl"));
            assertEquals(RuntimeInstaller.release(context).dshVersion, defaults.getString("dshVersion"));

            assertTrue(preferences.edit().putString("assistantName", "随身小墨").putString("petName", "墨墨")
                .putString("brandSidebarLabel", "伙伴").putString("brandHeroBadge", "内测").putString("brandWelcomeTemplate", "今天想和{name}聊什么")
                .putString("brandComposerTemplate", "告诉{name}你的安排").commit());
            JSONObject saved = AppBranding.state(context, preferences);
            assertEquals("随身小墨", saved.getString("name"));
            assertEquals("墨墨", preferences.getString("petName", ""));
            assertEquals("伙伴", saved.getString("sidebarLabel"));
            assertEquals("内测", saved.getString("heroBadge"));
            assertEquals("今天想和{name}聊什么", saved.getString("welcomeTemplate"));
            assertEquals("告诉{name}你的安排", saved.getString("composerTemplate"));
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : keys) {
                if (previous.containsKey(key)) restore.putString(key, previous.get(key)); else restore.remove(key);
            }
            assertTrue("Could not restore the existing brand configuration", restore.commit());
        }
    }
}
