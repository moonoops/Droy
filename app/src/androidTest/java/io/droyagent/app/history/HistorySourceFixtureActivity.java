package io.droyagent.app.history;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Fixed fictional content in the test APK, never user text. */
public final class HistorySourceFixtureActivity extends Activity {
    private TextView content;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,96,32,32);
        content=new TextView(this);content.setTextSize(24);content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        page.addView(content);setContentView(page);apply(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {super.onNewIntent(intent);setIntent(intent);apply(intent);}
    private void apply(Intent intent) {
        if(intent.getBooleanExtra("keepScreenOn",false))getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int phase=intent.getIntExtra("phase",0);
        if(phase<0){finish();return;}
        content.setText(phase==0?"Fictional history source breakfast fixture":"Fictional history source afternoon fixture");
    }
}
