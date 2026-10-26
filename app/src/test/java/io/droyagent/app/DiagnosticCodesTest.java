package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class DiagnosticCodesTest {
    @Test public void arbitraryProviderMessagesAndCredentialsAreNeverExported() {
        assertNull(DiagnosticCodes.fromRuntimeOutput("apiKey: fictional-private-value; user text"));
        assertEquals("EACCES", DiagnosticCodes.fromRuntimeOutput("EACCES: path/user-data apiKey=fictional-private-value"));
        assertNull(DiagnosticCodes.fromRuntimeOutput("model response: all good"));
    }
}
