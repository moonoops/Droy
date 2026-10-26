package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real-window settings layout and trusted touches, without submitting a prompt. */
@RunWith(AndroidJUnit4.class)
public final class SettingsLayoutDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private DeviceUi ui;
    private SharedPreferences preferences;
    private String originalTheme;
    private Boolean originalExpanded;
    private boolean themeChanged;
    private boolean fixtureOwned;

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Settings layout fixtures require a dedicated emulator",
                Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                        || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop existing tasks and engine before settings layout tests", RuntimeService.canManageHistory());
        preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        fixtureOwned = true;
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        DeviceUi.await("DSH did not synchronize its original theme", 15000, () -> preferences.contains("themePreference"));
        originalTheme = preferences.getString("themePreference", "system");
        JSONObject viewport = (JSONObject) ui.evaluate("({width:innerWidth,height:innerHeight,mobile:document.documentElement.hasAttribute('data-droy-mobile'),dpToCss:window.__droyTouchConfig?.dpToCss})");
        Bundle status = new Bundle();
        status.putString("stream", "\nDROY_SETTINGS_VIEWPORT " + viewport + "\n");
        instrumentation.sendStatus(2, status);
        assertTrue("Use a real mobile window with a short side of at most 600 CSS pixels: " + viewport,
                viewport.getBoolean("mobile") && Math.min(viewport.getDouble("width"), viewport.getDouble("height")) <= 600);
        openGeneral();
        originalExpanded = fontsExpanded();
    }

    @After public void tearDown() throws Exception {
        if (!fixtureOwned) return;
        try {
            if (ui != null && ui.web() != null && RuntimeService.ready) {
                if (originalExpanded != null && fontsExpanded() != originalExpanded) setFontsExpanded(originalExpanded);
                if (themeChanged && originalTheme != null) chooseTheme(originalTheme);
            }
        } finally {
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Settings layout test left its runtime active", 15000, RuntimeService::canManageHistory);
            fixtureOwned = false;
        }
    }

    @Test public void expandedFontFamiliesKeepReadableCopyAndUnclippedInputs() throws Exception {
        String originalFonts = fontPreferences();
        setFontsExpanded(true);
        assertEquals("All three original DSH font roles must remain available", 3,
                ((Number) ui.evaluate("document.querySelectorAll('[data-droy-font-row=family]').length")).intValue());
        for (String role : new String[]{"text", "code", "terminal"}) {
            String row = fontRow("family", role);
            ui.visibleWebBounds(row);
            JSONObject geometry = fontGeometry(row);
            assertTrue("Font title/description and input have insufficient width or overlap: " + geometry,
                    geometry.getBoolean("familyReadable"));
            assertNoHorizontalOverflow();
        }
        setFontsExpanded(false);
        assertFalse("Collapsing more fonts kept its advanced controls mounted",
                ui.truth("!!document.querySelector('[data-droy-font-advanced],[data-droy-font-row=family]')"));
        assertTrue("Opening or closing more fonts changed a font preference", originalFonts.equals(fontPreferences()));
        setFontsExpanded(originalExpanded);
    }

    @Test public void fontSizeControlsStayVisibleAndFingerSizedWithoutChangingTheirValues() throws Exception {
        String originalFonts = fontPreferences();
        setFontsExpanded(true);
        for (String role : new String[]{"text", "code", "terminal"}) {
            String row = fontRow("size", role);
            ui.visibleWebBounds(row);
            JSONObject geometry = fontGeometry(row);
            assertTrue("Font-size copy or controls are clipped or overlapping: " + geometry,
                    geometry.getBoolean("sizeReadable"));
            assertTouchTarget(row + " [data-droy-font-step=increase]");
            assertTouchTarget(row + " [data-droy-font-step=decrease]");
            boolean arrowsVisible = ui.truth("(()=>{const buttons=[...document.querySelectorAll("
                    + JSONObject.quote(row + " [data-droy-font-step]") + ")];return buttons.length===2&&buttons.every(e=>{"
                    + "for(let a=e;a&&!a.hasAttribute('data-droy-font-row');a=a.parentElement){const s=getComputedStyle(a);"
                    + "if(s.visibility==='hidden'||s.display==='none'||Number(s.opacity)===0)return false}return true})})()");
            assertTrue("Size arrows must be visible before hover or keyboard focus"
                    + (arrowsVisible ? "" : "; safe arrow styles=" + fontArrowStyles(row)), arrowsVisible);
            assertNoHorizontalOverflow();
        }
        assertTouchTarget("[data-droy-font-expander]");
        setFontsExpanded(false);
        setFontsExpanded(true);
        assertTrue("Inspecting or toggling size controls changed a font preference", originalFonts.equals(fontPreferences()));
        setFontsExpanded(originalExpanded);
    }

    @Test public void threeThemeChoicesFitOneRowAndRespondToActualTouches() throws Exception {
        ui.visibleWebBounds("[data-droy-theme-choices]");
        assertTrue("The three original theme choices must share one row without overflow", ui.truth("(()=>{"
                + "const group=document.querySelector('[data-droy-theme-choices]'),buttons=[...group.querySelectorAll('[data-droy-theme-choice]')],"
                + "r=group.getBoundingClientRect();if(buttons.length!==3)return false;const boxes=buttons.map(e=>e.getBoundingClientRect());"
                + "return boxes.every(b=>b.width>0&&b.height>0&&Math.abs(b.top-boxes[0].top)<1&&b.left>=r.left-.75&&b.right<=r.right+.75)"
                + "&&boxes[0].right<=boxes[1].left+.75&&boxes[1].right<=boxes[2].left+.75})()"));
        for (String theme : new String[]{"light", "dark", "system"}) {
            assertTouchTarget("[data-droy-theme-choice=" + theme + "]");
            chooseTheme(theme);
            assertNoHorizontalOverflow();
        }
        chooseTheme(originalTheme);
    }

    private void openGeneral() throws Exception {
        ui.hideKeyboard();
        ui.tap("[data-droy-menu]");
        DeviceUi.await("The real conversation menu did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-drawer-open=true]')"));
        awaitSurfaceSettled("[data-droy-sidebar]", true);
        SettingsEntryDeviceUi.openFromDrawer(ui);
        DeviceUi.await("The original settings page did not open", 5000,
                () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
        awaitSurfaceSettled("[data-droy-settings-panel]", false);
        awaitSurfaceSettled("[data-droy-settings-section=general]", false);
        ui.tap("[data-droy-settings-section=general]");
        DeviceUi.await("The original font and theme controls did not load", 10000,
                () -> ui.truth("!!document.querySelector('[data-droy-font-expander]')&&document.querySelectorAll('[data-droy-theme-choice]').length===3"));
        awaitSurfaceSettled("[data-droy-font-group]", false);
    }

    private void awaitSurfaceSettled(String selector, boolean drawer) throws Exception {
        JSONObject[] previous = {null};
        DeviceUi.await("The actual touch surface is still moving: " + selector, 5000, () -> {
            JSONObject state = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");"
                    + "if(!e)return {ready:false};const r=e.getBoundingClientRect(),s=getComputedStyle(e);"
                    + "let animating=false;for(let a=e;a;a=a.parentElement){a.getBoundingClientRect();"
                    + "if(a.getAnimations().some(animation=>animation.pending||animation.playState==='running'))animating=true;}"
                    + "return {ready:r.width>0&&r.height>0&&!e.closest('[inert]')&&s.visibility!=='hidden'&&s.display!=='none'&&!animating,"
                    + "left:r.left,top:r.top,width:r.width,height:r.height}})()");
            boolean stable = state.getBoolean("ready") && (!drawer || Math.abs(state.getDouble("left")) < .75)
                    && previous[0] != null;
            if (stable) for (String dimension : new String[]{"left", "top", "width", "height"})
                stable &= Math.abs(state.getDouble(dimension) - previous[0].getDouble(dimension)) < .5;
            previous[0] = state.getBoolean("ready") ? state : null;
            return stable;
        });
    }

    private boolean fontsExpanded() throws Exception {
        return ui.truth("document.querySelector('[data-droy-font-expander]')?.getAttribute('aria-expanded')==='true'");
    }

    private void setFontsExpanded(boolean expanded) throws Exception {
        if (fontsExpanded() != expanded) ui.tap("[data-droy-font-expander]");
        DeviceUi.await("More fonts did not become " + (expanded ? "expanded" : "collapsed"), 5000,
                () -> fontsExpanded() == expanded
                        && ui.truth("!!document.querySelector('[data-droy-font-advanced]')") == expanded);
    }

    private String fontRow(String kind, String role) {
        return "[data-droy-font-row='" + kind + "'][data-droy-font-role='" + role + "']";
    }

    private String fontPreferences() throws Exception {
        return String.valueOf(ui.evaluate("(()=>{const style=getComputedStyle(document.body);return JSON.stringify("
                + "['--dsh-content-font-size','--dsh-code-font-size','--dsh-terminal-font-size','--dsh-font-family-text','--dsh-font-family-code','--dsh-font-family-terminal']"
                + ".map(name=>style.getPropertyValue(name)))})()"));
    }

    private JSONObject fontGeometry(String selector) throws Exception {
        return (JSONObject) ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(selector) + "),"
                + "copy=row.querySelector('[data-droy-font-copy]'),title=copy?.querySelector('[data-droy-font-title]'),"
                + "description=copy?.querySelector('[data-droy-font-description]'),input=row.querySelector('[data-droy-font-field]'),"
                + "field=row.querySelector(':scope > :has([data-droy-font-field])'),"
                + "control=row.querySelector('[data-droy-font-control]'),value=row.querySelector('[data-droy-font-value]');"
                + "const rect=e=>{if(!e)return null;const r=e.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height}};"
                + "const r=rect(row),c=rect(copy),t=rect(title),d=rect(description),f=rect(field),i=rect(input),k=rect(control),v=rect(value),narrow=innerWidth<=600;"
                + "const within=b=>!!b&&b.width>0&&b.height>0&&b.left>=r.left-.75&&b.right<=r.right+.75&&b.top>=r.top-.75&&b.bottom<=r.bottom+.75;"
                + "const inputWithinField=!!i&&!!f&&i.width>0&&i.height>0&&i.left>=f.left-.75&&i.right<=f.right+.75&&i.top>=f.top-.75&&i.bottom<=f.bottom+.75"
                + "&&f.width-i.width<=32&&f.height-i.height<=32;"
                + "const apart=(a,b)=>a.bottom<=b.top+.75||b.bottom<=a.top+.75||a.right<=b.left+.75||b.right<=a.left+.75;"
                + "const copyReadable=within(c)&&within(t)&&within(d)&&c.width>=r.width*(narrow ? 0.95 : 0.4)&&t.width>=c.width*.9&&d.width>=c.width*.9;"
                + "return {viewport:{width:innerWidth,height:innerHeight},role:row.getAttribute('data-droy-font-role'),row:r,copy:c,title:t,description:d,field:f,input:i,control:k,value:v,"
                + "familyReadable:copyReadable&&within(f)&&inputWithinField&&f.width>=r.width*(narrow ? 0.95 : 0.4)&&apart(c,f)"
                + "&&(!narrow||(f.top>=c.bottom-.75&&f.top-c.bottom<=24&&r.height<=c.height+f.height+72)),"
                + "sizeReadable:copyReadable&&within(k)&&within(v)&&apart(c,k)&&(!narrow||k.top>=c.bottom-.75)}})()");
    }

    private void assertTouchTarget(String selector) throws Exception {
        JSONObject bounds = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(selector) + ");"
                + "if(!e)return {present:false};const r=e.getBoundingClientRect(),s=getComputedStyle(e);return {present:true,width:r.width,height:r.height,"
                + "visible:s.visibility!=='hidden'&&s.display!=='none'&&!e.closest('[inert]'),ratio:window.__droyTouchConfig?.dpToCss||0}})()");
        assertTrue("A settings touch control must occupy at least 48 Android dp: " + bounds,
                bounds.optBoolean("present") && bounds.optBoolean("visible") && bounds.optDouble("ratio") > 0
                        && bounds.getDouble("width") / bounds.getDouble("ratio") >= 47.5
                        && bounds.getDouble("height") / bounds.getDouble("ratio") >= 47.5);
    }

    private Object fontArrowStyles(String row) throws Exception {
        return ui.evaluate("(()=>{const row=document.querySelector(" + JSONObject.quote(row) + ");"
                + "return {role:row?.getAttribute('data-droy-font-role'),buttons:[...row.querySelectorAll('[data-droy-font-step]')].slice(0,2).map(e=>{"
                + "const ancestors=[];for(let a=e;a&&ancestors.length<8;a=a.parentElement){const s=getComputedStyle(a),r=a.getBoundingClientRect();"
                + "ancestors.push({tag:a.tagName,className:a.className,display:s.display,visibility:s.visibility,opacity:s.opacity,pointerEvents:s.pointerEvents,"
                + "rect:{left:r.left,top:r.top,width:r.width,height:r.height}});if(a===row)break}"
                + "return {direction:e.getAttribute('data-droy-font-step'),disabled:e.disabled,inert:!!e.closest('[inert]'),ancestors}})}})()");
    }

    private void assertNoHorizontalOverflow() throws Exception {
        JSONObject widths = (JSONObject) ui.evaluate("(()=>{const rect=e=>({client:e.clientWidth,scroll:e.scrollWidth});return {"
                + "root:rect(document.documentElement),panel:rect(document.querySelector('[data-droy-settings-panel]')),"
                + "options:rect(document.querySelector('[data-droy-settings-options]'))}})()");
        for (String element : new String[]{"root", "panel", "options"}) {
            JSONObject width = widths.getJSONObject(element);
            assertTrue("Settings content requires horizontal scrolling: " + widths,
                    width.getDouble("scroll") <= width.getDouble("client") + 1);
        }
    }

    private void chooseTheme(String theme) throws Exception {
        themeChanged = true;
        ui.tap("[data-droy-theme-choice=" + theme + "]");
        DeviceUi.await("The real DSH theme control did not accept a finger touch", 10000,
                () -> theme.equals(preferences.getString("themePreference", ""))
                        && ui.truth("document.querySelector('[data-droy-theme-choice=" + theme + "]')?.getAttribute('aria-pressed')==='true'"
                                + "&&document.documentElement.getAttribute('data-ds-theme-source')===" + JSONObject.quote(theme)));
    }
}
