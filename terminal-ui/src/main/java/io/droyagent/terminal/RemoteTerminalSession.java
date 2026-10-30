package io.droyagent.terminal;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalSessionClient;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalViewSession;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Objects;

/** Folds one remote attachment on its owning UI thread. It never creates a process or echoes input. */
public final class RemoteTerminalSession extends TerminalOutput implements TerminalViewSession {
    public interface Listener {
        void onInput(long epoch, byte[] utf8);
        void onResize(long epoch, int columns, int rows, int cellWidth, int cellHeight);
        default void onTitleChanged(long epoch, String title) {}
        default void onBell(long epoch) {}
        default void onCopySelection(String text) {}
        default void onPasteRequested(long epoch) {}
        default void onCursorStateChanged(boolean enabled) {}
    }

    public static final int MAX_DIMENSION = 512;
    public static final int MIN_DIMENSION = 2;
    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;
    private static final int TRANSCRIPT_ROWS = 2048;
    private final Thread owner = Thread.currentThread();
    private Listener listener;
    private TerminalEmulator emulator;
    private long epoch = -1;
    private boolean writable;
    private boolean replaying;
    private boolean released;
    private ByteArrayOutputStream inputBatch;
    private int batchDepth;
    private long batchEpoch;
    private boolean batchFailed;
    private int cellWidth = 1, cellHeight = 1;
    private int viewportColumns = 80, viewportRows = 24;
    private boolean viewportKnown;
    private int reportedColumns = -1, reportedRows = -1;
    private int foreground = 0xffe6e1e5, background = 0xff1c1b1f, cursor = 0xffd0bcff;
    private final TerminalSessionClient client = new TerminalSessionClient() {
        @Override public void onTerminalCursorStateChange(boolean enabled) { if (listener != null) listener.onCursorStateChanged(enabled); }
        @Override public Integer getTerminalCursorStyle() { return null; }
        // ANSI/OSC diagnostics can contain terminal content and never enter application logs.
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception error) {}
        @Override public void logStackTrace(String tag, Exception error) {}
    };

    public RemoteTerminalSession() { emulator = create(80, 24); }

    public void setListener(Listener value) { own(); listener = value; }
    public long getEpoch() { return epoch; }

    /** A snapshot is presentation recovery; its device queries must not run a second time. */
    public boolean resetSnapshot(long nextEpoch, byte[] ansi, int columns, int rows) {
        own(); dimensions(columns, rows); bytes(ansi, MAX_SNAPSHOT_BYTES);
        if (released || nextEpoch < 0 || nextEpoch <= epoch) return false;
        epoch = nextEpoch; writable = false; replaying = true;
        reportedColumns = columns; reportedRows = rows;
        try {
            emulator = create(columns, rows);
            emulator.append(ansi, ansi.length);
        } finally { replaying = false; }
        return true;
    }

    public boolean appendOutput(long frameEpoch, byte[] ansi) {
        own(); bytes(ansi, MAX_CHUNK_BYTES);
        if (released || frameEpoch != epoch || epoch < 0) return false;
        emulator.append(ansi, ansi.length);
        return true;
    }

    public boolean setWritable(long frameEpoch, boolean value) {
        own();
        if (released || frameEpoch != epoch || epoch < 0) return false;
        boolean gained = !writable && value;
        writable = value;
        if (gained) reportSize(true);
        return true;
    }

    public boolean resizeRemote(long frameEpoch, int columns, int rows) {
        own();
        if (released || frameEpoch != epoch || epoch < 0) return false;
        dimensions(columns, rows);
        emulator.resize(columns, rows, cellWidth, cellHeight);
        return true;
    }

    @Override public boolean isWritable() { return writable && !released && epoch >= 0; }
    @Override public TerminalEmulator getEmulator() { own(); return emulator; }

    @Override public void updateSize(int columns, int rows, int width, int height) {
        own(); dimensions(columns, rows);
        if (width < 1 || height < 1) throw new IllegalArgumentException("INVALID_TERMINAL_CELL");
        cellWidth = width; cellHeight = height;
        viewportColumns = columns; viewportRows = rows;
        viewportKnown = true;
        emulator.resize(emulator.mColumns, emulator.mRows, width, height);
        reportSize(false);
    }

    private void reportSize(boolean gainedControl) {
        if (isWritable() && !replaying && listener != null && viewportKnown
                && (reportedColumns != viewportColumns || reportedRows != viewportRows
                || gainedControl && (viewportColumns != emulator.mColumns || viewportRows != emulator.mRows))) {
            reportedColumns = viewportColumns; reportedRows = viewportRows;
            listener.onResize(epoch, reportedColumns, reportedRows, cellWidth, cellHeight);
        }
    }

    @Override public void write(byte[] data, int offset, int count) {
        own();
        try { Objects.checkFromIndexSize(offset, count, data.length); }
        catch (RuntimeException failure) { if (batchDepth > 0) batchFailed = true; throw failure; }
        if (count > MAX_CHUNK_BYTES) {
            if (batchDepth > 0) batchFailed = true;
            throw new IllegalArgumentException("TERMINAL_INPUT_TOO_LARGE");
        }
        if (count == 0 || !isWritable() || replaying || listener == null) return;
        if (batchDepth > 0) {
            if (inputBatch.size() + count > MAX_CHUNK_BYTES) {
                batchFailed = true;
                throw new IllegalArgumentException("TERMINAL_INPUT_TOO_LARGE");
            }
            inputBatch.write(data, offset, count);
        } else listener.onInput(epoch, Arrays.copyOfRange(data, offset, offset + count));
    }

    @Override public void writeCodePoint(boolean prependEscape, int codePoint) {
        own();
        if (!Character.isValidCodePoint(codePoint) || codePoint >= 0xd800 && codePoint <= 0xdfff)
            codePoint = TerminalEmulator.UNICODE_REPLACEMENT_CHAR;
        byte[] utf8 = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
        if (prependEscape) {
            byte[] escaped = new byte[utf8.length + 1]; escaped[0] = 27;
            System.arraycopy(utf8, 0, escaped, 1, utf8.length); utf8 = escaped;
        }
        write(utf8, 0, utf8.length);
    }

    public boolean paste(long frameEpoch, String text) {
        own(); Objects.requireNonNull(text);
        if (released || frameEpoch != epoch || !isWritable()) return false;
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_CHUNK_BYTES - 12)
            throw new IllegalArgumentException("TERMINAL_INPUT_TOO_LARGE");
        beginInputBatch();
        try { emulator.paste(text); }
        finally { endInputBatch(); }
        return true;
    }

    @Override public void beginInputBatch() {
        own();
        if (batchDepth++ == 0) { inputBatch = new ByteArrayOutputStream(); batchEpoch = epoch; batchFailed = false; }
    }

    /** A composing commit or bracketed paste is admitted as one immutable input chunk. */
    @Override public void endInputBatch() {
        own();
        if (batchDepth == 0) throw new IllegalStateException("TERMINAL_INPUT_BATCH_NOT_STARTED");
        if (--batchDepth == 0) {
            byte[] data = inputBatch.toByteArray(); inputBatch = null;
            if (!batchFailed && data.length > 0 && batchEpoch == epoch && isWritable() && listener != null)
                listener.onInput(epoch, data);
        }
    }

    public void abortInputBatch() { own(); if (batchDepth > 0) batchFailed = true; }

    public void setColors(int text, int surface, int caret) {
        own(); foreground = text; background = surface; cursor = caret; applyColors();
    }

    public void release() { own(); writable = false; released = true; listener = null; }

    @Override public void copySelection(String text) { own(); if (!released && listener != null) listener.onCopySelection(text); }
    @Override public void pasteFromClipboard() { own(); if (isWritable() && listener != null) listener.onPasteRequested(epoch); }

    // OSC 52 originates in remote program output, unlike the explicit selection toolbar above.
    @Override public void onCopyTextToClipboard(String text) {}
    @Override public void onPasteTextFromClipboard() {}
    @Override public void titleChanged(String oldTitle, String newTitle) { if (!replaying && !released && listener != null) listener.onTitleChanged(epoch, newTitle); }
    @Override public void onBell() { if (!replaying && !released && listener != null) listener.onBell(epoch); }
    @Override public void onColorsChanged() {}

    private TerminalEmulator create(int columns, int rows) {
        TerminalEmulator value = new TerminalEmulator(this, columns, rows, cellWidth, cellHeight, TRANSCRIPT_ROWS, client);
        value.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = foreground;
        value.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = background;
        value.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cursor;
        return value;
    }
    private void applyColors() {
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = foreground;
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = background;
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = cursor;
    }
    private void own() { if (Thread.currentThread() != owner) throw new IllegalStateException("TERMINAL_OWNER_THREAD_REQUIRED"); }
    private static void dimensions(int columns, int rows) {
        if (!canRenderDimensions(columns, rows))
            throw new IllegalArgumentException("INVALID_TERMINAL_DIMENSIONS");
    }
    public static boolean canRenderDimensions(int columns, int rows) {
        return columns >= MIN_DIMENSION && columns <= MAX_DIMENSION && rows >= MIN_DIMENSION && rows <= MAX_DIMENSION;
    }
    private static void bytes(byte[] value, int maximum) {
        if (value == null || value.length > maximum) throw new IllegalArgumentException("TERMINAL_OUTPUT_TOO_LARGE");
    }
}
