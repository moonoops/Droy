package io.droyagent.app.nativeui.tools

import org.junit.Assert.*
import org.junit.Test

class NativeBrowserNavigationTest {
    @Test fun sourceLinkSelectsItsOwnBrowserTabWithoutChangingTheOriginSessionOrClaimingACompletedLoad() {
        val previous = NativeToolsState(page = NativeToolsPage.PREVIEW, selectedSessionId = "origin-conversation",
            selectedBrowserId = "existing-tool-tab", browser = NativeBrowserState("existing-tool-tab", url = "https://example.org/old"))
        val request = NativeBrowserNavigation("source-request", "source-tab", "https://example.com/article?q=a%20b#section")
        val opened = previous.withBrowserNavigation(request)
        assertEquals(NativeToolsPage.BROWSER, opened.page)
        assertEquals("origin-conversation", opened.selectedSessionId)
        assertEquals("source-tab", opened.selectedBrowserId)
        assertNull("Only a real BrowserService frame may establish the loaded page", opened.browser)
        assertEquals(request, opened.pendingBrowserNavigation)
    }

    @Test fun retainedStateHandsTheExactUrlToTheHostOnceAndNeverReplaysItAfterRecreation() {
        val request = NativeBrowserNavigation("request", "tab", "https://example.com/a?b=c#d")
        val waiting = NativeToolsState().withBrowserNavigation(request)
        val retainedBeforeDispatch = waiting.copy()
        assertEquals(request, retainedBeforeDispatch.pendingBrowserNavigation)
        val consumed = retainedBeforeDispatch.consumeBrowserNavigation("request")
        assertNull(consumed.pendingBrowserNavigation)
        assertEquals("tab", consumed.selectedBrowserId)
        assertSame(consumed, consumed.consumeBrowserNavigation("request"))
        assertNull(consumed.copy().pendingBrowserNavigation)
    }

    @Test fun lateOldHandoffCannotConsumeANewerLinkOrLoadIntoAnotherSelectedTab() {
        val first = NativeBrowserNavigation("old-request", "old-tab", "https://example.com/old")
        val second = NativeBrowserNavigation("new-request", "new-tab", "https://example.com/new")
        val newer = NativeToolsState().withBrowserNavigation(first).withBrowserNavigation(second)
        assertSame(newer, newer.consumeBrowserNavigation(first.requestId))
        assertEquals(second, newer.pendingBrowserNavigation)
        val changedTab = newer.copy(selectedBrowserId = "user-selected-tab")
        assertSame(changedTab, changedTab.consumeBrowserNavigation(second.requestId))
    }

    @Test fun handoffDoesNotRewriteOrTreatAnUnvalidatedUrlAsAllowed() {
        // NativeBrowserSurfaceHost.request remains the policy boundary; state must not substitute a trusted URL.
        for (url in listOf("file:///data/private", "http://127.0.0.1:8787", "https://name:secret@example.com", "javascript:alert(1)")) {
            val opened = NativeToolsState().withBrowserNavigation(NativeBrowserNavigation("request", "tab", url))
            assertEquals(url, opened.pendingBrowserNavigation?.url)
            assertNull(opened.browser)
        }
    }

    @Test fun rejectedSourceKeepsItsReadableAddressAndErrorAfterTheOneTimeRequestIsConsumed() {
        val request = NativeBrowserNavigation("request", "tab", "http://127.0.0.1:8787/rejected")
        val rejected = NativeToolsState().withBrowserNavigation(request).consumeBrowserNavigation("request")
            .copy(error = "NATIVE_BROWSER_FAILED", browser = NativeBrowserState("tab"))
        assertEquals(request.url, rejected.browserDisplayUrl)
        assertEquals("NATIVE_BROWSER_FAILED", rejected.error)
        assertNull(rejected.pendingBrowserNavigation)
        assertEquals(request.url, rejected.copy().browserDisplayUrl)
    }

    @Test fun verifiedCurrentPageUrlWinsOverTheSourceAfterRedirectAndAnOldTabCannotReplaceIt() {
        val requested = NativeToolsState().withBrowserNavigation(NativeBrowserNavigation("request", "current", "https://example.com/source"))
        assertEquals("https://example.org/final", requested.copy(browser = NativeBrowserState("current", url = "https://example.org/final")).browserDisplayUrl)
        assertEquals("https://example.com/source", requested.copy(browser = NativeBrowserState("old-tab", url = "https://example.net/old")).browserDisplayUrl)
    }
}
