package io.droyagent.app

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.droyagent.app.nativeui.data.NativeMessage
import io.droyagent.app.nativeui.data.NativeSessionReducer
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.ui.NativeMessageCard
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Controlled DSH frames exercise the production reducer and message card, without a cloud model. */
@RunWith(AndroidJUnit4::class)
class NativeReasoningSignatureDeviceTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val fixture = NativeIsolatedRuntimeFixture()

    @Test fun englishReasoningHidesStreamedSignatureAndCopiesDisplayText() = verify("en")
    @Test fun chineseReasoningHidesStreamedSignatureAndCopiesDisplayText() = verify("zh")

    private fun verify(language: String) {
        val thought = if (language == "zh") "先检查灯光状态，再调整亮度。" else "Check the light state before adjusting brightness."
        val answer = if (language == "zh") "已准备好调整灯光。" else "Ready to adjust the light."
        val request = if (language == "zh") "请检查灯光设置。" else "Please check the light settings."
        val reducer = NativeSessionReducer()
        val rows = mutableStateOf(emptyList<NativeMessage>())
        val copied = AtomicReference<String>()
        val configuration = Configuration(fixture.context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        val localized = fixture.context.createConfigurationContext(configuration)
        fixture.scenario.onActivity { activity -> activity.setContent {
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources) {
                DroyTheme { Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().statusBarsPadding().navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        NativeMessageCard(NativeMessage("request", "user", request), copied::set, {})
                        rows.value.forEach { NativeMessageCard(it, copied::set, {}) }
                    }
                } }
            }
        } }
        fun accept(frame: JSONObject) {
            compose.runOnIdle { assertTrue(reducer.accept(frame)); rows.value = reducer.messages() }
            compose.waitForIdle()
            compose.onAllNodesWithText("fixture.metadata", substring = true, useUnmergedTree = true).assertCountEquals(0)
            compose.onAllNodesWithText("<signature", substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
        accept(snapshot(0))
        accept(live(JSONObject().put("type", "start").put("attemptId", "signature")
            .put("turn", 1).put("step", 1).put("startedAfterSeq", 0)))
        val parts = listOf("<sig", "nature>fixture.metadata", "</signature>\r\n", thought)
        parts.forEachIndexed { index, text ->
            accept(live(JSONObject().put("type", "chunk").put("attemptId", "signature").put("index", index)
                .put("chunk", JSONObject().put("type", "reasoning-delta").put("index", 0).put("text", text))))
            if (index < 3) compose.onNodeWithTag("native-message-bubble:attempt:signature").assertDoesNotExist()
        }
        awaitText(thought)
        fixture.capture("dsh-rc5-reasoning-stream-$language")

        val committed = JSONObject().put("type", "event").put("event", JSONObject().put("type", "assistant/message")
            .put("seq", 1).put("time", 100).put("surfaceOp", "append").put("data", JSONObject().put("turn", 1).put("step", 1)
                .put("message", JSONObject().put("id", "answer").put("role", "assistant").put("content", JSONArray()
                    .put(JSONObject().put("type", "reasoning").put("text", parts.joinToString("")))
                    .put(JSONObject().put("type", "text").put("text", answer))))))
        val raw = committed.toString()
        accept(committed)
        awaitText(answer)
        compose.onNodeWithTag("native-message-bubble:attempt:signature").assertDoesNotExist()
        accept(snapshot(1, committed))
        awaitText(thought)
        awaitText(answer)
        compose.onNodeWithTag("native-message-bubble:answer").performTouchInput { longClick() }
        val copyLabel = localized.getString(R.string.native_copy_reply)
        compose.onNodeWithText(copyLabel).assertIsDisplayed()
        fixture.capture("dsh-rc5-reasoning-copy-$language")
        compose.onNodeWithText(copyLabel).performClick()
        assertEquals("$thought\n\n$answer", copied.get())
        assertEquals(raw, committed.toString())
        fixture.capture("dsh-rc5-reasoning-complete-$language")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text, useUnmergedTree = true).assertIsDisplayed()
    }
    private fun live(frame: JSONObject) = JSONObject().put("type", "assistant-stream").put("frame", frame)
    private fun snapshot(cursor: Int, vararg records: JSONObject) = JSONObject().put("type", "snapshot").put("cursor", cursor)
        .put("header", JSONObject().put("id", "reasoning-fixture")).put("hasMore", false).put("records", JSONArray(records))
        .put("projections", JSONObject().put("asOfSeq", cursor).put("values", JSONObject()))
}
