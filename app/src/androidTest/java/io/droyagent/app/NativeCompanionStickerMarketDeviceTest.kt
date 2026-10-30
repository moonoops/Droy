package io.droyagent.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.ui.CompanionStickerMarketPage
import io.droyagent.app.nativeui.ui.DroyTheme
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Market presentation and real touch targets with fictional data; remote integration has separate Host tests. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionStickerMarketDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun searchDownloadFailureRetryAndLastPackRemainReachable() {
        val locale = InstrumentationRegistry.getArguments().getString("nativeFixtureLocale", "en")
        val packs = (0..5).map { index -> NativeCompanionMarketPack("fictional-$index", "Fictional pack $index",
            "Fictional source description. 图包原始说明。", if (index == 0) 0 else 12, if (index == 0) 0 else 120_000, "CC0") }
        val shown = mutableStateOf(NativeCompanionStickerMarketState(packs = packs, loaded = true))
        val searches = AtomicInteger()
        val retries = AtomicInteger()
        var installed: String? = null
        fixture.scenario.onActivity { activity ->
            val config = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }
            val localized = activity.createConfigurationContext(config)
            activity.setContent {
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config) {
                    DroyTheme(dark = false) {
                        CompanionStickerMarketPage("Fictional friend", shown.value, true,
                            { query -> shown.value = shown.value.copy(query = query) }, { searches.incrementAndGet() },
                            { pack -> installed = pack.id; shown.value = shown.value.copy(installingId = pack.id) },
                            { retries.incrementAndGet() }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("native-companion-market-page").assertIsDisplayed()
        compose.onNodeWithTag("native-companion-market-query").performTextInput("Fictional")
        compose.onNodeWithTag("native-companion-market-search").performClick()
        compose.runOnIdle { assertEquals(1, searches.get()); assertEquals("Fictional", shown.value.query) }
        fixture.capture("sticker-market-$locale-list")
        compose.onNodeWithTag("native-companion-market-results")
            .performScrollToNode(hasTestTag("native-companion-market-install:fictional-5"))
        compose.onNodeWithTag("native-companion-market-install:fictional-5").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("fictional-5", installed) }
        compose.onNodeWithTag("native-companion-market-search").assertIsNotEnabled()
        compose.onNodeWithTag("native-companion-market-install:fictional-5").assertIsNotEnabled()
        fixture.capture("sticker-market-$locale-downloading")
        compose.runOnIdle { shown.value = shown.value.copy(installingId = null, error = "COMPANION_STICKER_ONLINE_CHECKSUM") }
        compose.onNodeWithTag("native-companion-market-error").assertIsDisplayed()
        compose.onNodeWithTag("native-companion-market-retry").performClick()
        compose.runOnIdle {
            assertEquals(1, retries.get())
            val receipt = NativeCompanionMarketReceipt(NativeCompanionStickerPack("pack-fictional", packs.last().name, 9), 3)
            shown.value = shown.value.copy(error = null, receipts = mapOf(packs.last().id to receipt), lastReceipt = receipt)
        }
        compose.onNodeWithTag("native-companion-market-success").assertIsDisplayed()
        compose.onNodeWithTag("native-companion-market-install:fictional-5").assertIsNotEnabled()
        fixture.capture("sticker-market-$locale-complete")
    }
}
