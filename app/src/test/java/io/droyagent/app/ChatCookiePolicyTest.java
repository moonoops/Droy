package io.droyagent.app;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatCookiePolicyTest {
    @Test public void identifiesOnlyDshAuthorityCookiesAndNeverReturnsValues() {
        String name = "dsh-auth-" + "A".repeat(43);
        String another = "dsh-auth-" + "_-a".repeat(14) + "0";
        String header = "droy_session=fictional; " + name + "=fictional==; unrelated=keep; "
                + another + "=another; " + name + "=duplicate; dsh-auth-short=keep; "
                + "dsh-auth-" + "B".repeat(44) + "=keep; dsh-auth-" + "C".repeat(42) + "+=keep";
        assertEquals(List.of(name, another), ChatCookiePolicy.authCookieNames(header));
    }

    @Test public void absentOrUnrelatedHeadersRequireNoCookieMutation() {
        assertTrue(ChatCookiePolicy.authCookieNames(null).isEmpty());
        assertTrue(ChatCookiePolicy.authCookieNames("").isEmpty());
        assertTrue(ChatCookiePolicy.authCookieNames("droy_session=fictional; other=unchanged; malformed").isEmpty());
    }
}
