package io.droyagent.terminal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public final class RemoteTerminalSessionTest {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String row(RemoteTerminalSession session, int row) {
        return session.getEmulator().getScreen().getSelectedText(0, row, session.getEmulator().mColumns - 1, row);
    }
    private static final class Recorder implements RemoteTerminalSession.Listener {
        final List<byte[]> inputs = new ArrayList<>();
        final List<String> sizes = new ArrayList<>(), copies = new ArrayList<>(), titles = new ArrayList<>();
        int bells, pastes;
        @Override public void onInput(long epoch, byte[] utf8) { inputs.add(utf8); }
        @Override public void onResize(long epoch, int columns, int rows, int width, int height) { sizes.add(epoch + ":" + columns + "x" + rows); }
        @Override public void onCopySelection(String text) { copies.add(text); }
        @Override public void onPasteRequested(long epoch) { pastes++; }
        @Override public void onTitleChanged(long epoch, String title) { titles.add(title); }
        @Override public void onBell(long epoch) { bells++; }
    }

    @Test public void recoveredQueriesBellsAndTitlesDoNotRepeatSideEffects() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        assertTrue(session.resetSnapshot(1, bytes("Hello\u001b[6n\u0007\u001b]0;restored\u0007"), 20, 4));
        assertFalse(session.isWritable()); assertTrue(recorder.inputs.isEmpty()); assertEquals(0, recorder.bells); assertTrue(recorder.titles.isEmpty());
        session.setWritable(1, true);
        session.appendOutput(1, bytes("\u001b[6n\u0007\u001b]0;live\u0007"));
        assertEquals(1, recorder.inputs.size()); assertEquals("\u001b[1;6R", new String(recorder.inputs.get(0), StandardCharsets.UTF_8));
        assertEquals(1, recorder.bells); assertEquals(List.of("live"), recorder.titles);
    }

    @Test public void snapshotsAreEpochBoundedAndDropOldOutputAndControl() {
        RemoteTerminalSession session = new RemoteTerminalSession();
        session.resetSnapshot(10, bytes("old"), 20, 4); session.setWritable(10, true);
        session.resetSnapshot(11, bytes("new"), 20, 4);
        assertFalse(session.appendOutput(10, bytes("stale"))); assertFalse(session.setWritable(10, true));
        assertFalse(session.resetSnapshot(10, bytes("bad"), 20, 4)); assertFalse(session.resetSnapshot(11, bytes("duplicate"), 20, 4));
        assertEquals("new", row(session, 0)); assertFalse(session.isWritable());
    }

    @Test public void utf8AcrossChunksKeepsWideCharactersAndCombiningMarks() {
        RemoteTerminalSession session = new RemoteTerminalSession(); session.resetSnapshot(1, new byte[0], 20, 4);
        byte[] text = bytes("中文🙂e\u0301");
        for (byte value : text) session.appendOutput(1, new byte[]{value});
        assertEquals("中文🙂e\u0301", row(session, 0)); assertEquals(7, session.getEmulator().getCursorCol());
    }

    @Test public void ansiCarriageReturnAndAlternateScreenUseRealTerminalState() {
        RemoteTerminalSession session = new RemoteTerminalSession(); session.resetSnapshot(1, bytes("10%\r80%"), 20, 4);
        assertEquals("80%", row(session, 0));
        session.appendOutput(1, bytes("\u001b[?1049h\u001b[HALT")); assertTrue(session.getEmulator().isAlternateBufferActive()); assertEquals("ALT", row(session, 0));
        session.appendOutput(1, bytes("\u001b[?1049l")); assertFalse(session.getEmulator().isAlternateBufferActive()); assertEquals("80%", row(session, 0));
    }

    @Test public void inputsAreCopiedAndNeverEchoedLocally() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(1, new byte[0], 20, 4); session.setWritable(1, true);
        byte[] input = bytes("input"); session.write(input, 0, input.length); Arrays.fill(input, (byte)'x');
        assertEquals("input", new String(recorder.inputs.get(0), StandardCharsets.UTF_8)); assertEquals("", row(session, 0));
        session.writeCodePoint(true, 0x1f642); assertEquals("\u001b🙂", new String(recorder.inputs.get(1), StandardCharsets.UTF_8));
    }

    @Test public void composingCommitAndBracketedPasteAreSingleImmutableChunks() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(1, bytes("\u001b[?2004h"), 20, 4); session.setWritable(1, true);
        session.beginInputBatch(); session.writeCodePoint(false, '中'); session.writeCodePoint(false, '文'); session.endInputBatch();
        assertEquals("中文", new String(recorder.inputs.get(0), StandardCharsets.UTF_8));
        assertTrue(session.paste(1, "你好\n")); assertEquals(2, recorder.inputs.size());
        assertEquals("\u001b[200~你好\r\u001b[201~", new String(recorder.inputs.get(1), StandardCharsets.UTF_8));
    }

    @Test public void readOnlyAndReleaseCannotSendOrReadClipboard() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(1, new byte[0], 20, 4); session.write("blocked"); session.pasteFromClipboard();
        assertTrue(recorder.inputs.isEmpty()); assertEquals(0, recorder.pastes);
        session.setWritable(1, true); session.copySelection("explicit"); session.pasteFromClipboard();
        assertEquals(List.of("explicit"), recorder.copies); assertEquals(1, recorder.pastes);
        session.onCopyTextToClipboard("remote"); session.onPasteTextFromClipboard();
        assertEquals(List.of("explicit"), recorder.copies); assertEquals(1, recorder.pastes);
        session.release(); session.write("closed"); assertFalse(session.setWritable(1, true)); assertFalse(session.appendOutput(1, bytes("closed")));
        assertTrue(recorder.inputs.isEmpty());
    }

    @Test public void viewportResizeWaitsForActualPtyDimensionsAndDoesNotFeedback() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(4, bytes("content"), 80, 24); session.updateSize(40, 12, 9, 18);
        assertEquals(80, session.getEmulator().mColumns); assertTrue(recorder.sizes.isEmpty());
        session.setWritable(4, true); assertEquals(List.of("4:40x12"), recorder.sizes);
        session.resizeRemote(4, 40, 12); session.setWritable(4, true);
        assertEquals(40, session.getEmulator().mColumns); assertEquals(1, recorder.sizes.size());
        session.resizeRemote(4, 60, 15); session.setWritable(4, false); session.setWritable(4, true);
        assertEquals(List.of("4:40x12", "4:40x12"), recorder.sizes);
        assertFalse(session.resizeRemote(3, 20, 4)); assertEquals(60, session.getEmulator().mColumns);
    }

    @Test public void oversizedOrInvalidFramesDoNotReplaceCommittedScreen() {
        RemoteTerminalSession session = new RemoteTerminalSession(); session.resetSnapshot(1, bytes("kept"), 20, 4);
        assertThrows(IllegalArgumentException.class, () -> session.resetSnapshot(2, bytes("bad"), 513, 4));
        assertThrows(IllegalArgumentException.class, () -> session.appendOutput(1, new byte[RemoteTerminalSession.MAX_CHUNK_BYTES + 1]));
        assertEquals("kept", row(session, 0)); assertEquals(1, session.getEpoch());
    }

    @Test public void failedBatchDoesNotSendItsPrefix() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(1, new byte[0], 20, 4); session.setWritable(1, true);
        session.beginInputBatch(); session.write("prefix");
        assertThrows(IllegalArgumentException.class, () -> session.write(new byte[RemoteTerminalSession.MAX_CHUNK_BYTES], 0, RemoteTerminalSession.MAX_CHUNK_BYTES));
        session.endInputBatch(); assertTrue(recorder.inputs.isEmpty());
    }

    @Test public void oversizedSingleWriteAlsoInvalidatesItsWholeBatch() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(1, new byte[0], 20, 4); session.setWritable(1, true);
        session.beginInputBatch(); session.write("prefix");
        assertThrows(IllegalArgumentException.class, () -> session.write(new byte[RemoteTerminalSession.MAX_CHUNK_BYTES + 1], 0, RemoteTerminalSession.MAX_CHUNK_BYTES + 1));
        session.endInputBatch(); assertTrue(recorder.inputs.isEmpty());
    }

    @Test public void cellGeometryUpdatesWithoutInventingAPtyResize() {
        RemoteTerminalSession session = new RemoteTerminalSession(); Recorder recorder = new Recorder(); session.setListener(recorder);
        session.resetSnapshot(1, new byte[0], 20, 4); session.updateSize(20, 4, 7, 15); session.setWritable(1, true);
        session.appendOutput(1, bytes("\u001b[16t"));
        assertEquals("\u001b[6;15;7t", new String(recorder.inputs.get(0), StandardCharsets.UTF_8));
        assertTrue(recorder.sizes.isEmpty());
    }

    @Test public void parserAndResizeStayOnTheirOwningThread() throws Exception {
        RemoteTerminalSession session = new RemoteTerminalSession(); session.resetSnapshot(1, bytes("kept"), 20, 4);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> { try { session.appendOutput(1, bytes("wrong")); } catch (Throwable error) { failure.set(error); } });
        other.start(); other.join(); assertTrue(failure.get() instanceof IllegalStateException); assertEquals("kept", row(session, 0));
    }
}
