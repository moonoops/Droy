package io.droyagent.app;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.text.InputFilter;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.droyagent.androiduse.HardwareSupport;
import io.droyagent.androiduse.NfcCredentials;

/** Existing card keys are entered locally rather than copied through the model. */
public final class NfcCredentialActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!allowed()) { finish(); return; }
        LinearLayout body = NfcUi.body(this);
        body.addView(NfcUi.text(this, io.droyagent.androiduse.R.string.nfc_credential_title, 24));
        body.addView(NfcUi.text(this, io.droyagent.androiduse.R.string.nfc_credential_help, 16));
        EditText name = new EditText(this); name.setSingleLine(true); name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setHint(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_credential_name)); body.addView(name);
        EditText key = new EditText(this); key.setSingleLine(true); key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setFilters(new InputFilter[]{new InputFilter.LengthFilter(12)}); key.setSaveEnabled(false);
        key.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        key.setHint(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_credential_key)); body.addView(key);
        TextView status = NfcUi.text(this, io.droyagent.androiduse.R.string.nfc_credential_help, 16);
        Button save = new Button(this); save.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_credential_save));
        save.setOnClickListener(v -> {
            if (!allowed()) { key.setText(""); finish(); return; }
            try {
                new NfcCredentials(this).save(name.getText().toString(), key.getText().toString()); key.setText("");
                save.setEnabled(false); name.setEnabled(false); key.setEnabled(false);
                status.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_credential_saved));
            } catch (IllegalArgumentException invalid) { status.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_invalid_request)); }
            catch (Exception failure) { status.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_save_failed)); }
        });
        body.addView(save); body.addView(status);
        Button back = new Button(this); back.setText(HardwareSupport.text(this, io.droyagent.androiduse.R.string.nfc_back)); back.setOnClickListener(v -> { key.setText(""); finish(); }); body.addView(back);
        NfcUi.show(this, body);
    }
    private boolean allowed() {
        return !ProductPermissions.stopped(this)
            && ProductPermissions.allowed(ProductPermissions.preferences(this), "allowNfcRead")
            && ProductPermissions.allowed(ProductPermissions.preferences(this), "allowNfcWrite");
    }
}
