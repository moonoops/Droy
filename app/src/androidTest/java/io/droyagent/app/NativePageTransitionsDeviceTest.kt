package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.ui.droyNavigationMotion
import io.droyagent.app.nativeui.ui.pageTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Check which page covers the overlap, not only whether the shell remains hidden. */
class NativePageTransitionsDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun nestedPagesKeepTheirDepthWhileEnteringAndReturning() {
        val page = mutableIntStateOf(0)
        val colors = listOf(Color.Red, Color.Blue, Color.Yellow)
        fixture.scenario.onActivity { activity ->
            activity.setContent {
                DroyTheme {
                    val motion = droyNavigationMotion()
                    Box(Modifier.size(240.dp, 320.dp).background(Color.Green).testTag("page-motion-surface")) {
                        AnimatedContent(page.intValue, transitionSpec = {
                            motion.pageTransition(targetState > initialState, targetState)
                        }, label = "nested-navigation-regression") { target ->
                            Box(Modifier.fillMaxSize().background(colors[target]).testTag("page-motion-$target"))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        try {
            var previous = 0
            for (target in listOf(1, 2, 1, 0)) {
                val outgoing = previous
                compose.runOnIdle { page.intValue = target }
                var overlapSamples = 0
                for ((index, elapsed) in listOf(32L, 32L, 48L, 64L).withIndex()) {
                    compose.mainClock.advanceTimeBy(elapsed)
                    val surface = compose.onNodeWithTag("page-motion-surface")
                    val surfaceOrigin = surface.fetchSemanticsNode().positionInRoot
                    val enteringOrigin = compose.onNodeWithTag("page-motion-$target").fetchSemanticsNode().positionInRoot
                    val exitingOrigin = compose.onNodeWithTag("page-motion-$outgoing").fetchSemanticsNode().positionInRoot
                    val pixels = surface.captureToImage().toPixelMap()
                    val enteringLeft = enteringOrigin.x - surfaceOrigin.x
                    val exitingLeft = exitingOrigin.x - surfaceOrigin.x
                    for (x in 4 until pixels.width - 4 step 12) {
                        val underEntering = x >= enteringLeft && x < enteringLeft + pixels.width
                        val underExiting = x >= exitingLeft && x < exitingLeft + pixels.width
                        val expected = if (target > outgoing) {
                            if (underEntering) colors[target] else colors[outgoing]
                        } else {
                            if (underExiting) colors[outgoing] else colors[target]
                        }
                        val actual = pixels[x, pixels.height / 2]
                        val message = "$outgoing->$target frame $index x=$x entering=$enteringLeft exiting=$exitingLeft"
                        assertEquals("$message red", expected.red, actual.red, .01f)
                        assertEquals("$message green", expected.green, actual.green, .01f)
                        assertEquals("$message blue", expected.blue, actual.blue, .01f)
                        if (underEntering && underExiting) overlapSamples++
                    }
                    fixture.capture("page-depth-$outgoing-$target-frame-$index")
                }
                assertTrue("The $outgoing->$target transition must inspect overlapping content", overlapSamples > 0)
                compose.mainClock.advanceTimeBy(2_000)
                compose.onNodeWithTag("page-motion-$outgoing").assertDoesNotExist()
                compose.onNodeWithTag("page-motion-$target").assertIsDisplayed()
                previous = target
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
