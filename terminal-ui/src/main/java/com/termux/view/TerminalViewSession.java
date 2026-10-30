package com.termux.view;

import com.termux.terminal.TerminalEmulator;

/** Presentation contract without a local process, file descriptor or PTY. */
public interface TerminalViewSession {
    TerminalEmulator getEmulator();
    void updateSize(int columns, int rows, int cellWidth, int cellHeight);
    void write(byte[] data, int offset, int count);
    void write(String text);
    void writeCodePoint(boolean prependEscape, int codePoint);
    void beginInputBatch();
    void endInputBatch();
    boolean isWritable();
    void copySelection(String text);
    void pasteFromClipboard();
}
