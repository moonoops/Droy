package io.droyagent.terminal;

import android.app.Activity;
import android.os.Bundle;

public final class TerminalHarnessActivity extends Activity {
    RemoteTerminalView terminal;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        terminal = new RemoteTerminalView(this);
        setContentView(terminal);
    }
    @Override protected void onDestroy() {
        if (terminal != null) terminal.release();
        super.onDestroy();
    }
}
