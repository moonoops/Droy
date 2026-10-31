package io.droyagent.app

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.droyagent.app.nativeui.setup.NativeStartupGate
import io.droyagent.app.nativeui.ui.DroyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeStartupGateDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedInstallationNeverMountsFirstRunWhileLocalRoutingLoads() {
        val required = mutableStateOf<Boolean?>(null)
        var onboardingCompositions = 0
        compose.setContent {
            DroyTheme {
                NativeStartupGate(required.value, null, {}, onboarding = {
                    onboardingCompositions++
                    Box(Modifier.testTag("fixture-onboarding"))
                }, content = { Box(Modifier.testTag("fixture-app")) })
            }
        }
        compose.onNodeWithTag("native-startup").assertExists()
        compose.onNodeWithTag("fixture-onboarding").assertDoesNotExist()
        compose.onNodeWithTag("fixture-app").assertDoesNotExist()
        compose.runOnIdle { required.value = false }
        compose.onNodeWithTag("fixture-app").assertExists()
        compose.onNodeWithTag("native-startup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, onboardingCompositions) }
    }

    @Test fun unknownRouteFailureCanRetryWithoutEnteringEitherProductFlow() {
        val required = mutableStateOf<Boolean?>(null)
        val error = mutableStateOf<String?>("storage_error")
        var retries = 0
        compose.setContent {
            DroyTheme {
                NativeStartupGate(required.value, error.value, { retries++; error.value = null },
                    onboarding = { Box(Modifier.testTag("fixture-onboarding")) },
                    content = { Box(Modifier.testTag("fixture-app")) })
            }
        }
        compose.onNodeWithTag("native-setup-error").assertIsDisplayed()
        compose.onNodeWithTag("fixture-onboarding").assertDoesNotExist()
        compose.onNodeWithTag("fixture-app").assertDoesNotExist()
        val retry = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.native_setup_retry)
        compose.onNodeWithText(retry).performClick()
        compose.runOnIdle { assertEquals(1, retries); required.value = true }
        compose.onNodeWithTag("fixture-onboarding").assertExists()
        compose.onNodeWithTag("fixture-app").assertDoesNotExist()
    }
}
