package io.droyagent.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Uses Android's own adaptive-icon mask and renderer rather than the host preview. */
@RunWith(AndroidJUnit4.class)
public final class LauncherIconArtworkDeviceTest {
    @Test public void allNineIconsRenderContrastingCatAndWarmPaper() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue(context.getPackageName().equals("io.droyagent.app.dev"));
        int[] resources = {
            R.mipmap.ic_launcher_closeup, R.mipmap.ic_launcher_sunglasses,
            R.mipmap.ic_launcher_headphones, R.mipmap.ic_launcher_programmer,
            R.mipmap.ic_launcher_hat_scarf, R.mipmap.ic_launcher_sleep_curled,
            R.mipmap.ic_launcher_sleep_side, R.mipmap.ic_launcher_sleep_belly_up,
            R.mipmap.ic_launcher_sleep_slumped
        };
        Bitmap board = Bitmap.createBitmap(960, 948, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(board);
        canvas.drawColor(Color.rgb(224, 224, 224));
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.rgb(30, 30, 30));
        text.setTextSize(22);
        canvas.drawText("Android API " + Build.VERSION.SDK_INT + " native adaptive icons", 24, 32, text);
        JSONArray measurements = new JSONArray();
        for (int i = 0; i < resources.length; i++) {
            Drawable icon = context.getResources().getDrawable(resources[i], context.getTheme());
            assertTrue("Launcher artwork must use an adaptive icon", icon instanceof AdaptiveIconDrawable);
            Bitmap rendered = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            icon.setBounds(0, 0, 192, 192);
            icon.draw(new Canvas(rendered));
            int dark = 0, warmPaper = 0, visible = 0;
            for (int y = 0; y < 192; y++) for (int x = 0; x < 192; x++) {
                int pixel = rendered.getPixel(x, y);
                if (Color.alpha(pixel) < 200) continue;
                visible++;
                if (Color.red(pixel) < 80 && Color.green(pixel) < 80 && Color.blue(pixel) < 80) dark++;
                if (Color.red(pixel) > 210 && Color.green(pixel) > 200 && Color.blue(pixel) > 185
                        && Color.red(pixel) - Color.blue(pixel) > 3) warmPaper++;
            }
            String name = context.getResources().getResourceEntryName(resources[i]);
            assertTrue(name + " has no visible cat silhouette", dark > visible * 0.05);
            assertTrue(name + " lost its warm paper background", warmPaper > visible * 0.03);
            measurements.put(new JSONObject().put("resource", name).put("visiblePixels", visible)
                .put("darkPixels", dark).put("warmPaperPixels", warmPaper));
            int x = (i % 3) * 320, y = (i / 3) * 300 + 48;
            canvas.drawBitmap(rendered, x + 64, y + 12, null);
            text.setTextSize(16);
            canvas.drawText(name.replace("ic_launcher_", ""), x + 64, y + 240, text);
            rendered.recycle();
        }
        File directory = new File(context.getFilesDir(), "launcher-icons-validation");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File image = new File(directory, "native-icon-board-api" + Build.VERSION.SDK_INT + ".png");
        try (FileOutputStream output = new FileOutputStream(image)) {
            assertTrue(board.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        File report = new File(directory, "native-icon-rendering-api" + Build.VERSION.SDK_INT + ".json");
        try (FileOutputStream output = new FileOutputStream(report)) {
            output.write(new JSONObject().put("api", Build.VERSION.SDK_INT).put("icons", measurements)
                .toString(2).getBytes(StandardCharsets.UTF_8));
        }
        board.recycle();
    }
}
