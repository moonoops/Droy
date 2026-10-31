package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The shell must never show through the two opaque page surfaces during navigation. */
class NativeNavigationOpacityDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()
    @Test fun pageTransitionsNeverBlendWithTheShell() {
        val page = mutableIntStateOf(0)
        fixture.scenario.onActivity { activity -> activity.setContent { DroyTheme {
            val motion = droyNavigationMotion()
            Box(Modifier.size(240.dp, 320.dp).background(Color.Green).testTag("motion-surface")) {
                AnimatedContent(page.intValue, transitionSpec = {
                    if (targetState > initialState) (motion.enter togetherWith motion.exit).using(null)
                    else (motion.popEnter togetherWith motion.popExit).using(null)
                }, label = "navigation-regression") { target ->
                    Box(Modifier.fillMaxSize().background(if (target == 0) Color.Red else Color.Blue))
                }
            }
        } } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        for (target in listOf(1, 0)) {
            compose.runOnIdle { page.intValue = target }
            for (advance in listOf(16L, 48L, 96L, 160L, 320L)) {
                compose.mainClock.advanceTimeBy(advance)
                val pixels = compose.onNodeWithTag("motion-surface").captureToImage().toPixelMap()
                for (x in 4 until pixels.width - 4 step 12) {
                    val c = pixels[x, pixels.height / 2]
                    assertTrue("Opaque page expected at frame $advance, x=$x: $c", c.green < .01f && (c.red > .99f || c.blue > .99f))
                }
            }
            compose.mainClock.advanceTimeBy(1000)
        }
        compose.mainClock.autoAdvance = true
    }
}
