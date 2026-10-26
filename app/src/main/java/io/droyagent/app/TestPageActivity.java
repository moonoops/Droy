package io.droyagent.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

public final class TestPageActivity extends Activity {
    private final Handler updates = new Handler(Looper.getMainLooper());
    private FrameLayout container;
    private LinearLayout layout;
    private TextView dynamicStatus;
    private TextView itemTitle;
    private int refreshCount;
    private int confirmationCount;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            refreshUnrelatedContent();
            updates.postDelayed(this, 80);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        container = new FrameLayout(this);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 96, 32, 32);
        TextView title = new TextView(this); title.setText("DroyAgent 操作练习"); title.setTextSize(24); layout.addView(title);
        TextView hint = new TextView(this); hint.setText("输入一段中文并确认，结果会显示在下方。此页面不发送任何信息。"); layout.addView(hint);
        EditText input = new EditText(this); input.setId(R.id.test_input); input.setHint("在这里输入文字"); input.setContentDescription("练习输入框"); layout.addView(input);
        Button confirm = new Button(this); confirm.setId(R.id.test_confirm); confirm.setText("确认"); layout.addView(confirm);
        TextView result = new TextView(this); result.setId(R.id.test_result); result.setText("尚未确认"); result.setTextSize(20); layout.addView(result);
        confirm.setOnClickListener(v -> { confirmationCount++; result.setText("已确认：" + input.getText()); });
        Button back = new Button(this); back.setText("返回 DroyAgent"); back.setOnClickListener(v -> finish()); layout.addView(back);
        container.addView(layout);
        setContentView(container);
    }

    void startUnrelatedUpdates() {
        if (dynamicStatus == null) {
            dynamicStatus = new TextView(this);
            dynamicStatus.setSingleLine(true);
            layout.addView(dynamicStatus);
        }
        updates.removeCallbacks(refresh);
        refresh.run();
    }

    void refreshUnrelatedContent() {
        if (dynamicStatus != null) dynamicStatus.setText("动态推荐更新：" + (++refreshCount));
    }

    int refreshCount() { return refreshCount; }
    int confirmationCount() { return confirmationCount; }
    void makePageClickable() { layout.setOnClickListener(v -> { }); }

    void showReusableItem(boolean childButton) {
        View original = findViewById(R.id.test_confirm);
        int index = layout.indexOfChild(original);
        layout.removeView(original);
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        itemTitle = new TextView(this);
        itemTitle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        itemTitle.setText("商品 A");
        item.addView(itemTitle);
        View action;
        if (childButton) {
            item.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            item.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(0, 1, 0, 1, false));
                }
            });
            Button button = new Button(this);
            button.setText("加入购物车");
            item.addView(button);
            action = button;
        } else {
            action = item;
        }
        action.setId(R.id.test_confirm);
        action.setOnClickListener(v -> ((TextView) findViewById(R.id.test_result)).setText("已确认商品：" + itemTitle.getText()));
        layout.addView(item, index);
    }

    void rebindItemTitle() { itemTitle.setText("商品 B"); }

    void coverControl(int id) {
        View target = findViewById(id);
        int[] targetLocation = new int[2], containerLocation = new int[2];
        target.getLocationOnScreen(targetLocation);
        container.getLocationOnScreen(containerLocation);
        Button cover = new Button(this);
        cover.setText("覆盖层");
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(target.getWidth(), target.getHeight());
        position.leftMargin = targetLocation[0] - containerLocation[0];
        position.topMargin = targetLocation[1] - containerLocation[1];
        container.addView(cover, position);
    }

    @Override protected void onDestroy() {
        updates.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        SetupVerification.opened(this, getIntent().getStringExtra("setupVerification"));
    }

    @Override protected void onPause() {
        SetupVerification.paused(this);
        super.onPause();
    }
}

