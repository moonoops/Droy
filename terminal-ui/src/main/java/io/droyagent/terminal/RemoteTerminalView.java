package io.droyagent.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.text.InputType;
import android.os.Looper;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.CorrectionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethodManager;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;
import com.termux.view.TerminalViewSession;

/** Native canvas/IME presentation. All network and terminal ownership stay with the caller. */
public final class RemoteTerminalView extends TerminalView {
    public interface Listener {
        void onInput(long epoch, byte[] utf8);
        void onResize(long epoch, int columns, int rows, int cellWidth, int cellHeight);
        default void onTitleChanged(long epoch, String title) {}
        default void onBell(long epoch) {}
        default void onInteractionError(String code) {}
    }

    public static boolean canRenderDimensions(int columns, int rows) { return RemoteTerminalSession.canRenderDimensions(columns, rows); }

    private final RemoteTerminalSession session;
    private Listener listener;
    private boolean released;
    private boolean controlKey, altKey;
    private float textSizeSp = 13;
    private long inputGeneration;
    private EpochInputConnection inputConnection;
    private boolean inputRestartRequired;
    private static final int MAX_CLIPBOARD_CHARACTERS = 200_000;

    public RemoteTerminalView(Context context) {
        super(context, null);
        main();
        setTerminalViewClient(new Client());
        session = new RemoteTerminalSession();
        session.setListener(new RemoteTerminalSession.Listener() {
            @Override public void onInput(long epoch, byte[] data) { if (listener != null) listener.onInput(epoch, data); }
            @Override public void onResize(long epoch, int columns, int rows, int width, int height) {
                if (listener != null) listener.onResize(epoch, columns, rows, width, height);
            }
            @Override public void onTitleChanged(long epoch, String title) { if (listener != null) listener.onTitleChanged(epoch, title); }
            @Override public void onBell(long epoch) { if (listener != null) listener.onBell(epoch); }
            @Override public void onCopySelection(String text) {
                ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
                if (text.length() > MAX_CLIPBOARD_CHARACTERS) { interactionError("terminal/clipboard-too-large"); return; }
                if (clipboard != null) {
                    try { clipboard.setPrimaryClip(ClipData.newPlainText(getContext().getString(R.string.copy_text), text)); }
                    catch (SecurityException denied) { interactionError("terminal/clipboard-unavailable"); }
                }
            }
            @Override public void onPasteRequested(long epoch) { pasteFromClipboard(epoch); }
            @Override public void onCursorStateChanged(boolean enabled) {
                if (!released && hasVisibleSurface()) setTerminalCursorBlinkerState(session.isWritable(), true);
            }
        });
        setTextSizeSp(textSizeSp);
        setTypeface(Typeface.MONOSPACE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        setTerminalCursorBlinkerRate(600);
        attachSession(session);
    }

    public void setListener(Listener value) { main(); if (!released) listener = value; }

    public boolean resetSnapshot(long epoch, byte[] ansi, int columns, int rows) {
        main();
        if (released || !session.resetSnapshot(epoch, ansi, columns, rows)) return false;
        retireInputConnection(); inputRestartRequired = true;
        controlKey = altKey = false;
        stopUiWork();
        mEmulator = session.getEmulator();
        setTopRow(0);
        scrollTo(0, 0);
        updateSize();
        onScreenUpdated();
        return true;
    }

    public boolean appendOutput(long epoch, byte[] ansi) {
        main();
        if (released || !session.appendOutput(epoch, ansi)) return false;
        onScreenUpdated();
        return true;
    }

    public boolean resizeRemote(long epoch, int columns, int rows) {
        main();
        if (released || !session.resizeRemote(epoch, columns, rows)) return false;
        onScreenUpdated();
        return true;
    }

    public boolean setWritable(long epoch, boolean writable) {
        main();
        boolean previous = session.isWritable();
        if (released || !session.setWritable(epoch, writable)) return false;
        setTerminalCursorBlinkerState(writable && hasVisibleSurface(), true);
        if (previous != writable) {
            retireInputConnection(); controlKey = altKey = false;
        }
        if ((previous != writable || inputRestartRequired) && hasFocus()) {
            InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
            if (keyboard != null) {
                if (!writable) keyboard.hideSoftInputFromWindow(getWindowToken(), 0);
                keyboard.restartInput(this);
            }
        }
        inputRestartRequired = false;
        invalidate();
        return true;
    }

    public void setTerminalColors(int foreground, int background, int cursor) {
        main(); session.setColors(foreground, background, cursor); setBackgroundColor(background); invalidate();
    }

    public void setTextSizeSp(float value) {
        main();
        if (!Float.isFinite(value) || value < 8 || value > 32) throw new IllegalArgumentException("INVALID_TERMINAL_TEXT_SIZE");
        textSizeSp = value;
        super.setTextSize(Math.max(1, Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics()))));
    }

    /** Optional native toolbar modifiers; they never dispatch independently of a key. */
    public void setModifiers(boolean control, boolean alt) { main(); controlKey = control; altKey = alt; }
    public boolean pasteText(long epoch, String text) { main(); return !released && session.paste(epoch, text); }
    public long getEpoch() { main(); return session.getEpoch(); }
    public boolean isWritable() { main(); return !released && session.isWritable(); }

    /** Called by AndroidView.onRelease or the page's disposal path; never closes the remote terminal. */
    public void release() {
        main();
        if (released) return;
        stopUiWork();
        retireInputConnection();
        session.release();
        listener = null; released = true;
        controlKey = altKey = false;
        clearFocus();
    }

    @Override public boolean onCheckIsTextEditor() { return !released && session != null && session.isWritable(); }
    @Override public InputConnection onCreateInputConnection(EditorInfo attributes) {
        if (!onCheckIsTextEditor()) return null;
        retireInputConnection();
        InputConnection value = super.onCreateInputConnection(attributes);
        attributes.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        attributes.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        inputConnection = new EpochInputConnection(value);
        return inputConnection;
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (session == null) return;
        if (visibility != VISIBLE) { stopUiWork(); retireInputConnection(); inputRestartRequired = true; }
        else if (!released) setTerminalCursorBlinkerState(session.isWritable() && hasVisibleSurface(), true);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!released && session != null) setTerminalCursorBlinkerState(session.isWritable() && hasVisibleSurface(), true);
    }

    @Override protected void onDetachedFromWindow() {
        if (session != null) { retireInputConnection(); inputRestartRequired = true; }
        super.onDetachedFromWindow();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (session == null) return;
        if (!hasVisibleSurface()) { stopUiWork(); retireInputConnection(); inputRestartRequired = true; }
        else if (!released) setTerminalCursorBlinkerState(session.isWritable(), true);
    }

    private boolean hasVisibleSurface() { return isAttachedToWindow() && getWindowVisibility() == VISIBLE && isShown(); }

    @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        if (session != null && !focused) { retireInputConnection(); inputRestartRequired = true; }
    }

    private void pasteFromClipboard(long epoch) {
        if (released || !session.isWritable() || session.getEpoch() != epoch) return;
        ClipboardManager clipboard = getContext().getSystemService(ClipboardManager.class);
        ClipData data;
        try { data = clipboard == null ? null : clipboard.getPrimaryClip(); }
        catch (SecurityException denied) { interactionError("terminal/clipboard-unavailable"); return; }
        if (data != null && data.getItemCount() > 0) {
            CharSequence text = data.getItemAt(0).coerceToText(getContext());
            if (text != null) {
                try { session.paste(epoch, text.toString()); }
                catch (IllegalArgumentException error) { if (!inputLimit(error)) throw error; }
            }
        }
    }

    private boolean inputLimit(IllegalArgumentException error) {
        if (!"TERMINAL_INPUT_TOO_LARGE".equals(error.getMessage())) return false;
        interactionError("terminal/input-too-large");
        return true;
    }
    private void interactionError(String code) { if (listener != null) listener.onInteractionError(code); }

    private void retireInputConnection() {
        inputGeneration++;
        if (inputConnection != null) inputConnection.retire();
        inputConnection = null;
        clearInputState();
    }

    /** Interface defaults dispatch back through this gate, including newer TextAttribute overloads. */
    private final class EpochInputConnection implements InputConnection {
        private final InputConnection delegate;
        private final long epoch = session.getEpoch(), generation = inputGeneration;
        private boolean closed;
        EpochInputConnection(InputConnection delegate) { this.delegate = delegate; }
        private boolean current() {
            return Looper.myLooper() == Looper.getMainLooper() && !closed && !released
                    && hasVisibleSurface() && hasFocus()
                    && inputConnection == this && inputGeneration == generation
                    && session.getEpoch() == epoch && session.isWritable();
        }
        void retire() {
            closed = true;
            if (delegate instanceof BaseInputConnection connection) {
                connection.getEditable().clear(); connection.getEditable().clearSpans();
            }
        }
        private boolean input(java.util.function.BooleanSupplier action) {
            if (!current()) return false;
            session.beginInputBatch();
            try { return action.getAsBoolean(); }
            catch (RuntimeException error) {
                session.abortInputBatch();
                if (error instanceof IllegalArgumentException invalid && inputLimit(invalid)) return false;
                throw error;
            } finally { session.endInputBatch(); }
        }
        @Override public boolean commitText(CharSequence text, int cursor) { return input(() -> delegate.commitText(text, cursor)); }
        @Override public boolean finishComposingText() { return input(delegate::finishComposingText); }
        @Override public boolean deleteSurroundingText(int before, int after) { return input(() -> delegate.deleteSurroundingText(before, after)); }
        @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) { return input(() -> delegate.deleteSurroundingTextInCodePoints(before, after)); }
        @Override public boolean sendKeyEvent(KeyEvent event) { return input(() -> delegate.sendKeyEvent(event)); }
        @Override public boolean performEditorAction(int action) { return input(() -> delegate.performEditorAction(action)); }
        @Override public boolean performContextMenuAction(int action) { return input(() -> delegate.performContextMenuAction(action)); }
        @Override public boolean commitCompletion(CompletionInfo value) { return input(() -> delegate.commitCompletion(value)); }
        @Override public boolean commitCorrection(CorrectionInfo value) { return input(() -> delegate.commitCorrection(value)); }
        @Override public boolean commitContent(InputContentInfo info, int flags, Bundle options) { return current() && delegate.commitContent(info, flags, options); }
        @Override public boolean performPrivateCommand(String action, Bundle data) { return current() && delegate.performPrivateCommand(action, data); }
        @Override public boolean setComposingText(CharSequence text, int cursor) { return current() && delegate.setComposingText(text, cursor); }
        @Override public boolean setComposingRegion(int start, int end) { return current() && delegate.setComposingRegion(start, end); }
        @Override public boolean setSelection(int start, int end) { return current() && delegate.setSelection(start, end); }
        @Override public boolean beginBatchEdit() { return current() && delegate.beginBatchEdit(); }
        @Override public boolean endBatchEdit() { return current() && delegate.endBatchEdit(); }
        @Override public boolean clearMetaKeyStates(int states) { return current() && delegate.clearMetaKeyStates(states); }
        @Override public boolean reportFullscreenMode(boolean fullscreen) { return current() && delegate.reportFullscreenMode(fullscreen); }
        @Override public boolean requestCursorUpdates(int mode) { return current() && delegate.requestCursorUpdates(mode); }
        @Override public CharSequence getTextBeforeCursor(int length, int flags) { return current() ? delegate.getTextBeforeCursor(length, flags) : null; }
        @Override public CharSequence getTextAfterCursor(int length, int flags) { return current() ? delegate.getTextAfterCursor(length, flags) : null; }
        @Override public CharSequence getSelectedText(int flags) { return current() ? delegate.getSelectedText(flags) : null; }
        @Override public int getCursorCapsMode(int modes) { return current() ? delegate.getCursorCapsMode(modes) : 0; }
        @Override public ExtractedText getExtractedText(ExtractedTextRequest request, int flags) { return current() ? delegate.getExtractedText(request, flags) : null; }
        @Override public Handler getHandler() { return RemoteTerminalView.this.getHandler(); }
        @Override public void closeConnection() {
            if (Looper.myLooper() != Looper.getMainLooper()) return;
            if (inputConnection == this) retireInputConnection(); else retire();
            delegate.closeConnection();
        }
    }

    private static void main() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("TERMINAL_MAIN_THREAD_REQUIRED");
    }

    private final class Client implements TerminalViewClient {
        @Override public float onScale(float scale) {
            if (scale > 1.1f || scale < .9f) { setTextSizeSp(Math.max(8, Math.min(32, textSizeSp * scale))); return 1; }
            return scale;
        }
        @Override public void onSingleTapUp(MotionEvent event) {
            if (session.isWritable() && !released) {
                requestFocus();
                InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
                if (keyboard != null) keyboard.showSoftInput(RemoteTerminalView.this, InputMethodManager.SHOW_IMPLICIT);
            }
        }
        @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
        @Override public boolean shouldEnforceCharBasedInput() { return false; }
        @Override public boolean shouldUseCtrlSpaceWorkaround() { return true; }
        @Override public boolean isTerminalViewSelected() { return hasFocus() && session.isWritable() && !released; }
        @Override public void copyModeChanged(boolean copying) {}
        @Override public boolean onKeyDown(int code, KeyEvent event, TerminalViewSession value) { return (released || !session.isWritable()) && !event.isSystem(); }
        @Override public boolean onKeyUp(int code, KeyEvent event) { return (released || !session.isWritable()) && !event.isSystem(); }
        @Override public boolean onLongPress(MotionEvent event) { return released; }
        @Override public boolean readControlKey() { return controlKey; }
        @Override public boolean readAltKey() { return altKey; }
        @Override public boolean readShiftKey() { return false; }
        @Override public boolean readFnKey() { return false; }
        @Override public boolean onCodePoint(int codePoint, boolean control, TerminalViewSession value) { return released || !session.isWritable(); }
        @Override public void onEmulatorSet() { if (session != null && !released) setTerminalCursorBlinkerState(session.isWritable() && hasVisibleSurface(), true); }
        @Override public void logError(String tag, String message) {}
        @Override public void logWarn(String tag, String message) {}
        @Override public void logInfo(String tag, String message) {}
        @Override public void logDebug(String tag, String message) {}
        @Override public void logVerbose(String tag, String message) {}
        @Override public void logStackTraceWithMessage(String tag, String message, Exception error) {}
        @Override public void logStackTrace(String tag, Exception error) {}
    }
}
