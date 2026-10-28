package io.droyagent.app;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppBrandingTest {
    @Test public void productLogoAndColorsDoNotSelectSavedPetArtwork() throws Exception {
        String custom = "a00b0000-0000-4000-8000-000000000001";
        JSONObject petColors = new JSONObject().put("light", "#4A4650").put("dark", "#37333D");
        String stored = AppBranding.updateLogoBackgrounds(new JSONObject().put(custom, petColors).toString(), new JSONObject()
            .put("petId", "default").put("light", "#f2eee7").put("dark", "#d8d3cb"));
        JSONObject state = AppBranding.logoState(stored);
        assertEquals("default", state.getString("logoPetId"));
        assertEquals("/droy/branding/installed-logo", state.getString("logoUrl"));
        assertEquals("#F2EEE7", state.getJSONObject("logoBackgrounds").getString("light"));
        assertEquals(petColors.toString(), state.getJSONObject("logoBackgroundsByPet").getJSONObject(custom).toString());
        String reset = AppBranding.updateLogoBackgrounds(stored, new JSONObject().put("petId", "default").put("reset", true));
        assertFalse(new JSONObject(reset).has("default"));
        assertEquals(petColors.toString(), new JSONObject(reset).getJSONObject(custom).toString());
        assertFalse("A saved pet palette must not become the product fallback", AppBranding.logoState(reset).has("logoBackgrounds"));
        assertThrows(IllegalArgumentException.class, () -> AppBranding.updateLogoBackgrounds(stored,
            new JSONObject().put("petId", custom).put("light", "#FFFFFF").put("dark", "#000000")));
    }

    @Test public void logoColorsRejectCssInjectionTransparencyAndCoercedValues() {
        for (Object color : new Object[]{"transparent", "red", "#FFF", "#FFFFFF00", "url(example)", "#fff;display:none", 123, JSONObject.NULL}) {
            assertThrows(IllegalArgumentException.class, () -> AppBranding.updateLogoBackgrounds("{}",
                new JSONObject().put("petId", "default").put("light", "#F2EEE7").put("dark", color)));
        }
        assertThrows(IllegalArgumentException.class, () -> AppBranding.updateLogoBackgrounds("{}",
            new JSONObject().put("petId", "default").put("reset", "true")));
    }

    @Test public void templatesRemainPlainTextAndRetainTheNamePlaceholder() {
        assertEquals("与{name}开始对话", AppBranding.preference("brandWelcomeTemplate", " 与{name}开始对话 "));
        assertEquals("请描述你想让{name}执行的任务，或者你的对话",
            AppBranding.preference("brandComposerTemplate", "请描述你想让{name}执行的任务，或者你的对话"));
        assertEquals("Droy", AppBranding.preference("brandSidebarLabel", "Droy"));
        assertFalse(AppBranding.isPreference("petId"));
        assertThrows(IllegalArgumentException.class, () -> AppBranding.preference("assistantName", "Droy"));
    }

    @Test public void rejectsMarkupControlCharactersAndCoercedValues() {
        for (Object value : new Object[]{"", "  ", "<img src=x>", "hello\nworld", "hello\u0000world", 12, false, JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () -> AppBranding.preference("brandWelcomeTemplate", value));
        assertThrows(IllegalArgumentException.class, () -> AppBranding.preference("brandSidebarLabel", "x".repeat(41)));
        assertThrows(IllegalArgumentException.class, () -> AppBranding.preference("brandComposerTemplate", "x".repeat(241)));
    }

    @Test public void countsUnicodeCodePointsRatherThanUtf16Units() {
        assertEquals("🐈".repeat(40), AppBranding.preference("brandSidebarLabel", "🐈".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> AppBranding.preference("brandSidebarLabel", "🐈".repeat(41)));
    }

    @Test public void optionalHeroBadgeCanBeHiddenWithoutAcceptingMarkupOrWrongTypes() {
        assertTrue(AppBranding.isPreference("brandHeroBadge"));
        assertEquals("", AppBranding.preference("brandHeroBadge", ""));
        assertEquals("内测", AppBranding.preference("brandHeroBadge", " 内测 "));
        for (Object value : new Object[]{"<b>preview</b>", "x".repeat(41), true, JSONObject.NULL})
            assertThrows(IllegalArgumentException.class, () -> AppBranding.preference("brandHeroBadge", value));
    }
}
