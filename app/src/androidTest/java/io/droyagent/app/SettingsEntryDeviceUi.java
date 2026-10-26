package io.droyagent.app;

import org.json.JSONObject;
import static org.junit.Assert.assertTrue;

/** The mobile profile contributes a direct button to the settings-launcher slot. */
final class SettingsEntryDeviceUi {
    private SettingsEntryDeviceUi() {}

    static void openFromDrawer(DeviceUi ui) throws Exception {
        String launcher = "[data-droy-sidebar] [data-droy-settings-launcher]";
        assertTrue("Could not focus the visible settings launcher", ui.truth("(()=>{const e=document.querySelector("
            + JSONObject.quote(launcher) + ");if(!e||e.closest('[inert]'))return false;e.focus({preventScroll:true});return document.activeElement===e})()"));
        JSONObject[] previous = {null};
        DeviceUi.await("The visible settings launcher is still moving or obscured", 5000, () -> {
            JSONObject current = (JSONObject) ui.evaluate("(()=>{const e=document.querySelector(" + JSONObject.quote(launcher)
                + ");const r=e.getBoundingClientRect(),hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);"
                + "return {left:r.left,top:r.top,width:r.width,height:r.height,hit:!!hit&&(e===hit||e.contains(hit))}})()");
            boolean stable = previous[0] != null && current.toString().equals(previous[0].toString());
            previous[0] = current;
            return stable && current.getBoolean("hit");
        });
        ui.tap(launcher);
        DeviceUi.await("The direct launcher did not open settings", 5000,
            () -> ui.truth("!!document.querySelector('[data-droy-settings-panel]')"));
    }
}
