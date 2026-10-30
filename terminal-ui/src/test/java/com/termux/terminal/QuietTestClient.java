package com.termux.terminal;

/** Invalid escape tests must not call the Android Log stub on a host JVM. */
final class QuietTestClient implements TerminalSessionClient {
    @Override public void onTerminalCursorStateChange(boolean enabled) {}
    @Override public Integer getTerminalCursorStyle() { return null; }
    @Override public void logError(String tag, String message) {}
    @Override public void logWarn(String tag, String message) {}
    @Override public void logInfo(String tag, String message) {}
    @Override public void logDebug(String tag, String message) {}
    @Override public void logVerbose(String tag, String message) {}
    @Override public void logStackTraceWithMessage(String tag, String message, Exception error) {}
    @Override public void logStackTrace(String tag, Exception error) {}
}
