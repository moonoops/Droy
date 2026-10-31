package io.droyagent.app

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.companion.ui.CompanionBackgroundPage
import io.droyagent.app.nativeui.ui.DroyTheme
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise the real background page at compact width without starting the runtime or a picker. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionBackgroundLayoutDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun englishBackgroundHeadingStaysWholeBesideOrAboveTheImageAction() = inspect("en")
    @Test fun chineseBackgroundHeadingStaysWholeBesideOrAboveTheImageAction() = inspect("zh")

    private fun inspect(locale: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("Development package only", "io.droyagent.app.dev", context.packageName)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
            fontScale = 1.3f
        }
        val localized = context.createConfigurationContext(configuration)
        val density = Density(context.resources.displayMetrics.density, 1.3f)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        val image = try {
            bitmap.eraseColor(android.graphics.Color.LTGRAY)
            ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally { bitmap.recycle() }
        val imageRequests = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources, LocalDensity provides density) {
                DroyTheme(dark = true) {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        CompanionBackgroundPage(initial = null, loader = { image }, enabled = true,
                            onChoose = { imageRequests.incrementAndGet() }, onApply = {}, onClose = {})
                    }
                }
            }
        }
        val expectedHeading = localized.getString(R.string.native_companion_background_options)
        val heading = compose.onNodeWithText(expectedHeading,
            useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        heading.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val action = compose.onNodeWithTag("native-creation-background-upload").assertIsDisplayed().assertIsEnabled()
        val textNode = heading.fetchSemanticsNode()
        val textBounds = textNode.boundsInRoot
        val textOrigin = textNode.positionInRoot
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        val layout = layouts.single()
        val lines = JSONArray()
        repeat(layout.lineCount) { index -> lines.put(JSONObject().put("line", index)
            .put("left", layout.getLineLeft(index)).put("right", layout.getLineRight(index))
            .put("width", layout.getLineRight(index) - layout.getLineLeft(index))
            .put("top", layout.getLineTop(index)).put("bottom", layout.getLineBottom(index))
            .put("ellipsized", layout.isLineEllipsized(index))) }
        val boxes = layout.layoutInput.text.text.indices.map(layout::getBoundingBox)
        val glyphLeft = boxes.minOf { it.left }
        val glyphTop = boxes.minOf { it.top }
        val glyphRight = boxes.maxOf { it.right }
        val glyphBottom = boxes.maxOf { it.bottom }
        val metrics = JSONObject().put("locale", locale).put("fontScale", 1.3f).put("widthDp", 320)
            .put("sizeWidth", layout.size.width).put("sizeHeight", layout.size.height)
            .put("paragraphWidth", layout.multiParagraph.width).put("paragraphHeight", layout.multiParagraph.height)
            .put("paragraphWidthBeyondSize", layout.multiParagraph.width - layout.size.width)
            .put("paragraphHeightBeyondSize", layout.multiParagraph.height - layout.size.height)
            .put("constraints", layout.layoutInput.constraints.toString())
            .put("hasVisualOverflow", layout.hasVisualOverflow).put("didOverflowWidth", layout.didOverflowWidth)
            .put("didOverflowHeight", layout.didOverflowHeight).put("lines", lines)
            .put("glyphLeft", glyphLeft).put("glyphTop", glyphTop)
            .put("glyphRight", glyphRight).put("glyphBottom", glyphBottom)
            .put("textOrigin", textOrigin.toString()).put("textBounds", textBounds.toString())
            .put("actionBounds", actionBounds.toString()).put("geometryTolerancePx", 1)
        try {
            assertEquals("The complete heading must reach the text layout unchanged", expectedHeading, layout.layoutInput.text.text)
            assertEquals("The compact heading must remain a complete word on one line", 1, layout.lineCount)
            assertFalse("The complete heading must not be ellipsized", layout.isLineEllipsized(0))
            assertFalse("The heading must not lose any lines", layout.multiParagraph.didExceedMaxLines)
            assertEquals("Every heading character must appear in the rendered line", expectedHeading.length, layout.getLineEnd(0))
            // FlowRow can retain a paragraph at its full max constraint while wrapping the Text
            // node to its glyphs. Check the rendered geometry, allowing only pixel rounding.
            val tolerance = 1f
            assertTrue("The rendered line must fit the measured Text node",
                layout.getLineLeft(0) >= -tolerance && layout.getLineRight(0) <= layout.size.width + tolerance &&
                    layout.getLineTop(0) >= -tolerance && layout.getLineBottom(0) <= layout.size.height + tolerance)
            assertTrue("Every glyph must fit the measured Text node",
                glyphLeft >= -tolerance && glyphRight <= layout.size.width + tolerance &&
                    glyphTop >= -tolerance && glyphBottom <= layout.size.height + tolerance)
            assertTrue("Every glyph must remain inside the visible text bounds",
                textOrigin.x + glyphLeft >= textBounds.left - tolerance &&
                    textOrigin.x + glyphRight <= textBounds.right + tolerance &&
                    textOrigin.y + glyphTop >= textBounds.top - tolerance &&
                    textOrigin.y + glyphBottom <= textBounds.bottom + tolerance)
            assertTrue("The image action must not overlap the complete heading",
                textBounds.right <= actionBounds.left || textBounds.bottom <= actionBounds.top)
            compose.onNodeWithTag("native-background-apply").assertIsDisplayed().assertIsEnabled()
            action.performClick()
            assertEquals("The image action must retain its callback", 1, imageRequests.get())
        } catch (failure: AssertionError) {
            try {
                val directory = File(context.getExternalFilesDir(null), "native-background-layout-evidence")
                assertTrue(directory.isDirectory || directory.mkdirs())
                File(directory, "background-heading-$locale-failure.json").writeText(metrics.toString(2))
                val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try { File(directory, "background-heading-$locale-failure.png").outputStream().use {
                    assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                } } finally { screenshot.recycle() }
            } catch (artifactFailure: Exception) { failure.addSuppressed(artifactFailure) }
            throw AssertionError("${failure.message}; layoutMetrics=$metrics", failure)
        }
    }
}
