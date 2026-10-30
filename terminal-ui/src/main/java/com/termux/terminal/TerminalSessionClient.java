// Droy modifications (2026): remote-only rendering integration; see terminal-ui/README.md.
package com.termux.terminal;

/** Emulator callbacks only. Local-process callbacks from upstream are deliberately absent. */
public interface TerminalSessionClient {
    void onTerminalCursorStateChange(boolean state);
    Integer getTerminalCursorStyle();
    void logError(String tag, String message);
    void logWarn(String tag, String message);
    void logInfo(String tag, String message);
    void logDebug(String tag, String message);
    void logVerbose(String tag, String message);
    void logStackTraceWithMessage(String tag, String message, Exception error);
    void logStackTrace(String tag, Exception error);
}
