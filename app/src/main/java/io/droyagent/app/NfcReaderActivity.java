package io.droyagent.app;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import io.droyagent.androiduse.HardwareSupport;
import io.droyagent.androiduse.NfcTools;

/** Reader mode is deliberately visible and ends when this activity is dismissed. */
public final class NfcReaderActivity extends Activity {
    private String token;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = getIntent().getStringExtra("readerToken");
        if (token == null) { finish(); return; }
        LinearLayout body = NfcUi.body(this);
        TextView title = NfcUi.text(this, io.droyagent.androiduse.R.string.nfc_title, 24); body.addView(title);
        status = NfcUi.text(this, io.droyagent.androiduse.R.string.nfc_waiting, 17); body.addView(status);
        Button cancel = new Button(this); cancel.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_cancel));
        cancel.setOnClickListener(v -> { NfcTools.cancelReader(token); finish(); }); body.addView(cancel);
        NfcUi.show(this, body);
    }
    @Override protected void onResume() {
        super.onResume();
        if (!NfcTools.resumeReader(this, token, resource -> status.setText(HardwareSupport.text(this, resource)))) finish();
    }
    @Override protected void onPause() { NfcTools.pauseReader(this, token); super.onPause(); }
    @Override protected void onDestroy() { if (isFinishing()) NfcTools.cancelReader(token); super.onDestroy(); }
}
