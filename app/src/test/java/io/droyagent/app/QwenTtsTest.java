package io.droyagent.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class QwenTtsTest {
    @Test public void voiceValidationDoesNotLoadNativeCode() {
        assertEquals("Serena", QwenTts.validateVoice(""));
        assertEquals("Uncle_Fu", QwenTts.validateVoice("Uncle_Fu"));
        assertThrows(IllegalArgumentException.class, () -> QwenTts.validateVoice("unknown"));
    }

    @Test public void boundedSentencesPreserveSupplementaryCharacters() {
        String input = "你好。" + "𠮷".repeat(85) + "！结束";
        java.util.List<String> parts = QwenTts.sentences(input);
        assertEquals(input, String.join("", parts));
        for (String part : parts) {
            assertTrue(part.codePointCount(0, part.length()) <= 80);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
}
