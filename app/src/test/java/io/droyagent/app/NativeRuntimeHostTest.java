package io.droyagent.app;

import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeRuntimeHostTest {
    @Test public void foregroundStartCannotBypassHistoryMaintenance() throws Exception {
        Field maintenance = RuntimeService.class.getDeclaredField("historyMaintenance");
        maintenance.setAccessible(true);
        boolean previous = maintenance.getBoolean(null);
        try {
            maintenance.setBoolean(null, true);
            assertFalse(RuntimeService.canStart());
            // A blocked launch must not attempt to use the Android context.
            NativeRuntimeHost.start(null);
        } finally { maintenance.setBoolean(null, previous); }
    }

    @Test public void foregroundStartWaitsForThePreviousWorkerToFinish() throws Exception {
        Field worker = RuntimeService.class.getDeclaredField("lastWorker");
        worker.setAccessible(true);
        Object previous = worker.get(null);
        ExecutorService unfinished = Executors.newSingleThreadExecutor();
        try {
            worker.set(null, unfinished);
            assertFalse(RuntimeService.canStart());
            NativeRuntimeHost.start(null);
        } finally {
            worker.set(null, previous);
            unfinished.shutdownNow();
        }
    }
}
