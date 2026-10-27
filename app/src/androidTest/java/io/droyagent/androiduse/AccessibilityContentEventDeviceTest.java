package io.droyagent.androiduse;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** An unattached service has no Android services to query from a content-event callback. */
@RunWith(AndroidJUnit4.class)
public final class AccessibilityContentEventDeviceTest {
    @Test public void unknownGeometryAndWindowsInvalidateContentWithoutCallingAndroidServices() throws Exception {
        DroyAccessibilityService detached = new DroyAccessibilityService();
        Method record = recordMethod();
        onMain(() -> {
            long full = DroyAccessibilityService.generation(), content = DroyAccessibilityService.contentGeneration();
            record(record, detached, 41);
            assertEquals(full + 1, DroyAccessibilityService.generation());
            assertEquals(content + 1, DroyAccessibilityService.contentGeneration());
        });
    }

    @Test public void knownBarContentUsesRecordedWindowsAndGeometryWithoutCallingAndroidServices() throws Exception {
        DroyAccessibilityService detached = withRecordedBar();
        Method record = recordMethod();
        onMain(() -> {
            long full = DroyAccessibilityService.generation(), content = DroyAccessibilityService.contentGeneration();
            for (int i = 0; i < 100; i++) record(record, detached, 41);
            assertEquals(full + 100, DroyAccessibilityService.generation());
            assertEquals(content, DroyAccessibilityService.contentGeneration());
            record(record, detached, 42);
            record(record, detached, 999);
            assertEquals(content + 2, DroyAccessibilityService.contentGeneration());
        });
    }

    @Test public void configurationChangeRejectsGeometryPublishedByAnOlderQuery() throws Exception {
        DroyAccessibilityService detached = withRecordedBar();
        Method record = recordMethod();
        Field region = field("eventContentRegion");
        Object previousRegion = region.get(detached);
        onMain(() -> {
            detached.onConfigurationChanged(new Configuration());
            try { region.set(detached, previousRegion); }
            catch (IllegalAccessException error) { throw new AssertionError(error); }
            long content = DroyAccessibilityService.contentGeneration();
            record(record, detached, 41);
            assertEquals("A late result must not restore the pre-rotation bar exception", content + 1,
                    DroyAccessibilityService.contentGeneration());
        });
    }

    private static DroyAccessibilityService withRecordedBar() throws Exception {
        DroyAccessibilityService detached = new DroyAccessibilityService();
        Class<?> regionType = Class.forName(DroyAccessibilityService.class.getName() + "$ContentRegion");
        Constructor<?> region = regionType.getDeclaredConstructor(long.class, Rect.class, Rect.class);
        region.setAccessible(true);
        field("eventContentRegion").set(detached, region.newInstance(0L,
                new Rect(0, 0, 1080, 2400), new Rect(0, 80, 1080, 2320)));
        Class<?> windowType = Class.forName(DroyAccessibilityService.class.getName() + "$WindowIdentity");
        Constructor<?> window = windowType.getDeclaredConstructor(int.class, int.class, Rect.class,
                boolean.class, boolean.class, boolean.class, String.class);
        window.setAccessible(true);
        field("contentWindows").set(detached, List.of(
                window.newInstance(41, AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, 0, 1080, 80), false, false, false, null),
                window.newInstance(42, AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 80, 1080, 2320), true, true, false, null)));
        return detached;
    }

    private static Field field(String name) throws Exception {
        Field field = DroyAccessibilityService.class.getDeclaredField(name); field.setAccessible(true); return field;
    }

    private static Method recordMethod() throws Exception {
        Method method = DroyAccessibilityService.class.getDeclaredMethod("recordUiChange", int.class, int.class);
        method.setAccessible(true); return method;
    }

    private static void record(Method method, DroyAccessibilityService service, int windowId) {
        try { method.invoke(service, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, windowId); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }

    private static void onMain(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }
}
