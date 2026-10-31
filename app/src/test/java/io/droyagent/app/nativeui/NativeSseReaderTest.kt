package io.droyagent.app.nativeui

import io.droyagent.app.nativeui.data.NativeSseReader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeSseReaderTest {
    @Test fun heartbeatsAndMultilineFramesUseEventBoundaries() {
        val frames = mutableListOf<String>()
        NativeSseReader.read(StringReader(": ping\r\n\r\nevent: message\r\ndata: {\r\ndata: \"type\":\"snapshot\"}\r\n\r\n").buffered(), frames::add)
        assertEquals(listOf("{\n\"type\":\"snapshot\"}"), frames)
    }

    @Test fun incompleteFrameAtDisconnectIsNotApplied() {
        val frames = mutableListOf<String>()
        NativeSseReader.read(StringReader("data: {\"type\":\"event\"}").buffered(), frames::add)
        assertEquals(emptyList<String>(), frames)
    }
}
