package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeRuntimeStartRequestTest {
    @Test public void launchIsVisibleBeforeDispatchAndDuplicateLaunchIsSuppressed() {
        NativeRuntimeStartRequest request = new NativeRuntimeStartRequest();
        int[] calls = {0};
        request.launch(100, () -> { assertTrue(request.pending(100)); calls[0]++; });
        request.launch(101, () -> calls[0]++);
        assertEquals(1, calls[0]);
        request.acknowledge();
        assertFalse(request.pending(102));
    }

    @Test public void dispatchFailureRestoresRetryAndPreservesTheFailure() {
        NativeRuntimeStartRequest request = new NativeRuntimeStartRequest();
        SecurityException failure = new SecurityException("fixture");
        try { request.launch(100, () -> { throw failure; }); fail(); }
        catch (SecurityException actual) { assertSame(failure, actual); }
        assertFalse(request.pending(101));
    }

    @Test public void missingCallbackExpiresWithoutAutomaticallyDispatchingAnotherStart() {
        NativeRuntimeStartRequest request = new NativeRuntimeStartRequest();
        request.launch(100, () -> {});
        assertFalse(request.pending(10_100));
        request.launch(10_101, () -> {});
        assertTrue(request.pending(10_102));
    }
}
