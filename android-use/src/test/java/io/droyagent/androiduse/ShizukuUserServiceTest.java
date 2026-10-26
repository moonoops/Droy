package io.droyagent.androiduse;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ShizukuUserServiceTest {
    @Test public void onlyFixedOperationsCanReachTheShell() {
        assertEquals(List.of("/system/bin/input", "tap", "30", "40"), ShizukuUserService.command("tap", new int[]{30, 40}));
        assertEquals(List.of("/system/bin/screencap", "-p"), ShizukuUserService.command("screenshot", new int[0]));
        assertThrows(IllegalArgumentException.class, () -> ShizukuUserService.command("sh", new int[0]));
        assertThrows(IllegalArgumentException.class, () -> ShizukuUserService.command("tap", new int[]{-1, 20}));
        assertThrows(IllegalArgumentException.class, () -> ShizukuUserService.command("back", new int[]{4}));
        assertThrows(IllegalArgumentException.class, () -> ShizukuUserService.command("swipe", new int[]{1, 2, 3, 4, 2001}));
    }
}
