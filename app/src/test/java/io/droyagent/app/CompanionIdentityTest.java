package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CompanionIdentityTest {
    @Test public void stableInstancesAndLegacyRowsHavePathSafeIdentities() {
        assertTrue(CompanionIdentity.isValid("kaka"));
        assertTrue(CompanionIdentity.isValid("maimai"));
        assertTrue(CompanionIdentity.isValid("companion-c9ea4f1c-1aa0-4ca5-9921-419a753bb4a9"));
        assertTrue(CompanionIdentity.isValid("legacy.instance:1"));
    }

    @Test public void crossOwnerPathsAndAmbiguousTextCannotBecomeIdentities() {
        for (String value : new String[]{null, "", "..", "../kaka", "kaka/maimai", "kaka\\maimai",
                "kaka\n", "kaka maimai", "kaka%2Fmaimai", "a".repeat(161)})
            assertFalse(String.valueOf(value), CompanionIdentity.isValid(value));
    }
}
