package io.droyagent.app.media;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Captures the temporary incoming URI grant before handing control to the chat. */
public final class ShareReceiveActivity extends Activity {
    private static final AtomicInteger VISIBLE = new AtomicInteger();
    private boolean counted;
    private String receiveId;

    public static boolean isVisible() { return VISIBLE.get() > 0; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        receiveId = state == null ? java.util.UUID.randomUUID().toString() : MediaInputPolicy.id(state.getString("mediaReceiveId"));
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(android.view.Gravity.CENTER); panel.setPadding(24, 24, 24, 24);
        panel.addView(new ProgressBar(this));
        TextView message = new TextView(this); message.setText(io.droyagent.app.NativeLocale.text("正在导入分享内容…", "Importing shared content…")); message.setTextSize(17); message.setPadding(0, 24, 0, 0);
        panel.addView(message); setContentView(panel);
        // Window resizing does not restart a granted stream copy in this receiving surface.
        Intent incoming = getIntent();
        new Thread(() -> receive(incoming), "droy-share-receive").start();
    }

    @Override protected void onStart() { super.onStart(); if (!counted) { counted = true; VISIBLE.incrementAndGet(); } }
    @Override protected void onStop() { if (counted) { counted = false; VISIBLE.decrementAndGet(); } super.onStop(); }
    @Override protected void onSaveInstanceState(Bundle out) { out.putString("mediaReceiveId", receiveId); super.onSaveInstanceState(out); }

    private void receive(Intent incoming) {
        MediaInputStore store = new MediaInputStore(this);
        JSONObject result;
        try {
            String action = incoming.getAction();
            if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) throw new IOException(io.droyagent.app.NativeLocale.text("不支持的分享操作", "Unsupported share action"));
            store.cleanAbandoned();
            String text = MediaInputPolicy.text(incoming.getCharSequenceExtra(Intent.EXTRA_TEXT));
            String subject = MediaInputPolicy.text(incoming.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
            if (text.isBlank() && incoming.getData() != null && MediaInputController.webUri(incoming.getData())) text = incoming.getData().toString();
            if (text.isBlank() && incoming.getClipData() != null && incoming.getClipData().getItemCount() == 1) {
                CharSequence clipText = incoming.getClipData().getItemAt(0).getText();
                if (clipText != null) text = MediaInputPolicy.text(clipText);
                else if (incoming.getClipData().getItemAt(0).getUri() != null
                    && MediaInputController.webUri(incoming.getClipData().getItemAt(0).getUri()))
                    text = incoming.getClipData().getItemAt(0).getUri().toString();
            }
            if (!subject.isBlank() && !text.contains(subject)) text = subject + (text.isBlank() ? "" : "\n" + text);
            result = store.importUrisForOperation(receiveId, "share", null, new ArrayList<>(MediaInputController.collectUris(incoming)),
                text, false, MediaInputPolicy.MAX_IMAGES);
        } catch (Exception failure) {
            try { result = store.error("share", null, failure); }
            catch (Exception writeFailure) {
                runOnUiThread(() -> {
                    android.widget.Toast.makeText(this, failure.getMessage() == null ? io.droyagent.app.NativeLocale.text("分享导入失败", "Share import failed") : failure.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    finish();
                });
                return;
            }
        }
        String id = result.optString("id");
        runOnUiThread(() -> {
            if (isDestroyed()) return;
            // Do not forward untrusted flags, components or extras from the sender.
            Intent chat = new Intent().setClassName(getPackageName(), "io.droyagent.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT).putExtra("mediaImportId", id);
            startActivity(chat); finish();
        });
    }
}
