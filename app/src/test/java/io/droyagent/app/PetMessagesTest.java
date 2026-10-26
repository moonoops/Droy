package io.droyagent.app;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PetMessagesTest {
    @Test public void stageCopyVariesWithoutLosingTheRealActivityOrLiteralName() {
        PetMessages messages = new PetMessages(bound -> 0);
        for (String kind : new String[] {"started", "thinking", "observing", "controlling"}) {
            String first = messages.activity(kind, "100%墨墨");
            assertTrue(first.startsWith("100%墨墨"));
            assertNotEquals(first, messages.activity(kind, "100%墨墨"));
            assertFalse(first.contains("成功") || first.contains("完成啦") || first.contains("报错"));
            assertTrue(messages.activity(kind, "新名字").startsWith("新名字"));
        }
        assertTrue(messages.activity("started", "Droy").contains("开始"));
        String thought = messages.activity("thinking", "Droy"), observation = messages.activity("observing", "Droy");
        assertTrue(thought.contains("思考") || thought.contains("琢磨"));
        assertTrue(observation.contains("屏幕") || observation.contains("页面"));
        assertTrue(messages.activity("controlling", "Droy").contains("手机"));
    }

    @Test public void workingCopyUsesTheCurrentNicknameWithoutInventingAnOutcome() {
        AtomicInteger selection = new AtomicInteger();
        PetMessages messages = new PetMessages(bound -> selection.getAndIncrement() % bound);
        Set<String> variants = new HashSet<>();
        String previous = "";
        for (int i = 0; i < 30; i++) {
            String value = messages.working("墨墨");
            assertTrue(value.startsWith("墨墨"));
            assertFalse(value.contains("完成") || value.contains("成功") || value.contains("马上"));
            assertNotEquals(previous, value);
            variants.add(value); previous = value;
        }
        assertTrue("Repeated intentional announcements should have varied wording", variants.size() >= 4);
        assertTrue(messages.working("新的昵称").startsWith("新的昵称"));
    }

    @Test public void aRepeatedRandomDrawStillCannotRepeatThePreviousPhrase() {
        PetMessages messages = new PetMessages(bound -> 0);
        String first = messages.working("Droy"), second = messages.working("Droy");
        assertNotEquals(first, second);
        assertEquals(first, messages.working("Droy"));
        assertEquals("Droy正在处理。", first);
    }

    @Test public void idleChatterHasIndependentVariationAndPreservesLiteralNames() {
        PetMessages messages = new PetMessages(bound -> 0);
        String first = messages.idle("100%猫");
        messages.working("100%猫");
        assertNotEquals(first, messages.idle("100%猫"));
        assertTrue(first.startsWith("100%猫"));
    }
    @Test public void customVoicePreservesLiteralPercentAndDescribedCharacter() throws Exception {
        org.json.JSONObject voice = new org.json.JSONObject().put("working", new org.json.JSONArray()
            .put("{name}：还在核对，100%认真。") .put("{name}：我再瞧一眼。"))
            .put("idle", new org.json.JSONArray().put("{name}：我在。") .put("{name}：有事叫我。"));
        PetPersona persona = PetPersona.parse(new org.json.JSONObject().put("temperament", "shy")
            .put("description", "害羞却认真，不喜欢长篇大论").put("voice", voice));
        PetMessages messages = new PetMessages(bound -> 0); messages.usePersona(persona);
        assertEquals("小狐狸：还在核对，100%认真。", messages.working("小狐狸"));
        assertEquals("小狐狸：我再瞧一眼。", messages.working("小狐狸"));
        assertEquals("害羞却认真，不喜欢长篇大论", persona.description);
        assertTrue(persona.weight("enterPeek") > persona.weight("enterJump"));
    }
    @Test public void generatedEmojiVoiceUsesTheSameCodepointLimitAsTheGenerator() throws Exception {
        String emojiLine = "{name}" + "🐱".repeat(70);
        org.json.JSONObject document = new org.json.JSONObject().put("description", "🐱".repeat(600))
            .put("voice", new org.json.JSONObject().put("working", new org.json.JSONArray().put(emojiLine).put("{name}还在处理")));
        assertEquals(emojiLine, PetPersona.parse(document).working[0]);
        document.getJSONObject("voice").getJSONArray("working").put(0, "🐱".repeat(121));
        assertThrows(IllegalArgumentException.class, () -> PetPersona.parse(document));
    }
}
