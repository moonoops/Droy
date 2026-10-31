package io.droyagent.app.nativeui.media

import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeMediaBoundaryDeviceTest {
    @Test fun restoredNativePickerEntersUnifiedHumanGateAndReleasesWithItsViewModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Media boundary fixture must use the independent development app", context.packageName.endsWith(".dev"))
        val application = context.applicationContext as android.app.Application
        val before = NativeMediaViewModel.isPickerOpen()
        instrumentation.runOnMainSync {
            val store = ViewModelStore()
            try {
                val selection = NativeMediaSelection("native-gate-fixture", "kaka", NativeMediaKind.ATTACHMENTS, 1, false, launched = true)
                val record = JSONObject().put("revision", Long.MAX_VALUE - 1).put("selection", selection.json()).put("avatars", JSONArray()).toString()
                val model = NativeMediaViewModel(application, SavedStateHandle(mapOf("native-media-v1" to record)))
                val companions = NativeCompanionViewModel(application)
                store.put("media", model); store.put("companions", companions)
                model.bind(companions) {}
                assertTrue(NativeMediaViewModel.isPickerOpen())
                assertTrue("Product tools must see the native picker through the shared gate", NativeRuntimeHost.isFilePickerOpen())
                assertTrue(NativeMediaViewModel.hasRuntimeWork())
            } finally { store.clear() }
        }
        assertEquals(before, NativeMediaViewModel.isPickerOpen())
    }

    @Test fun externalViewerUriContainsOnlyTheControlledPrivateCopy() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "media-input/captures/native-media-test-${UUID.randomUUID()}")
        try {
            val bytes = "Synthetic local document".toByteArray()
            val file = NativeMediaCache(root).write(bytes, "fixture.txt")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.media-input-files", file)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.media-input-files", uri.authority)
            assertNull(uri.query)
            assertFalse(uri.toString().contains("127.0.0.1"))
            context.contentResolver.openInputStream(uri).use { input -> assertArrayEquals(bytes, requireNotNull(input).readBytes()) }
        } finally { root.deleteRecursively() }
    }
}
