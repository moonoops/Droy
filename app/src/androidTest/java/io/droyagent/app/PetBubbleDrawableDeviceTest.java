package io.droyagent.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.core.graphics.ColorUtils;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PetBubbleDrawableDeviceTest {
    @Test public void compositorBlurUsesATranslucentTintAndFallbackStillHonorsFades() {
        assertEquals(178, centerAlpha(true, 255));
        assertEquals(224, centerAlpha(false, 255));
        assertEquals(178 * 128 / 255, centerAlpha(true, 128));
        assertEquals(224 * 128 / 255, centerAlpha(false, 128));
        assertEquals(0, centerAlpha(true, 0));
        assertEquals(0, centerAlpha(false, 0));
    }

    @Test public void roundedOutlineBoundsTheBlurToTheBodyInsteadOfTheTail() {
        for (PetBubblePlacement.Side side : PetBubblePlacement.Side.values()) {
            PetBubbleDrawable bubble = new PetBubbleDrawable(1f, Color.BLACK, Color.GRAY);
            bubble.setBounds(0, 0, 200, 100); bubble.pointTo(side, 50);
            Outline outline = new Outline(); bubble.getOutline(outline);
            Rect body = new Rect();
            assertTrue("Window blur must use a rectangular rounded outline", outline.getRect(body));
            assertTrue("The blur outline must support clipping", outline.canClip());
            assertEquals(16f, outline.getRadius(), .01f);
            assertEquals(1f, outline.getAlpha(), 0f);
            switch (side) {
                case LEFT -> assertTrue(body.left >= 6);
                case RIGHT -> assertTrue(body.right <= 194);
                case TOP -> assertTrue(body.top >= 6);
                case BOTTOM -> assertTrue(body.bottom <= 94);
            }
        }
    }

    @Test public void appAppearanceSuppliesReadableLightAndDarkBubbleColors() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("product", Context.MODE_PRIVATE);
        String mode = preferences.contains("appearanceMode") ? preferences.getString("appearanceMode", null) : null;
        String source = preferences.contains("colorSource") ? preferences.getString("colorSource", null) : null;
        String palette = preferences.contains("themePalette") ? preferences.getString("themePalette", null) : null;
        try {
            for (String appearance : new String[]{"light", "dark"}) for (String color : new String[]{"purple", "teal", "amber", "rose"}) {
                assertTrue(preferences.edit().putString("appearanceMode", appearance).putString("colorSource", "preset").putString("themePalette", color).commit());
                PetBubbleColors colors = PetBubbleColors.from(context);
                assertEquals(255, Color.alpha(colors.surface));
                // Theme-role contrast checks the opaque base colors. The actual glass
                // composite also depends on the wallpaper and compositor blur.
                assertTrue("Body text must contrast with the theme's base surface", ColorUtils.calculateContrast(colors.onSurface, colors.surface) >= 4.5);
                assertTrue("Secondary text must contrast with the base surface", ColorUtils.calculateContrast(colors.onSurfaceVariant, colors.surface) >= 4.5);
                assertTrue("The primary action must remain readable", ColorUtils.calculateContrast(colors.onPrimary, colors.primary) >= 4.5);
            }
        } finally {
            SharedPreferences.Editor restore = preferences.edit();
            if (mode == null) restore.remove("appearanceMode"); else restore.putString("appearanceMode", mode);
            if (source == null) restore.remove("colorSource"); else restore.putString("colorSource", source);
            if (palette == null) restore.remove("themePalette"); else restore.putString("themePalette", palette);
            assertTrue(restore.commit());
        }
    }

    private int centerAlpha(boolean blurAvailable, int alpha) {
        Bitmap image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        try {
            PetBubbleDrawable bubble = new PetBubbleDrawable(1f, Color.BLACK, Color.GRAY);
            bubble.setBlurAvailable(blurAvailable);
            bubble.setBounds(0, 0, image.getWidth(), image.getHeight());
            bubble.pointTo(PetBubblePlacement.Side.LEFT, 50);
            bubble.setAlpha(alpha); bubble.draw(new Canvas(image));
            return Color.alpha(image.getPixel(100, 50));
        } finally { image.recycle(); }
    }
}
