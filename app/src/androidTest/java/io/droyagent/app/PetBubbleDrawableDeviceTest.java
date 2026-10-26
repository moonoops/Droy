package io.droyagent.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class PetBubbleDrawableDeviceTest {
    @Test public void answerFormsKeepTheirBackdropWhileOrdinaryBubblesRetainTheirTransparency() {
        assertEquals(247, centerAlpha(true, 255));
        assertEquals(199, centerAlpha(false, 255));
        assertEquals(247 * 128 / 255, centerAlpha(true, 128));
        assertEquals(199 * 128 / 255, centerAlpha(false, 128));
        assertEquals(0, centerAlpha(true, 0));
    }

    private int centerAlpha(boolean answerForm, int alpha) {
        Bitmap image = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888);
        try {
            PetBubbleDrawable bubble = new PetBubbleDrawable(1f, answerForm);
            bubble.setBounds(0, 0, image.getWidth(), image.getHeight());
            bubble.pointTo(PetBubblePlacement.Side.LEFT, 50);
            bubble.setAlpha(alpha); bubble.draw(new Canvas(image));
            return Color.alpha(image.getPixel(100, 50));
        } finally { image.recycle(); }
    }
}
