package io.droyagent.memoryfixture;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView values;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        FixtureMemory.initialize();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Droy 进程内存测试");
        title.setTextSize(24);
        root.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("这是独立测试应用。金币与两个诱饵初值相同；花费只改变金币。修改只影响当前进程，重启后重置。");
        explanation.setTextSize(16);
        root.addView(explanation);
        values = new TextView(this);
        values.setTextSize(22);
        values.setPadding(0, padding, 0, padding);
        root.addView(values);
        root.addView(button("花费 100 金币", v -> { FixtureMemory.spend(); render(); }));
        root.addView(button("重置测试数值", v -> { FixtureMemory.reset(); render(); }));
        setContentView(root);
        applyCommand(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyCommand(intent);
    }

    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }

    private Button button(String text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(action);
        return button;
    }

    private void applyCommand(Intent intent) {
        String command = intent.getStringExtra("fixtureAction");
        if ("reset".equals(command)) FixtureMemory.reset();
        else if ("spend".equals(command)) FixtureMemory.spend();
        render();
    }

    private void render() {
        try {
            JSONObject state = new JSONObject(FixtureMemory.state());
            values.setText("金币：" + state.getInt("gold") + "\n诱饵 A：" + state.getInt("decoyA")
                + "\n诱饵 B：" + state.getInt("decoyB") + "\n体力：" + state.getInt("energy"));
        } catch (Exception invalid) {
            throw new IllegalStateException("Fixture state is invalid", invalid);
        }
    }
}
