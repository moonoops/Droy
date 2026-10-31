package io.droyagent.app.nativeui.companion

import android.content.SharedPreferences
import io.droyagent.app.nativeui.companion.data.*
import java.lang.reflect.Proxy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeCompanionAppearanceTest {
    private val preset = """{"type":"preset","id":"03","position":{"x":20,"y":75}}"""

    @Test fun restoresOnlyBackgroundFieldsAndKeepsCropPosition() {
        val restored = NativeCompanionAppearance.read(mapOf("background:fixture" to preset, "draft:fixture" to "private draft"))
        assertEquals(setOf("fixture"), restored.keys)
        assertEquals(75, JSONObject(restored.getValue("fixture")).getJSONObject("position").getInt("y"))
        val state = NativeCompanionState(selectedCompanionId = "fixture", backgroundChoices = restored)
        assertTrue(state.companions.isEmpty())
        assertEquals(restored["fixture"], state.selectedBackgroundJson)
        assertNull(state.copy(selectedCompanionId = "other").selectedBackgroundJson)
    }

    @Test fun liveDefaultOverridesOldSnapshotRatherThanRestoringOldWallpaper() {
        val state = NativeCompanionState(selectedCompanionId = "fixture", backgroundChoices = mapOf("fixture" to preset),
            companions = listOf(NativeCompanion("fixture", "Example", "")))
        assertNull(state.selectedBackgroundJson)
        assertTrue(NativeCompanionAppearance.snapshot(state.companions).isEmpty())
    }

    @Test fun privateUploadCannotBeRestoredForAnotherOwnerAndInvalidHintsAreIgnored() {
        val upload = """{"type":"upload","id":"image","url":"/droy/companions/fixture/attachments/image"}"""
        val restored = NativeCompanionAppearance.read(mapOf("background:fixture" to upload, "background:other" to upload,
            "background:broken" to "{", "background:unknown" to """{"type":"preset","id":"../01"}"""))
        assertEquals(setOf("fixture"), restored.keys)
    }

    @Test fun resetAndDeletedProfilesPruneHintsWithoutTouchingDrafts() {
        val values = mutableMapOf<String, Any?>("background:removed" to preset, "background:reset" to preset,
            "background:retained" to preset, "draft:removed" to "draft")
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { _, method, args -> when (method.name) {
                "putString" -> { values[args[0] as String] = args[1]; editor }
                "remove" -> { values.remove(args[0]); editor }
                "apply" -> null
                else -> error(method.name)
            } } as SharedPreferences.Editor
        val preferences = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ -> when (method.name) {
                "getAll" -> values.toMap()
                "edit" -> editor
                else -> error(method.name)
            } } as SharedPreferences
        NativeCompanionAppearance.save(preferences, NativeCompanionAppearance.snapshot(listOf(
            NativeCompanion("reset", "Reset", ""), NativeCompanion("retained", "Retained", "", chatBackgroundJson = preset))))
        assertEquals(setOf("background:retained", "draft:removed"), values.keys)
    }
}
