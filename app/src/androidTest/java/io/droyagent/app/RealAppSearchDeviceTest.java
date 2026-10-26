package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.ActionException;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Opt-in real JD search; leaves the results visible and never calls a model. */
@RunWith(AndroidJUnit4.class)
public final class RealAppSearchDeviceTest {
    private static final String PACKAGE = "com.jingdong.app.mall";
    private static final String QUERY = "m6 macmini";
    private Context context;
    private ProductBridge bridge;
    private String task;
    private int calls;

    @Test public void searchesJdThroughProductToolsWhileBrowserServiceIsBound() throws Exception {
        assumeTrue("Enable explicitly with -e droyJdSearch true",
                "true".equalsIgnoreCase(InstrumentationRegistry.getArguments().getString("droyJdSearch")));
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        SharedPreferences settings = ProductPermissions.preferences(context);
        assertFalse("Respect the user's saved pause before any operation", ProductPermissions.stopped(context));
        for (String permission : new String[]{"allowApps", "allowObserve", "allowControl", "allowBrowser"}) {
            assertTrue("Required capability is not authorized: " + permission, ProductPermissions.allowed(settings, permission));
        }
        // Instrumentation restarts the target process; wait for the already
        // enabled service to reconnect before asking it to verify a launch.
        DeviceUi.await("The authorized accessibility service did not reconnect", 15000, () ->
                io.droyagent.androiduse.DroyAccessibilityService.isConnected(context));
        task = "jd-regression-" + UUID.randomUUID();
        bridge = new ProductBridge(context, "unused-local-regression-token");
        bridge.policy.enable();
        BrowserSession browser = new BrowserSession(context);
        String tab = "jd-regression-blank-" + UUID.randomUUID();
        boolean attached = false;
        Throwable failure = null;
        try {
            // Use the same foreground entry as a user-started task. Some vendors
            // suppress background instrumentation service launches after teardown.
            MainActivity activity = (MainActivity) instrumentation.startActivitySync(new android.content.Intent(context, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            new DeviceUi(instrumentation, activity).awaitPage();
            JSONObject bound = browser.execute("browser_ui", new JSONObject().put("tabId", tab).put("operation", "attach"), this::checkSavedStop);
            attached = true;
            assertEquals(tab, bound.getString("sessionId"));
            assertTrue(bound.getBoolean("sessionOpen"));
            stage("blank_browser_bound", true);

            JSONObject found = tool("find_apps", new JSONObject().put("query", "京东").put("limit", 30));
            boolean available = false;
            JSONArray apps = found.getJSONArray("apps");
            for (int index = 0; index < apps.length(); index++) {
                if (PACKAGE.equals(apps.getJSONObject(index).optString("packageName"))) available = true;
            }
            assertTrue("The intended JD launcher app must be visible", available);
            stage("jd_found", true);
            JSONObject opened = tool("open_app", new JSONObject().put("packageName", PACKAGE).put("verifyTimeoutMs", 5000));
            assertTrue("JD launch must be confirmed in foreground", opened.getBoolean("foregroundVerified"));
            assertEquals("Production launch must return a readable target page without test-only departure waits",
                    "observed", opened.getJSONObject("afterObservation").getString("status"));
            assertEquals(PACKAGE, opened.getJSONObject("afterObservation").getString("packageName"));
            stage("jd_foreground", true);

            JSONObject initial = awaitObservation("search_entry_or_editor", observation ->
                    searchEditor(observation) != null || searchEntry(observation) != null);
            JSONObject editing = initial;
            if (searchEditor(initial) == null) {
                tool("tap", nodeArgs(initial, searchEntry(initial)));
                stage("search_region_clicked_once", true);
                editing = awaitObservation("search_editor", observation -> searchEditor(observation) != null);
            }
            JSONObject editor = searchEditor(editing);
            JSONObject input = tool("input_text", nodeArgs(editing, editor).put("text", QUERY));
            assertTrue("Exact query must be read back after one input action", input.getBoolean("inputTextVerified"));
            assertFalse(input.getBoolean("submitActionRequested"));
            stage("query_input_verified", true);

            JSONObject typed = awaitObservation("exact_query_readback", observation -> {
                JSONObject field = searchEditor(observation);
                return field != null && QUERY.equals(field.optString("text"));
            });
            assertEquals(QUERY, searchEditor(typed).getString("text"));
            JSONObject submit = searchSubmit(typed, searchEditor(typed));
            assertNotNull("Search page must expose an unambiguous search submit control", submit);
            tool("tap", nodeArgs(typed, submit));
            stage("search_submitted_once", true);

            JSONObject result = awaitObservation("business_result", RealAppSearchDeviceTest::hasSearchResultEvidence);
            assertTrue(hasSearchResultEvidence(result));
            stage("business_result_verified", true);
            JSONObject retained = browser.execute("browser_ui", new JSONObject().put("tabId", tab).put("operation", "state"), this::checkSavedStop);
            assertTrue("The unrelated bound browser tab must remain open", retained.getBoolean("sessionOpen"));
            stage("bound_browser_retained", true);
        } catch (Exception | AssertionError error) {
            failure = error;
            throw error;
        } finally {
            try {
                if (attached) browser.execute("browser_ui", new JSONObject().put("tabId", tab).put("operation", "close"), () -> {});
            } catch (Exception cleanup) {
                if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
            } finally {
                BrowserSession.release();
                bridge.policy.stop();
                RuntimeService.requestStop();
                DeviceUi.await("The test engine did not stop", 15000, () -> !RuntimeService.hasActiveSession());
            }
        }
    }

    private void checkSavedStop() throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || ProductPermissions.stopped(context)) {
            bridge.policy.stop();
            throw new InterruptedException("Real-app regression stopped");
        }
    }

    private JSONObject tool(String action, JSONObject args) throws Exception {
        checkSavedStop();
        JSONObject result = bridge.executeTool(action, args, task, "call-" + (++calls));
        checkSavedStop();
        return result;
    }

    private JSONObject awaitObservation(String phase, ObservationCondition condition) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        JSONObject last = null;
        do {
            try {
                JSONObject observed = tool("observe", new JSONObject());
                last = observed;
                if (PACKAGE.equals(observed.optString("packageName")) && condition.matches(observed)) return observed;
            } catch (ActionException unavailable) {
                if (!"window_unavailable".equals(unavailable.getCode())) throw unavailable;
            }
            checkSavedStop();
            SystemClock.sleep(150);
        } while (SystemClock.elapsedRealtime() < deadline);
        if (last != null) saveDiagnostic(phase, last);
        stage(phase, false);
        throw new AssertionError("JD phase did not expose the required evidence: " + phase);
    }

    private void saveDiagnostic(String phase, JSONObject observed) throws Exception {
        JSONArray candidates = new JSONArray();
        JSONArray nodes = observed.getJSONArray("nodes");
        int editClasses = 0, editable = 0, searchLabels = 0;
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            boolean editor = node.optString("className").contains("EditText");
            boolean search = isSearchLabel(node);
            if (editor) editClasses++;
            if (node.optBoolean("editable")) editable++;
            if (search) searchLabels++;
            if (editor || node.optBoolean("editable") || search) candidates.put(new JSONObject()
                    .put("className", node.optString("className")).put("editable", node.optBoolean("editable"))
                    .put("enabled", node.optBoolean("enabled")).put("clickable", node.optBoolean("clickable"))
                    .put("password", node.optBoolean("password")).put("bounds", node.getJSONObject("bounds"))
                    .put("queryMatched", QUERY.equals(node.optString("text"))).put("searchLabel", search));
        }
        JSONObject diagnostic = new JSONObject().put("phase", phase).put("packageName", observed.optString("packageName"))
                .put("nodes", nodes.length()).put("truncated", observed.optBoolean("truncated"))
                .put("width", observed.optInt("width")).put("height", observed.optInt("height"))
                .put("editorClasses", editClasses).put("editableNodes", editable).put("searchLabels", searchLabels)
                .put("candidates", candidates);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(context.getCacheDir(), "jd-regression-diagnostic.json"))) {
            output.write(diagnostic.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static JSONObject searchEntry(JSONObject observed) throws Exception {
        JSONArray nodes = observed.getJSONArray("nodes");
        JSONObject marker = null;
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (node.optString("description").contains("搜索栏")) { marker = node; break; }
        }
        if (marker == null) return null;
        Rect bar = bounds(marker);
        return smallestClickableCovering(nodes, bar, bar.width() / 2);
    }

    private static JSONObject searchEditor(JSONObject observed) throws Exception {
        JSONArray nodes = observed.getJSONArray("nodes");
        JSONObject best = null;
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (!node.optBoolean("editable") || !node.optBoolean("enabled") || node.optBoolean("password")) continue;
            String label = node.optString("text") + node.optString("description") + node.optString("resourceId");
            if (label.contains("手机号") || label.contains("验证码") || label.contains("账号") || label.contains("密码")) continue;
            Rect area = bounds(node);
            if (area.top > observed.optInt("height") / 2 || area.width() < observed.optInt("width") / 3) continue;
            if (best == null || area.width() > bounds(best).width()) best = node;
        }
        return best != null && searchSubmit(observed, best) != null ? best : null;
    }

    private static JSONObject searchSubmit(JSONObject observed, JSONObject editor) throws Exception {
        JSONArray nodes = observed.getJSONArray("nodes");
        JSONObject best = null;
        long bestDistance = Long.MAX_VALUE;
        Rect field = bounds(editor);
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject marker = nodes.getJSONObject(index);
            if (!isSearchLabel(marker)) continue;
            Rect region = bounds(marker);
            JSONObject clickable = smallestClickableCovering(nodes, region, 0);
            if (clickable == null || clickable.optBoolean("editable")) continue;
            long distance = Math.abs((long) region.centerY() - field.centerY()) * 10000L + Math.abs(region.centerX() - field.right);
            if (distance < bestDistance) { best = clickable; bestDistance = distance; }
        }
        return best;
    }

    private static boolean isSearchLabel(JSONObject node) {
        String text = node.optString("text").replaceAll("\\s+", "");
        String description = node.optString("description").replaceAll("\\s+", "");
        return "搜索".equals(text) || "搜索".equals(description) || "搜索按钮".equals(description);
    }

    private static JSONObject smallestClickableCovering(JSONArray nodes, Rect marker, int minimumWidth) throws Exception {
        JSONObject best = null;
        long bestArea = Long.MAX_VALUE;
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (!node.optBoolean("clickable") || !node.optBoolean("enabled") || node.optBoolean("password")) continue;
            Rect region = bounds(node);
            long area = (long) region.width() * region.height();
            if (region.width() > minimumWidth && region.contains(marker.centerX(), marker.centerY()) && area > 0 && area < bestArea) {
                best = node; bestArea = area;
            }
        }
        return best;
    }

    private static boolean hasSearchResultEvidence(JSONObject observed) throws Exception {
        String text = observed.optString("text");
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        boolean queryVisible = normalized.contains(QUERY.replace(" ", ""));
        boolean resultControls = text.contains("综合") && text.contains("销量");
        boolean noResults = text.contains("暂无搜索结果") || text.contains("没有找到") || text.contains("无搜索结果");
        return queryVisible && (resultControls || noResults);
    }

    private static Rect bounds(JSONObject node) throws Exception {
        JSONObject area = node.getJSONObject("bounds");
        return new Rect(area.getInt("left"), area.getInt("top"), area.getInt("right"), area.getInt("bottom"));
    }

    private static JSONObject nodeArgs(JSONObject observed, JSONObject target) throws Exception {
        return new JSONObject().put("snapshotId", observed.getString("snapshotId")).put("nodeId", target.getString("nodeId"));
    }

    private static void stage(String name, boolean verified) { Log.i("DroyJdRegression", "stage=" + name + " verified=" + verified); }
    private interface ObservationCondition { boolean matches(JSONObject observation) throws Exception; }
}
