package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.voiceinput.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real pointer input through the shared Compose control. The recorder boundary is deliberately inert. */
@RunWith(AndroidJUnit4::class)
class NativeVoiceGestureDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun releasedVoiceLivesInBubbleWhileRecognitionRunsWithoutComposerProcessingCard() {
        val state = mutableStateOf(NativeVoiceInputState(visible = true, operationId = "pending-audio",
            phase = NativeVoiceInputPhase.RECOGNIZING, durationMs = 3_000))
        fixture.scenario.onActivity { activity -> activity.setContent { DroyTheme {
            Column {
                if (state.value.showPendingBubble) NativePendingVoiceBubble(state.value, {})
                NativeVoiceInputStatus(state.value, {}, {}, {})
            }
        } } }
        compose.onNodeWithTag("native-pending-voice-bubble").assertIsDisplayed()
        compose.onNodeWithTag("native-inline-voice").assertDoesNotExist()
        compose.onAllNodesWithText(fixture.context.getString(R.string.native_voice_input_processing)).assertCountEquals(0)
        compose.runOnIdle { state.value = state.value.copy(phase = NativeVoiceInputPhase.RECORDING) }
        compose.onNodeWithTag("native-pending-voice-bubble").assertDoesNotExist()
        compose.onNodeWithTag("native-inline-voice").assertIsDisplayed()
        fixture.capture("round3-recording-strip")
    }

    @Test fun releaseFinishesOnceSwipeUpCancelsAndPermissionCompletionCannotStartAnOldPress() {
        val state = mutableStateOf(NativeVoiceInputState(visible = true,
            permissions = NativeVoiceInputPermissions(true, true, true, false, true)))
        val actions = mutableListOf<NativeVoiceInputAction>()
        var starts = 0
        var permissionRequests = 0
        fixture.scenario.onActivity { activity ->
            activity.setContent {
                DroyTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NativeHoldToRecordButton(state.value,
                            onBegin = { starts++; state.value = state.value.copy(phase = NativeVoiceInputPhase.RECORDING) },
                            onAction = { action ->
                                actions += action
                                if (action is NativeVoiceInputAction.FinishHold) state.value = state.value.copy(phase = NativeVoiceInputPhase.IDLE)
                            },
                            onRequestPermissions = { permissionRequests++ })
                    }
                }
            }
        }
        val button = compose.onNodeWithTag("native-hold-to-record")
        button.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(1, starts); assertTrue(state.value.recording) }
        button.performTouchInput { advanceEventTime(500); up() }
        compose.runOnIdle {
            assertEquals(listOf(NativeVoiceInputAction.FinishHold(false)), actions.filterIsInstance<NativeVoiceInputAction.FinishHold>())
            actions.clear()
        }
        button.performTouchInput { down(center) }
        compose.waitForIdle()
        button.performTouchInput { moveTo(Offset(center.x, center.y - height * 2)); advanceEventTime(500); up() }
        compose.runOnIdle {
            assertEquals(2, starts)
            assertTrue(actions.contains(NativeVoiceInputAction.MoveHold(true)))
            assertEquals(listOf(NativeVoiceInputAction.FinishHold(true)), actions.filterIsInstance<NativeVoiceInputAction.FinishHold>())
            actions.clear()
            state.value = state.value.copy(permissions = state.value.permissions.copy(microphone = false))
        }
        button.performTouchInput { down(center); advanceEventTime(500); up() }
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertEquals(2, starts)
            assertTrue(actions.isEmpty())
            state.value = state.value.copy(permissions = state.value.permissions.copy(microphone = true))
        }
        compose.runOnIdle { assertEquals("Permission returning after release must not start recording", 2, starts) }
        assertTrue("No microphone capture is opened by this test", AudioCapture.get(fixture.context).uiStatus().isNull("active"))
    }
}
