package io.droyagent.app;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.droyagent.androiduse.AccessibilityRecovery;
import io.droyagent.androiduse.DroyAccessibilityService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Local practice screens only: no accounts, remote requests, or real messages. */
@RunWith(AndroidJUnit4.class)
public final class SequenceExecutionDeviceTest {
    private static final String[] KEYS = {"allowObserve", "allowControl", "allowRoot", "toolsStopped"};
    private final Map<String, Boolean> previous = new HashMap<>();
    private final List<ProductBridge> bridges = new ArrayList<>();
    private Instrumentation instrumentation;
    private Context context;
    private SharedPreferences preferences, receipts;
    private Set<String> oldReceipts;
    private TestPageActivity activity;
    private ProductBridge bridge;
    private String owner;
    private LinearLayout layout;
    private final Handler updates = new Handler(Looper.getMainLooper());
    private final AtomicInteger firstClicks = new AtomicInteger(), secondClicks = new AtomicInteger(), submissions = new AtomicInteger();

    @Before public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        context = instrumentation.getTargetContext();
        preferences = ProductPermissions.preferences(context);
        for (String key : KEYS) if (preferences.contains(key)) previous.put(key, preferences.getBoolean(key, false));
        assertTrue(preferences.edit().putBoolean("allowObserve", true).putBoolean("allowControl", true)
                .putBoolean("allowRoot", false).putBoolean("toolsStopped", false).commit());
        receipts = context.getSharedPreferences("sequence-receipts", Context.MODE_PRIVATE);
        oldReceipts = new HashSet<>(receipts.getAll().keySet());
        owner = "sequence-fixture-" + UUID.randomUUID();
        AccessibilityRecovery.get(context).onAppOpened(false);
        activity = (TestPageActivity) instrumentation.startActivitySync(new Intent(context, TestPageActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        DeviceUi.await("Accessibility unavailable", 15000, () -> DroyAccessibilityService.isConnected(context));
        instrumentation.runOnMainSync(() -> {
            layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(30, 120, 30, 30);
            activity.setContentView(layout);
        });
        bridge = newBridge();
    }

    @After public void tearDown() {
        updates.removeCallbacksAndMessages(null);
        for (ProductBridge active : bridges) { active.policy.stop(); active.stop(); }
        if (instrumentation != null) instrumentation.runOnMainSync(() -> { if (activity != null) activity.finish(); });
        if (preferences != null) {
            SharedPreferences.Editor restore = preferences.edit();
            for (String key : KEYS) { if (previous.containsKey(key)) restore.putBoolean(key, previous.get(key)); else restore.remove(key); }
            assertTrue("Could not restore product choices", restore.commit());
        }
        if (receipts != null) {
            SharedPreferences.Editor restore = receipts.edit();
            for (String key : receipts.getAll().keySet()) if (!oldReceipts.contains(key)) restore.remove(key);
            assertTrue("Could not remove local fixture receipts", restore.commit());
        }
    }

    private ProductBridge newBridge() {
        ProductBridge value = new ProductBridge(context, "fictional-sequence-not-listening-" + UUID.randomUUID());
        value.policy.enable(); bridges.add(value); return value;
    }

    @Test public void inputAndSubmitWaitForTheActualResultInOneCallAndCannotReplay() throws Exception {
        instrumentation.runOnMainSync(() -> {
            EditText input = new EditText(activity); input.setContentDescription("练习搜索词"); layout.addView(input);
            Button submit = new Button(activity); submit.setText("搜索练习商品"); layout.addView(submit);
            TextView result = new TextView(activity); result.setText("等待搜索"); layout.addView(result);
            submit.setOnClickListener(v -> { submissions.incrementAndGet(); updates.postDelayed(() -> result.setText("找到练习商品：" + input.getText()), 160); });
        });
        JSONObject basis = observe();
        JSONArray steps = new JSONArray().put(new JSONObject().put("action", "input_text")
                .put("target", target(basis, "练习搜索词")).put("text", "优化面包"))
                .put(new JSONObject().put("action", "tap").put("target", new JSONObject().put("text", "搜索练习商品"))
                        .put("terminal", true).put("afterCondition", new JSONObject().put("text", "找到练习商品：优化面包")));
        JSONObject request = request(basis, steps); String call = UUID.randomUUID().toString();
        JSONObject result = bridge.executeTool("sequence", request, owner, call);
        assertEquals(result.toString(), "sequence_completed", result.getString("status"));
        assertEquals(2, result.getInt("completedSteps")); assertEquals(1, submissions.get());
        assertTrue(result.getJSONObject("afterObservation").getString("text").contains("找到练习商品：优化面包"));
        JSONObject repeated = newBridge().executeTool("sequence", request, owner, call);
        assertTrue(repeated.getBoolean("existingReceipt")); assertEquals(1, submissions.get());
        assertFalse("Receipt metadata must not persist input text", receipts.getAll().values().toString().contains("优化面包"));
    }

    @Test public void delayedCheckboxSelectionIsConfirmedBeforeTheNextStep() throws Exception {
        instrumentation.runOnMainSync(() -> {
            CheckBox first = checkBox("练习图片甲", firstClicks), second = checkBox("练习图片乙", secondClicks);
            layout.addView(first); layout.addView(second);
            Button finish = new Button(activity); finish.setText("完成练习选择"); layout.addView(finish);
            finish.setOnClickListener(v -> { if (first.isChecked() && second.isChecked()) submissions.incrementAndGet(); });
        });
        JSONObject basis = observe();
        JSONArray steps = new JSONArray().put(select(basis, "练习图片甲")).put(select(basis, "练习图片乙"))
                .put(new JSONObject().put("action", "tap").put("target", new JSONObject().put("text", "完成练习选择")).put("terminal", true));
        JSONObject result = bridge.executeTool("sequence", request(basis, steps), owner, UUID.randomUUID().toString());
        assertEquals(result.toString(), "sequence_completed", result.getString("status"));
        assertEquals(1, firstClicks.get()); assertEquals(1, secondClicks.get()); assertEquals(1, submissions.get());
    }

    private CheckBox checkBox(String label, AtomicInteger clicks) {
        CheckBox box = new CheckBox(activity); box.setText(label);
        box.setOnClickListener(v -> {
            clicks.incrementAndGet(); boolean target = box.isChecked(); box.setChecked(!target);
            updates.postDelayed(() -> box.setChecked(target), 150);
        });
        return box;
    }

    @Test public void opaquePhotoCellsUseOneCountTransitionPerClick() throws Exception {
        instrumentation.runOnMainSync(() -> {
            Button send = new Button(activity); send.setText("发送");
            TextView first = photoCell("练习缩略图甲", firstClicks, send), second = photoCell("练习缩略图乙", secondClicks, send);
            layout.addView(first); layout.addView(second); layout.addView(send);
            send.setOnClickListener(v -> submissions.incrementAndGet());
        });
        JSONObject basis = observe();
        JSONArray steps = new JSONArray().put(select(basis, "练习缩略图甲").put("afterCondition", new JSONObject().put("text", "发送(1)")))
                .put(select(basis, "练习缩略图乙").put("afterCondition", new JSONObject().put("text", "发送(2)")))
                .put(new JSONObject().put("action", "tap").put("target", new JSONObject().put("text", "发送(2)")).put("terminal", true));
        JSONObject result = bridge.executeTool("sequence", request(basis, steps), owner, UUID.randomUUID().toString());
        assertEquals(result.toString(), "sequence_completed", result.getString("status"));
        assertEquals(1, firstClicks.get()); assertEquals(1, secondClicks.get()); assertEquals(1, submissions.get());
        assertEquals("count_transition", result.getJSONArray("completed").getJSONObject(0).getString("selectionVerification"));
        assertFalse(result.getJSONArray("completed").getJSONObject(0).getBoolean("selectionVerified"));
    }

    private TextView photoCell(String label, AtomicInteger clicks, Button send) {
        TextView view = new TextView(activity); view.setText(label); view.setTextSize(24); view.setPadding(15, 30, 15, 30);
        view.setOnClickListener(v -> {
            clicks.incrementAndGet(); updates.postDelayed(() -> {
                view.setSelected(true); send.setText("发送(" + (firstClicks.get() + secondClicks.get()) + ")");
            }, 150);
        });
        return view;
    }

    @Test public void cancellingAfterDispatchPreventsTheFollowingActionAndPersistsUnknown() throws Exception {
        String call = UUID.randomUUID().toString();
        instrumentation.runOnMainSync(() -> {
            Button first = new Button(activity); first.setText("取消本轮练习"); layout.addView(first);
            Button second = new Button(activity); second.setText("不应执行的后续"); layout.addView(second);
            first.setOnClickListener(v -> { firstClicks.incrementAndGet(); bridge.policy.cancel(owner, call); });
            second.setOnClickListener(v -> secondClicks.incrementAndGet());
        });
        JSONObject basis = observe();
        JSONObject request = request(basis, new JSONArray().put(new JSONObject().put("action", "tap").put("target", target(basis, "取消本轮练习")))
                .put(new JSONObject().put("action", "tap").put("target", new JSONObject().put("text", "不应执行的后续"))));
        JSONObject result = bridge.executeTool("sequence", request, owner, call);
        assertEquals(result.toString(), "sequence_stopped", result.getString("status"));
        assertTrue(result.getBoolean("outcomeUnknown")); assertEquals(1, firstClicks.get()); assertEquals(0, secondClicks.get());
        JSONObject replay = newBridge().executeTool("sequence", request, owner, call);
        assertTrue(replay.getBoolean("existingReceipt")); assertTrue(replay.getBoolean("outcomeUnknown"));
        assertEquals(1, firstClicks.get()); assertEquals(0, secondClicks.get());
    }

    private JSONObject observe() throws Exception {
        instrumentation.waitForIdleSync(); android.os.SystemClock.sleep(220);
        return bridge.executeTool("observe", new JSONObject(), owner, UUID.randomUUID().toString());
    }
    private static JSONObject target(JSONObject basis, String label) throws Exception {
        JSONArray nodes = basis.getJSONArray("nodes");
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.getJSONObject(index);
            if (label.equals(node.optString("text")) || label.equals(node.optString("description")))
                return new JSONObject().put("nodeId", node.getString("nodeId"));
        }
        throw new AssertionError("Missing fixture target " + label);
    }
    private static JSONObject select(JSONObject basis, String label) throws Exception {
        return new JSONObject().put("action", "set_selected").put("target", target(basis, label)).put("selected", true);
    }
    private static JSONObject request(JSONObject basis, JSONArray steps) throws Exception {
        return new JSONObject().put("snapshotId", basis.getString("snapshotId"))
                .put("packageName", basis.getString("packageName")).put("steps", steps).put("timeoutMs", 15000);
    }
}
