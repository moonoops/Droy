package io.droyagent.app.nativeui.companion

import io.droyagent.app.nativeui.companion.data.bundledCompanionAsset
import org.junit.Assert.*
import org.junit.Test

class CompanionBundledAssetsTest {
    @Test fun onlyPublicArtworkUsesLocalAssets() {
        assertEquals("wallpaper-01.webp", bundledCompanionAsset("/droy/companions/assets/wallpaper-01.webp"))
        assertEquals("kaka-v2.png", bundledCompanionAsset("/droy/companions/assets/kaka-v2.png?v=2"))
        for (url in listOf("/droy/companions/one/attachments/photo.png", "/droy/companions/assets/../photo.png",
            "/droy/companions/assets/%2e%2e/photo.png", "https://example.com/photo.png",
            "/droy/companions/assets/file.json", "/droy/companions/assets/photo.png#fragment"))
            assertNull(url, bundledCompanionAsset(url))
    }
}
