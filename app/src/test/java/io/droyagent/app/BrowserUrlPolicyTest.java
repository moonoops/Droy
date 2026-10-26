package io.droyagent.app;

import org.junit.Test;
import java.net.InetAddress;
import static org.junit.Assert.*;

public class BrowserUrlPolicyTest {
    @Test public void acceptsOnlyWebSchemesWithoutCredentials() throws Exception {
        assertEquals("example.com", BrowserSession.UrlPolicy.parse("https://example.com/page").getHost());
        for (String url : new String[]{"file:///data/data/app/file", "content://x", "javascript:alert(1)", "intent://x", "https://name:secret@example.com", "http://localhost:8787", "http://x.local", "https://x.internal", "https://example.com."}) {
            try { BrowserSession.UrlPolicy.parse(url); fail(url); } catch (Exception expected) { }
        }
    }
    @Test public void rejectsLocalPrivateAndTransitionAddresses() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "100.64.1.1", "198.18.1.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "::1", "::", "fe80::1", "fc00::1", "::ffff:127.0.0.1", "2002:7f00:1::", "2001:0000::1", "2001:db8::1"})
            assertFalse(ip, BrowserSession.UrlPolicy.isPublicAddress(InetAddress.getByName(ip)));
        assertTrue(BrowserSession.UrlPolicy.isPublicAddress(InetAddress.getByName("93.184.216.34")));
        assertTrue(BrowserSession.UrlPolicy.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test public void resolvedLoopbackAndNumericAliasesAreRejected() throws Exception {
        for (String url : new String[]{"http://127.0.0.1:8787", "http://2130706433", "http://127.1", "http://[::1]"}) {
            try { BrowserSession.UrlPolicy.validatePublic(url); fail(url); } catch (Exception expected) { }
        }
    }
}
