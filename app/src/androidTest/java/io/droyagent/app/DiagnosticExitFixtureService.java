package io.droyagent.app;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Separate test APK UID and process; never packaged in the production application. */
public final class DiagnosticExitFixtureService extends Service {
    @Override public IBinder onBind(Intent intent) {
        String mode = intent.getStringExtra("mode");
        if (!"exit_self".equals(mode) && !"java_crash".equals(mode))
            throw new IllegalArgumentException("Unknown diagnostic fixture mode");
        Binder reply = new Binder();
        reply.attachInterface(null, Integer.toString(android.os.Process.myPid()));
        // The client unbinds after learning the PID, preventing crash/rebind loops.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if ("exit_self".equals(mode)) System.exit(73);
            throw new IllegalStateException("Droy fictional diagnostic crash fixture");
        }, 1000);
        return reply;
    }
}
