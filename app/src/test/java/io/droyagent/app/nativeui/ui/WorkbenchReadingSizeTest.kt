package io.droyagent.app.nativeui.ui

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class WorkbenchReadingSizeTest {
    @Test fun missingPreferenceUsesFourteenWithoutWritingAnOverride() {
        val preferences = ReadingPreferences()
        val reading = WorkbenchReadingSize(preferences.instance)
        assertEquals(14, reading.value)
        reading.reload()
        assertEquals(14, reading.value)
        assertNull(preferences.saved)
    }

    @Test fun existingExplicitSizeIsPreservedAndNewBoundsPersistAcrossRecreation() {
        val preferences = ReadingPreferences(16)
        val reading = WorkbenchReadingSize(preferences.instance)
        assertEquals(16, reading.value)
        listOf(6, 7, 14, 22).forEach { size ->
            reading.set(size)
            assertEquals(size, preferences.saved)
            assertEquals(size, WorkbenchReadingSize(preferences.instance).value)
        }
        preferences.saved = 6
        reading.reload()
        assertEquals(6, reading.value)
        preferences.saved = null
        reading.reload()
        assertEquals(14, reading.value)
    }

    @Test fun invalidWritesCannotReplaceTheSavedChoiceAndOldInvalidValuesAreClamped() {
        val preferences = ReadingPreferences(18)
        val reading = WorkbenchReadingSize(preferences.instance)
        listOf(5, 23).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { reading.set(size) }
            assertEquals(18, reading.value)
            assertEquals(18, preferences.saved)
            preferences.saved = size
            assertEquals(size.coerceIn(6, 22), WorkbenchReadingSize(preferences.instance).value)
            preferences.saved = 18
        }
    }

    private class ReadingPreferences(var saved: Int? = null) {
        private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args -> when (method.name) {
                "putInt" -> { check(args[0] == "workbenchTextSize"); saved = args[1] as Int; proxy }
                "apply" -> null
                else -> error(method.name)
            } } as SharedPreferences.Editor
        val instance = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args -> when (method.name) {
                "getInt" -> { check(args[0] == "workbenchTextSize"); saved ?: args[1] as Int }
                "edit" -> editor
                else -> error(method.name)
            } } as SharedPreferences
    }
}
