package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeHttpErrors
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeHttpErrorsTest {
    @Test fun serviceNotReadyRetainsMachineCodeForStreamReconnect() {
        assertEquals("NATIVE_SERVICE_NOT_READY", NativeHttpErrors.code(503,
            """{"ok":false,"error":{"code":"NATIVE_SERVICE_NOT_READY","message":"Fixture"}}"""))
    }
    @Test fun rejectionIsNotChangedIntoTransientFailure() {
        assertEquals("NATIVE_DECISION_ENDED", NativeHttpErrors.code(410,
            """{"ok":false,"error":{"code":"NATIVE_DECISION_ENDED"}}"""))
        assertEquals("runtime_unavailable", NativeHttpErrors.code(401, "Unauthorized"))
        assertEquals("service_temporarily_unavailable", NativeHttpErrors.code(503, "Unavailable"))
    }
    @Test fun setupRetainsOnlyItsMachineCodeAndNeverItsRawProviderMessage() {
        assertEquals("MODEL_CONNECTION_FAILED", NativeHttpErrors.code(422,
            """{"ok":false,"code":"MODEL_CONNECTION_FAILED","message":"fictional-private-header"}"""))
        assertEquals("request_failed", NativeHttpErrors.code(422,
            """{"ok":false,"code":"fictional-private-header","message":"fixture"}"""))
    }
    @Test fun nativeSetupConflictAndInvalidInputKeepOnlyPublicCodes() {
        assertEquals("NATIVE_SETUP_IDENTITY_CHANGED", NativeHttpErrors.code(409,
            """{"ok":false,"code":"NATIVE_SETUP_IDENTITY_CHANGED","message":"fictional-private-header"}"""))
        assertEquals("INVALID_NATIVE_SETUP_REQUEST", NativeHttpErrors.code(400,
            """{"ok":false,"code":"INVALID_NATIVE_SETUP_REQUEST"}"""))
        assertEquals("request_failed", NativeHttpErrors.code(409,
            """{"ok":false,"code":"NATIVE_PRIVATE_HEADER","message":"fixture"}"""))
    }
}
