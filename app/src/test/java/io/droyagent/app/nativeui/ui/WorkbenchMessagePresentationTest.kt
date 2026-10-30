package io.droyagent.app.nativeui.ui

import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeMessageAttachment
import org.junit.Assert.*
import org.junit.Test

class WorkbenchMessagePresentationTest {
    @Test fun processRecordsAreHiddenWithoutDroppingTheirDeliverables() {
        val image = NativeMessageAttachment("image", "fixture-image", "result.png", "image/png")
        for (kind in listOf("context", "reasoning", "tool/result")) {
            val event = NativeMessage("fixture-$kind", "assistant", "Internal process", kind = kind)
            assertFalse(workbenchMessageVisible(event))
            assertTrue(workbenchMessageVisible(event.copy(attachments = listOf(image))))
        }
        val tool = NativeMessage("tool", "assistant", "Internal output", toolName = "android_find_apps")
        assertFalse(workbenchMessageVisible(tool))
        assertTrue(workbenchMessageVisible(tool.copy(attachments = listOf(image))))
    }

    @Test fun ordinaryUserAssistantAndErrorContentRemainVisible() {
        for (role in listOf("user", "assistant")) {
            assertTrue(workbenchMessageVisible(NativeMessage(role, role, "Fixture message")))
        }
        assertTrue(workbenchMessageVisible(NativeMessage("error", "assistant", "Fixture error", kind = "error", failed = true)))
    }

    @Test fun durableAudioFilesDoNotRequireANonexistentDshMimeField() {
        val audio = NativeMessageAttachment("file", "fixture", "recording.WAV")
        assertTrue(audio.isPlayableAudio())
        assertTrue(audio.copy(name = "clip.mp3").isPlayableAudio())
        assertFalse(audio.copy(name = "recording.wav.txt").isPlayableAudio())
        assertFalse(audio.copy(name = "recording").isPlayableAudio())
        assertFalse(audio.copy(name = null).isPlayableAudio())
        assertFalse(audio.copy(kind = "image").isPlayableAudio())
        assertTrue(audio.copy(name = "unnamed", mediaType = "audio/ogg").isPlayableAudio())
    }

    @Test fun onlyCommittedSessionAttachmentsCanBeOpened() {
        val attachment = NativeMessageAttachment("image", "fixture-image", "result.png")
        val message = NativeMessage("message", "assistant", "", seq = 4, attachments = listOf(attachment))
        assertTrue(message.canOpenAttachment(attachment))
        assertFalse(message.copy(streaming = true).canOpenAttachment(attachment))
        assertFalse(message.copy(seq = null).canOpenAttachment(attachment))
        assertFalse(message.canOpenAttachment(attachment.copy(attachmentId = null)))
    }
}
