package io.droyagent.app;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeProductPresentationHostTest {
    @Test public void fixedPresentationKeepsInstalledIdentityAndNativeCopy() throws Exception {
        NativeProductPresentationHost.Snapshot value = NativeProductPresentationHost.decode(new JSONObject(), Map.of(), "Droy Dev");
        assertEquals("Droy Dev", value.appName);
        assertNull(value.workbenchTitleOverride);
        assertNull(value.welcomeOverride);
        assertNull(value.composerHintOverride);
        assertEquals("", value.heroBadge);
        assertNull(value.logoLightColor);
        assertNull(value.logoDarkColor);
        assertEquals("app-brand-logo.png", value.logoAsset);
    }
    @Test public void retiredOverridesCannotAlterPresentationOrBlockStartup() throws Exception {
        Map<String, Object> saved = new HashMap<>();
        saved.put("assistantName", "Old Name");
        saved.put("brandSidebarLabel", "Old workbench");
        saved.put("brandHeroBadge", true);
        saved.put("brandWelcomeTemplate", "Old welcome");
        saved.put("brandComposerTemplate", "Old hint");
        saved.put("brandLogoBackgrounds", "invalid-json");
        saved.put("petId", "existing-pet");
        Map<String, Object> original = new HashMap<>(saved);
        NativeProductPresentationHost.Snapshot value = NativeProductPresentationHost.decode(new JSONObject(), saved, "Droy");
        assertEquals("Droy", value.appName);
        assertNull(value.workbenchTitleOverride);
        assertNull(value.welcomeOverride);
        assertNull(value.composerHintOverride);
        assertEquals("", value.heroBadge);
        assertNull(value.logoLightColor);
        assertNull(value.logoDarkColor);
        assertEquals(original, saved);
    }
}
