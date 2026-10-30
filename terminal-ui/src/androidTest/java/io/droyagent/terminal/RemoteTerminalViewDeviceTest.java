package io.droyagent.terminal;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class RemoteTerminalViewDeviceTest {
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static String utf8(byte[] value) { return new String(value, StandardCharsets.UTF_8); }
    private static final class Recorder implements RemoteTerminalView.Listener {
        final List<byte[]> input = new ArrayList<>();
        final List<String> sizes = new ArrayList<>();
        @Override public void onInput(long epoch, byte[] utf8) { input.add(utf8); }
        @Override public void onResize(long epoch, int columns, int rows, int width, int height) { sizes.add(columns + "x" + rows); }
    }

    @Before public void requireIsolatedEmulator() {
        assertEquals("Use -e nativeTerminalFixture true on a dedicated emulator", "true",
                InstrumentationRegistry.getArguments().getString("nativeTerminalFixture"));
        assertTrue(Build.MODEL.contains("sdk_gphone") || Build.FINGERPRINT.contains("emulator"));
    }

    @Test public void composingChineseEmojiAndHardwareKeysUseOwnedNativeInput() {
        try (ActivityScenario<TerminalHarnessActivity> scene = ActivityScenario.launch(TerminalHarnessActivity.class)) {
            awaitWindow(scene);
            scene.onActivity(activity -> {
                RemoteTerminalView view = activity.terminal; Recorder recorder = new Recorder(); view.setListener(recorder);
                view.resetSnapshot(1, new byte[0], 80, 24); view.setWritable(1, true); view.requestFocus();
                EditorInfo info = new EditorInfo(); InputConnection connection = view.onCreateInputConnection(info);
                assertNotNull(connection); assertEquals(InputType.TYPE_CLASS_TEXT, info.inputType & InputType.TYPE_MASK_CLASS);
                assertTrue((info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
                connection.setComposingText("中文", 1); assertTrue(recorder.input.isEmpty());
                connection.commitText("中文", 1); assertEquals("中文", utf8(recorder.input.get(0)));
                connection.commitText("🙂", 1); assertEquals("🙂", utf8(recorder.input.get(1)));
                view.onKeyDown(KeyEvent.KEYCODE_DEL, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL));
                assertEquals("\u007f", utf8(recorder.input.get(2)));
                view.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
                assertEquals("\u001b[A", utf8(recorder.input.get(3)));
                view.onKeyDown(KeyEvent.KEYCODE_C, new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON));
                assertEquals("\u0003", utf8(recorder.input.get(4)));
                view.onKeyDown(KeyEvent.KEYCODE_ENTER, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                assertEquals("\r", utf8(recorder.input.get(5)));
                view.setWritable(1, false); connection.commitText("blocked", 1);
                assertEquals(6, recorder.input.size()); assertNull(view.onCreateInputConnection(new EditorInfo()));
                assertFalse(view.onKeyDown(KeyEvent.KEYCODE_BACK, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)));
            });
        }
    }

    @Test public void snapshotCannotReplayQueriesOrChangeClipboardAndOldEpochIsRejected() {
        try (ActivityScenario<TerminalHarnessActivity> scene = ActivityScenario.launch(TerminalHarnessActivity.class)) {
            awaitWindow(scene);
            scene.onActivity(activity -> {
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                ClipData original = clipboard.getPrimaryClip();
                try {
                    clipboard.setPrimaryClip(ClipData.newPlainText("fixture", "owned seed"));
                    RemoteTerminalView view = activity.terminal; Recorder recorder = new Recorder(); view.setListener(recorder);
                    String remote = "\u001b]52;c;" + Base64.encodeToString(bytes("remote text"), Base64.NO_WRAP) + "\u0007\u001b[6n";
                    view.resetSnapshot(10, bytes(remote), 80, 24); assertTrue(recorder.input.isEmpty());
                    view.setWritable(10, true); view.appendOutput(10, bytes(remote));
                    assertEquals("owned seed", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    assertEquals(1, recorder.input.size());
                    view.mTermSession.copySelection("explicit copy");
                    assertEquals("explicit copy", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                    view.resetSnapshot(11, bytes("new"), 80, 24);
                    assertFalse(view.appendOutput(10, bytes("stale"))); assertFalse(view.setWritable(10, true));
                    assertEquals("new", view.mEmulator.getScreen().getSelectedText(0, 0, 79, 0));
                } finally {
                    if (original == null) clipboard.clearPrimaryClip(); else clipboard.setPrimaryClip(original);
                }
            });
        }
    }

    @Test public void remoteResizePreservesContentAndReleaseClearsSelectionAndBlinkWork() throws Exception {
        try (ActivityScenario<TerminalHarnessActivity> scene = ActivityScenario.launch(TerminalHarnessActivity.class)) {
            awaitWindow(scene);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            scene.onActivity(activity -> {
                try {
                    RemoteTerminalView view = activity.terminal; Recorder recorder = new Recorder(); view.setListener(recorder);
                    view.setTerminalColors(0xff201a26, 0xfffff7ff, 0xff6750a4);
                    view.resetSnapshot(1, bytes("Native terminal\r\n中文 · ANSI\r\n\u001b[32mready\u001b[0m"), 80, 24);
                    view.setWritable(1, true);
                    int resizeCount = recorder.sizes.size();
                    assertTrue(view.resizeRemote(1, 48, 14)); assertEquals(48, view.mEmulator.mColumns); assertEquals(resizeCount, recorder.sizes.size());
                    assertEquals("Native terminal", view.mEmulator.getScreen().getSelectedText(0, 0, 47, 0));
                    assertEquals("中文 · ANSI", view.mEmulator.getScreen().getSelectedText(0, 1, 47, 1));
                    MotionEvent selection = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 20, 20, 0);
                    try { view.startTextSelectionMode(selection); assertTrue(view.isSelectingText()); }
                    finally { selection.recycle(); }
                    view.release(); assertFalse(view.isSelectingText());
                    Field blinkFlag = com.termux.terminal.TerminalEmulator.class.getDeclaredField("mCursorBlinkingEnabled"); blinkFlag.setAccessible(true);
                    assertFalse(blinkFlag.getBoolean(view.mEmulator));
                    Field handlerField = com.termux.view.TerminalView.class.getDeclaredField("mTerminalCursorBlinkerHandler"); handlerField.setAccessible(true);
                    Field runnableField = com.termux.view.TerminalView.class.getDeclaredField("mTerminalCursorBlinkerRunnable"); runnableField.setAccessible(true);
                    Handler handler = (Handler) handlerField.get(view); Runnable runnable = (Runnable) runnableField.get(view);
                    if (handler != null && runnable != null) assertFalse(handler.hasCallbacks(runnable));
                } catch (Throwable error) { failure.set(error); }
            });
            if (failure.get() != null) throw new AssertionError(failure.get());
            capture(scene, "native-terminal-light", 0xfffff7ff, 0xff201a26);
        }
    }

    @Test public void darkPaletteAndChineseSelectionLabelsFollowTheProvidedContext() throws Exception {
        try (ActivityScenario<TerminalHarnessActivity> scene = ActivityScenario.launch(TerminalHarnessActivity.class)) {
            awaitWindow(scene);
            scene.onActivity(activity -> {
                Configuration config = new Configuration(activity.getResources().getConfiguration());
                config.setLocale(Locale.SIMPLIFIED_CHINESE);
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
                activity.terminal.release();
                RemoteTerminalView view = new RemoteTerminalView(activity.createConfigurationContext(config));
                activity.terminal = view; activity.setContentView(view);
                assertEquals("复制", view.getContext().getString(R.string.copy_text));
                assertEquals("粘贴", view.getContext().getString(R.string.paste_text));
                view.setTerminalColors(0xffe6e0e9, 0xff141218, 0xffd0bcff);
                view.resetSnapshot(1, bytes("原生终端\r\n中文输入 · ANSI\r\n\u001b[32mready\u001b[0m"), 80, 24);
                assertEquals("原生终端", view.mEmulator.getScreen().getSelectedText(0, 0, 79, 0));
                assertEquals("中文输入 · ANSI", view.mEmulator.getScreen().getSelectedText(0, 1, 79, 1));
            });
            capture(scene, "native-terminal-dark-zh", 0xff141218, 0xffe6e0e9);
        }
    }

    @Test public void retiredImeConnectionsCannotSendIntoANewerEpochOrRegainedControl() {
        try (ActivityScenario<TerminalHarnessActivity> scene = ActivityScenario.launch(TerminalHarnessActivity.class)) {
            awaitWindow(scene);
            scene.onActivity(activity -> {
                RemoteTerminalView view = activity.terminal; Recorder recorder = new Recorder(); view.setListener(recorder);
                view.resetSnapshot(1, new byte[0], 80, 24); view.setWritable(1, true); view.requestFocus();
                InputConnection old = view.onCreateInputConnection(new EditorInfo());
                old.setComposingText("old pending", 1);
                view.resetSnapshot(2, new byte[0], 80, 24); view.setWritable(2, true);
                assertFalse(old.finishComposingText()); assertFalse(old.commitText("late", 1));
                assertFalse(old.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)));
                assertFalse(old.deleteSurroundingText(1, 0)); assertNull(old.getTextBeforeCursor(100, 0));
                if (Build.VERSION.SDK_INT >= 33) assertFalse(old.commitText("late attributes", 1, null));
                assertTrue(recorder.input.isEmpty());
                InputConnection current = view.onCreateInputConnection(new EditorInfo());
                current.commitText("fresh", 1); assertEquals("fresh", utf8(recorder.input.get(0)));
                current.setComposingText("old control", 1);
                view.setWritable(2, false); view.setWritable(2, true);
                assertFalse(current.finishComposingText()); assertFalse(current.commitText("late control", 1));
                assertEquals(1, recorder.input.size());
                InputConnection regained = view.onCreateInputConnection(new EditorInfo());
                regained.commitText("regained", 1); assertEquals("regained", utf8(recorder.input.get(1)));
            });
        }
    }

    @Test public void hiddenAttachedViewCannotRestartCursorWorkFromRemoteOutput() {
        try (ActivityScenario<TerminalHarnessActivity> scene = ActivityScenario.launch(TerminalHarnessActivity.class)) {
            awaitWindow(scene);
            scene.onActivity(activity -> {
                RemoteTerminalView view = activity.terminal; view.setListener(new Recorder());
                view.resetSnapshot(1, new byte[0], 80, 24); view.setWritable(1, true);
                assertTrue(view.isAttachedToWindow()); view.setVisibility(View.INVISIBLE);
                view.appendOutput(1, bytes("\u001b[?25l\u001b[?25h"));
                try {
                    Field field = com.termux.view.TerminalView.class.getDeclaredField("mTerminalCursorBlinkerHandler"); field.setAccessible(true);
                    Field runner = com.termux.view.TerminalView.class.getDeclaredField("mTerminalCursorBlinkerRunnable"); runner.setAccessible(true);
                    Handler handler = (Handler) field.get(view); Runnable runnable = (Runnable) runner.get(view);
                    if (handler != null && runnable != null) assertFalse(handler.hasCallbacks(runnable));
                } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            });
        }
    }

    private static void awaitWindow(ActivityScenario<TerminalHarnessActivity> scene) {
        long deadline = SystemClock.elapsedRealtime() + 5000;
        AtomicReference<Boolean> focused = new AtomicReference<>(false);
        do {
            scene.onActivity(activity -> focused.set(activity.hasWindowFocus()));
            if (focused.get()) return;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        assertTrue("Fixture Activity must own the clipboard and popup window", focused.get());
    }

    private static void capture(ActivityScenario<TerminalHarnessActivity> scene, String name,
                                int background, int foreground) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        long stableSince = -1;
        Bitmap screenshot = null;
        SurfaceProbe lastProbe = null;
        try {
            do {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                AtomicReference<SurfaceProbe> probe = new AtomicReference<>();
                scene.onActivity(activity -> probe.set(SurfaceProbe.from(activity.terminal)));
                lastProbe = probe.get();
                if (screenshot != null) screenshot.recycle();
                screenshot = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
                assertNotNull(screenshot);
                boolean matches = lastProbe != null && lastProbe.matches(screenshot, background, foreground);
                long now = SystemClock.elapsedRealtime();
                // An idle View hierarchy can still be behind a compositor transition or an older frame.
                if (!matches) stableSince = -1;
                else if (stableSince < 0) stableSince = now;
                else if (now - stableSince >= 300) {
                    saveScreenshot(name, screenshot);
                    return;
                }
                SystemClock.sleep(50);
            } while (SystemClock.elapsedRealtime() < deadline);
            saveScreenshot(name, screenshot);
            fail("Native terminal never reached its visible palette and two text rows: "
                    + (lastProbe == null ? "view not laid out" : lastProbe.describe(screenshot)));
        } finally {
            if (screenshot != null) screenshot.recycle();
        }
    }

    private static void saveScreenshot(String name, Bitmap screenshot) throws Exception {
        File folder = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null), "terminal-evidence");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        try (var output = new java.io.FileOutputStream(new File(folder, name + ".png"))) { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)); }
    }

    private static final class SurfaceProbe {
        final Rect bounds, firstText, secondText;
        int firstInk, secondInk;

        private SurfaceProbe(Rect bounds, int textWidth, int lineHeight) {
            this.bounds = bounds;
            firstText = new Rect(bounds.left, bounds.top, bounds.left + textWidth, bounds.top + lineHeight);
            secondText = new Rect(firstText); secondText.offset(0, lineHeight);
        }

        static SurfaceProbe from(RemoteTerminalView view) {
            if (!view.isAttachedToWindow() || !view.isShown() || view.getWindowVisibility() != View.VISIBLE
                    || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
            int[] location = new int[2]; view.getLocationOnScreen(location);
            int textWidth = view.getPointX(8), lineHeight = view.getPointY(1) - view.getPointY(0);
            if (textWidth <= 0 || textWidth > view.getWidth() || lineHeight <= 0 || 2 * lineHeight > view.getHeight()) return null;
            return new SurfaceProbe(new Rect(location[0], location[1], location[0] + view.getWidth(),
                    location[1] + view.getHeight()), textWidth, lineHeight);
        }

        boolean matches(Bitmap screen, int background, int foreground) {
            if (!new Rect(0, 0, screen.getWidth(), screen.getHeight()).contains(bounds)) return false;
            boolean surface = near(pixelAt(screen, 1, 2, 3, 5), background, 3)
                    && near(pixelAt(screen, 3, 4, 1, 2), background, 3)
                    && near(pixelAt(screen, 1, 3, 4, 5), background, 3);
            // Both regions contain Chinese/Latin glyphs; neither contains the third-row cursor.
            firstInk = countInk(screen, firstText, foreground);
            secondInk = countInk(screen, secondText, foreground);
            return surface && firstInk >= Math.max(8, firstText.width() * firstText.height() / 100)
                    && secondInk >= Math.max(8, secondText.width() * secondText.height() / 100);
        }

        int pixelAt(Bitmap screen, int xNumerator, int xDenominator, int yNumerator, int yDenominator) {
            return screen.getPixel(bounds.left + bounds.width() * xNumerator / xDenominator,
                    bounds.top + bounds.height() * yNumerator / yDenominator);
        }

        String describe(Bitmap screen) {
            if (!new Rect(0, 0, screen.getWidth(), screen.getHeight()).contains(bounds))
                return "view bounds=" + bounds + " outside screenshot=" + screen.getWidth() + "x" + screen.getHeight();
            return "bounds=" + bounds + ", center=" + Integer.toHexString(pixelAt(screen, 1, 2, 3, 5))
                    + ", textPixels=" + firstInk + "/" + secondInk;
        }

        private static int countInk(Bitmap screen, Rect region, int foreground) {
            int count = 0;
            for (int y = region.top; y < region.bottom; y++) {
                for (int x = region.left; x < region.right; x++) {
                    if (near(screen.getPixel(x, y), foreground, 12)) count++;
                }
            }
            return count;
        }

        private static boolean near(int actual, int expected, int tolerance) {
            for (int shift = 0; shift <= 16; shift += 8) {
                if (Math.abs(((actual >> shift) & 255) - ((expected >> shift) & 255)) > tolerance) return false;
            }
            return true;
        }
    }
}
