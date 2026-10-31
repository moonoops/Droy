package io.droyagent.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.ui.CompanionAvatar
import io.droyagent.app.nativeui.companion.ui.CompanionMessageRow
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.voiceinput.NativePendingVoiceBubble
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputState
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production bubble composables with synthetic state; no microphone or model request. */
@RunWith(AndroidJUnit4::class)
class NativeCompanionVoiceGeometryDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun pendingSendingAndSentKeepBubbleAndAvatarGeometry() {
        val stage = mutableIntStateOf(0)
        val menu = AtomicReference<NativeCompanionMessage?>()
        val profile = NativeUserProfile("You", "fixture-avatar")
        val voice = NativeCompanionVoice("audio", durationMs = 3200L)
        val base = NativeCompanionMessage("voice-message", "user", "voice", "", "", "sending", voice = voice)
        val avatarLabel = fixture.context.getString(R.string.native_companion_my_profile)
        val playLabel = fixture.context.getString(R.string.native_companion_play_voice)
        fixture.scenario.onActivity { activity ->
            activity.setContent {
                DroyTheme(dark = false) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        if (stage.intValue == 0) {
                            Row(Modifier.fillMaxWidth().testTag("voice-row"), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                                NativePendingVoiceBubble(NativeVoiceInputState(durationMs = 3200L), {})
                                CompanionAvatar(profile.name, profile.avatarUrl, { byteArrayOf() }, size = 40.dp,
                                    onClick = {}, label = avatarLabel)
                            }
                        } else {
                            val message = if (stage.intValue == 1) base else base.copy(status = "sent",
                                createdAt = "2026-10-30T01:00:00Z", voice = voice.copy(url = "fixture-audio", transcript = "A transcript"))
                            CompanionMessageRow(message, NativeCompanion("friend", "Friend", "fixture-avatar"), profile,
                                quoted = null, sticker = null, loader = { byteArrayOf() }, onProfile = {}, onUserProfile = {},
                                onMenu = { menu.set(it) }, onRetry = {}, onImage = { _, _ -> }, onAttachment = null,
                                onVoice = {}, sending = stage.intValue == 1)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val playBounds = compose.onNodeWithContentDescription(playLabel, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val avatarBounds = compose.onNodeWithContentDescription(avatarLabel).getUnclippedBoundsInRoot()
        val pendingBounds = compose.onNodeWithTag("native-pending-voice-bubble").getUnclippedBoundsInRoot()
        assertEquals(48f, (pendingBounds.bottom - pendingBounds.top).value, .1f)
        assertEquals((pendingBounds.top + pendingBounds.bottom).value / 2, (avatarBounds.top + avatarBounds.bottom).value / 2, .1f)
        for (next in 1..2) {
            compose.runOnIdle { stage.intValue = next }
            compose.waitForIdle()
            assertEquals(playBounds, compose.onNodeWithContentDescription(playLabel, useUnmergedTree = true).getUnclippedBoundsInRoot())
            assertEquals(avatarBounds, compose.onNodeWithContentDescription(avatarLabel).getUnclippedBoundsInRoot())
            compose.onNodeWithTag("native-companion-message:voice-message").assertHeightIsEqualTo(48.dp)
            compose.onNodeWithText("A transcript").assertDoesNotExist()
            compose.onNodeWithText(fixture.context.getString(R.string.native_companion_show_transcript)).assertDoesNotExist()
        }
        compose.onNodeWithTag("native-companion-message:voice-message").performTouchInput {
            longClick(androidx.compose.ui.geometry.Offset(width * .7f, height / 2f))
        }
        compose.runOnIdle {
            assertEquals("voice-message", menu.get()?.id)
            assertEquals("A transcript", menu.get()?.voice?.transcript)
        }
    }
}
