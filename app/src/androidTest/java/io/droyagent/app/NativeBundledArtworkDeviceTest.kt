package io.droyagent.app

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.NativeCompanionClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeBundledArtworkDeviceTest {
    @Test fun allWallpapersDecodeFromPackageWithoutEngine() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val client = NativeCompanionClient(assets)
        for (id in 1..8) for (suffix in listOf("", "-thumb")) {
            val bytes = client.media("/droy/companions/assets/wallpaper-0$id$suffix.webp")
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            assertNotNull(bitmap)
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
    }
}
