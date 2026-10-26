package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ChatFilePolicyTest {
    @Test public void downloadCredentialsStayOnTheExactRuntimeOrigin() {
        assertTrue(ChatFilePolicy.isLocalDownload("http://127.0.0.1:31234/api/session.export?sessionId=test", 31234));
        for (String address : new String[]{"https://127.0.0.1:31234/file", "http://localhost:31234/file",
                "http://127.0.0.1:31235/file", "http://user@127.0.0.1:31234/file", "http://example.test/file",
                "file:///data/private", "content://provider/file", "blob:http://127.0.0.1:31234/id",
                "http://127.0.0.1:31234/file#part", "http://127.0.0.1:31234/\nfile"})
            assertFalse(address, ChatFilePolicy.isLocalDownload(address, 31234));
    }

    @Test public void chooserNeverAcceptsPathsOrIntentUris() {
        assertTrue(ChatFilePolicy.isContentUri("content://com.example.documents/document/file%3A42"));
        for (String address : new String[]{"file:///data/private", "/data/private", "intent://provider/file",
                "content:///file", "content://user@provider/file", "content://provider/file#fragment"})
            assertFalse(address, ChatFilePolicy.isContentUri(address));
    }

    @Test public void linksCannotInjectAnAndroidIntentOrJavascript() {
        assertTrue(ChatFilePolicy.isExternalLink("https://example.test/docs?part=1"));
        assertTrue(ChatFilePolicy.isExternalLink("http://example.test/docs"));
        assertFalse(ChatFilePolicy.isExternalLink("intent://example.test/#Intent;scheme=https;end"));
        assertFalse(ChatFilePolicy.isExternalLink("javascript:alert(1)"));
        assertFalse(ChatFilePolicy.isExternalLink("https://user@example.test/"));
    }

    @Test public void pickerTypesAndNamesAreSafeWithoutRestrictingOrdinaryFiles() {
        assertArrayEquals(new String[]{"image/png", "image/jpeg", "text/*"},
            ChatFilePolicy.acceptedMimeTypes(new String[]{"image/png,image/jpeg", "text/*", "image/png", "not a type", null}));
        assertArrayEquals(new String[0], ChatFilePolicy.acceptedMimeTypes(new String[0]));
        assertEquals("application/zip", ChatFilePolicy.mimeType("application/zip; charset=utf-8"));
        assertEquals("application/octet-stream", ChatFilePolicy.mimeType("text/plain\r\nmalicious"));
        assertEquals(".._.._example_.zip", ChatFilePolicy.filename("../../example\n.zip"));
        assertEquals("dsh-download", ChatFilePolicy.filename(".."));
        assertEquals("对话日志.zip", ChatFilePolicy.filename("对话日志.zip"));
    }
}
