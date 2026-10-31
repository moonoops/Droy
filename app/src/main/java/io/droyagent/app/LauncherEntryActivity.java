package io.droyagent.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Keeps the long-lived UI task rooted on a component that icon rotation never disables. */
public final class LauncherEntryActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent source = getIntent();
        boolean freshOpen = isFreshLauncherOpen(source, state != null);
        try {
            startActivity(new Intent(this, NativeAppActivity.class)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } finally {
            // Theme.NoDisplay requires finish before onResume; this transient task has no affinity.
            finish();
        }
        if (freshOpen) LauncherIconRotation.onLauncherOpened(getApplicationContext());
    }

    static boolean isFreshLauncherOpen(Intent intent, boolean recreated) {
        return !recreated && intent != null && Intent.ACTION_MAIN.equals(intent.getAction())
            && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
    }
}
