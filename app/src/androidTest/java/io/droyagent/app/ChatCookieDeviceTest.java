package io.droyagent.app;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real DSH startup with an oversized synthetic HttpOnly cookie jar; no model requests. */
@RunWith(AndroidJUnit4.class)
public final class ChatCookieDeviceTest {
    private static final String LOOPBACK_URL = "http://127.0.0.1:39999/";
    private static final String SENTINEL = "droy-cookie-regression-sentinel";
    private static final int AUTH_COOKIE_COUNT = 80;
    private Instrumentation instrumentation;
    private Context context;
    private CookieManager cookies;
    private MainActivity activity;
    private boolean fixtureOwned;
    private final List<String> fixtureNames = new ArrayList<>();

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        assertTrue("Cookie accumulation fixtures require a dedicated emulator",
                Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                        || Build.MODEL.contains("sdk_gphone") || Build.PRODUCT.contains("sdk_gphone"));
        assertTrue("Stop existing tasks and engine before cookie startup tests", RuntimeService.canManageHistory());
        instrumentation.runOnMainSync(() -> {
            cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
        });
        for (int index = 0; index < AUTH_COOKIE_COUNT; index++)
            fixtureNames.add("dsh-auth-" + String.format(Locale.ROOT, "%043d", index));
        fixtureNames.add(SENTINEL);
        fixtureOwned = true;
    }

    @After public void tearDown() throws Exception {
        if (!fixtureOwned) return;
        try {
            if (activity != null) {
                instrumentation.runOnMainSync(activity::finish);
                DeviceUi.await("Cookie regression Activity did not finish", 10000, () -> {
                    boolean[] destroyed = {false};
                    instrumentation.runOnMainSync(() -> destroyed[0] = activity.isDestroyed());
                    return destroyed[0];
                });
            }
        } finally {
            instrumentation.runOnMainSync(RuntimeService::requestStop);
            DeviceUi.await("Cookie regression cleanup left its runtime active", 15000, RuntimeService::canManageHistory);
            List<String> expirations = new ArrayList<>();
            for (String name : fixtureNames)
                expirations.add(name + "=; Max-Age=0; Path=/; HttpOnly");
            setCookies(expirations);
            Set<String> remaining = cookieNames(cookieHeader());
            for (String name : fixtureNames)
                assertFalse("A synthetic cookie survived targeted fixture cleanup", remaining.contains(name));
        }
    }

    @Test public void authCookiesFromOldChatPortsAreRemovedBeforeRealDshStartup() throws Exception {
        List<String> additions = new ArrayList<>();
        String fakeValue = "x".repeat(180);
        for (int index = 0; index < AUTH_COOKIE_COUNT; index++)
            additions.add(fixtureNames.get(index) + "=" + fakeValue + "; Max-Age=3600; HttpOnly; SameSite=Strict; Path=/");
        additions.add(SENTINEL + "=synthetic-preserved-cookie; Max-Age=3600; HttpOnly; SameSite=Strict; Path=/");
        setCookies(additions);
        String seededHeader = cookieHeader();
        Set<String> seededNames = cookieNames(seededHeader);
        for (String name : fixtureNames)
            assertTrue("The real WebView cookie jar did not retain every synthetic fixture", seededNames.contains(name));
        // Cookies are host scoped, so old random chat ports accumulate in this
        // actual jar. Keep the request over Node's default 16 KiB header limit.
        assertTrue("The regression must reproduce an oversized real Cookie header",
                seededHeader.getBytes(StandardCharsets.UTF_8).length > 16 * 1024);

        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        DeviceUi ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        assertTrue("Startup must finish its native and web readiness handshake", ui.nativeUiReady());
        assertFalse("A ready conversation must not retain the loading cover", ui.loadingOverlayShown());
        DeviceUi.await("Real DSH did not mount its usable conversation composer", 10000,
                () -> ui.truth("(()=>{const e=document.querySelector('[data-composer-input][contenteditable=\"true\"]');"
                        + "return !!e&&e.getClientRects().length>0&&!e.closest('[inert]')})()"));

        Set<String> names = cookieNames(cookieHeader());
        for (int index = 0; index < AUTH_COOKIE_COUNT; index++)
            assertFalse("An obsolete DSH auth cookie survived startup", names.contains(fixtureNames.get(index)));
        int currentAuthCookies = 0;
        for (String name : names) if (name.matches("^dsh-auth-[A-Za-z0-9_-]{43}$")) currentAuthCookies++;
        assertTrue("Only the newly established DSH auth cookie may remain", currentAuthCookies <= 1);
        assertTrue("Targeted cleanup must preserve the active Droy session", names.contains("droy_session"));
        assertTrue("Targeted cleanup must preserve unrelated loopback cookies", names.contains(SENTINEL));
    }

    @Test public void deferredCookieSuccessCannotResumeAFailedCurrentWebSession() throws Exception {
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        DeviceUi ui = new DeviceUi(instrumentation, activity);
        ui.awaitPage();
        WebView target = ui.web();
        instrumentation.runOnMainSync(() -> {
            try {
                Field loaded = MainActivity.class.getDeclaredField("loaded");
                loaded.setAccessible(true);
                String session = (String) loaded.get(activity);
                Field failure = MainActivity.class.getDeclaredField("webFailure");
                failure.setAccessible(true);
                Method current = MainActivity.class.getDeclaredMethod("isCurrentWebSession", WebView.class, String.class, int.class);
                current.setAccessible(true);
                Method expire = MainActivity.class.getDeclaredMethod("expireChatAuthCookies", CookieManager.class,
                        String.class, WebView.class, String.class, int.class, List.class, int.class, Runnable.class);
                expire.setAccessible(true);
                Method fail = MainActivity.class.getDeclaredMethod("failWeb", WebView.class, String.class, boolean.class);
                fail.setAccessible(true);
                int port = RuntimeService.webPort;
                String url = "http://127.0.0.1:" + port + "/";
                assertNull("The control page must start without a native failure", failure.get(activity));
                assertTrue("The fixture must target the actual current web session",
                        (Boolean) current.invoke(activity, target, session, port));

                DeferredCookieManager deferred = new DeferredCookieManager();
                int[] completions = {0};
                Runnable onComplete = () -> {
                    completions[0]++;
                    deferred.setCookie(url, "droy_session=fictional-deferred-session; HttpOnly; Path=/", ignored -> {});
                };
                expire.invoke(activity, deferred, url, target, session, port,
                        List.of("dsh-auth-" + "a".repeat(43)), 0, onComplete);
                assertEquals("The expiry must be waiting on exactly one cookie operation", 1, deferred.calls);
                assertNotNull("The fixture must capture the real expiry callback", deferred.pending);
                assertEquals("Expiry must wait for its callback before establishing a session", 0, completions[0]);

                String message = "fictional deferred cookie failure";
                fail.invoke(activity, target, message, false);
                assertEquals("The actual native recovery path must record its failure", message, failure.get(activity));
                assertNotNull("The actual failure path must display its native retry action",
                        DeviceUi.findButton(activity.getWindow().getDecorView(), "重新加载对话"));
                // Keep the session itself valid: only webFailure should reject
                // this late success, rather than a changed token or WebView.
                assertTrue("The failure fixture must preserve the current session identity",
                        (Boolean) current.invoke(activity, target, session, port));
                deferred.pending.onReceiveValue(true);
                assertEquals("A late successful expiry must not run session establishment", 0, completions[0]);
                assertEquals("A late callback must not schedule another cookie operation", 1, deferred.calls);
                assertEquals("A late callback must preserve the existing native failure", message, failure.get(activity));
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }

    private void setCookies(List<String> values) throws Exception {
        CountDownLatch complete = new CountDownLatch(values.size());
        AtomicBoolean accepted = new AtomicBoolean(true);
        instrumentation.runOnMainSync(() -> {
            for (String value : values) cookies.setCookie(LOOPBACK_URL, value, result -> {
                if (!Boolean.TRUE.equals(result)) accepted.set(false);
                complete.countDown();
            });
        });
        assertTrue("Synthetic cookie operations did not finish", complete.await(20, TimeUnit.SECONDS));
        assertTrue("The real WebView jar rejected a synthetic cookie operation", accepted.get());
        instrumentation.runOnMainSync(cookies::flush);
    }

    private String cookieHeader() {
        AtomicReference<String> header = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> header.set(cookies.getCookie(LOOPBACK_URL)));
        return header.get() == null ? "" : header.get();
    }

    private static Set<String> cookieNames(String header) {
        Set<String> names = new HashSet<>();
        for (String entry : header.split(";")) {
            int equals = entry.indexOf('=');
            if (equals > 0) names.add(entry.substring(0, equals).trim());
        }
        return names;
    }

    /** Defers only the callback under test; never reads or changes the real cookie jar. */
    private static final class DeferredCookieManager extends CookieManager {
        int calls;
        ValueCallback<Boolean> pending;

        @Override public void setCookie(String url, String value, ValueCallback<Boolean> callback) {
            calls++;
            pending = callback;
        }

        @Override public boolean acceptCookie() { throw new UnsupportedOperationException(); }
        @Override public boolean acceptThirdPartyCookies(WebView webView) { throw new UnsupportedOperationException(); }
        @Override public void flush() { throw new UnsupportedOperationException(); }
        @Override public String getCookie(String url) { throw new UnsupportedOperationException(); }
        @Override public boolean hasCookies() { throw new UnsupportedOperationException(); }
        @Override public void removeAllCookie() { throw new UnsupportedOperationException(); }
        @Override public void removeAllCookies(ValueCallback<Boolean> callback) { throw new UnsupportedOperationException(); }
        @Override public void removeExpiredCookie() { throw new UnsupportedOperationException(); }
        @Override public void removeSessionCookie() { throw new UnsupportedOperationException(); }
        @Override public void removeSessionCookies(ValueCallback<Boolean> callback) { throw new UnsupportedOperationException(); }
        @Override public void setAcceptCookie(boolean accept) { throw new UnsupportedOperationException(); }
        @Override public void setAcceptThirdPartyCookies(WebView webView, boolean accept) { throw new UnsupportedOperationException(); }
        @Override public void setCookie(String url, String value) { throw new UnsupportedOperationException(); }
    }
}
