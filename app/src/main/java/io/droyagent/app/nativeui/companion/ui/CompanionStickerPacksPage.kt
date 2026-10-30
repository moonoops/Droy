package io.droyagent.app.nativeui.companion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.ui.DroyIcon
import io.droyagent.app.nativeui.ui.DroySettingsToggle

@Composable
internal fun CompanionStickerPacksPage(companion: NativeCompanion, state: NativeCompanionState,
    model: NativeCompanionViewModel, onSave: (List<String>) -> Unit, onClose: () -> Unit) {
    val original = companion.settings.stickerPackIds ?: listOf("droy-default")
    var selected by rememberSaveable(companion.id, original) { mutableStateOf(ArrayList(original)) }
    var importOwner by rememberSaveable { mutableStateOf<String?>(null) }
    val dirty = selected.toSet() != original.toSet()
    val enabled = state.runtime.ready && !state.profileSaving && !state.stickerPackImporting
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val owner = importOwner
        importOwner = null
        if (uri != null && owner != null) model.importStickerPack(owner, uri)
    }
    CompanionEditorScaffold(stringResource(R.string.native_companion_sticker_packs), dirty, enabled,
        onSave = { onSave(selected.toList()) }, onClose = onClose, tag = "native-companion-sticker-packs-save",
        errorContent = { CompanionError(state.error, model::dismissError, modifier = Modifier.padding(horizontal = 20.dp)) }) { padding ->
        Column(Modifier.fillMaxSize().testTag("native-companion-sticker-packs-page").padding(padding)
            .consumeWindowInsets(padding).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.runtime.ready) CompanionRuntime(state.runtime, model::startRuntime)
            Text(stringResource(R.string.native_companion_sticker_packs_scope), style = MaterialTheme.typography.bodyLarge)
            if (state.loading && state.stickerPacks.isEmpty()) CircularProgressIndicator()
            else if (state.stickerPacks.isEmpty()) Text(stringResource(R.string.native_companion_sticker_packs_empty), style = MaterialTheme.typography.bodyMedium)
            else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                state.stickerPacks.forEachIndexed { index, pack ->
                    DroySettingsToggle(stringResource(R.string.native_companion_sticker_pack_count, pack.name, pack.count),
                        R.drawable.native_companion_sentiment_satisfied, pack.id in selected, enabled,
                        index, state.stickerPacks.size, Modifier.testTag("native-companion-sticker-pack-${pack.id}")) { checked ->
                        selected = ArrayList(if (checked) (selected + pack.id).distinct() else selected - pack.id)
                    }
                }
            }
            if (selected.isEmpty()) Text(stringResource(R.string.native_companion_sticker_packs_none_selected),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text(stringResource(R.string.native_companion_sticker_packs_import_scope), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = { importOwner = companion.id; picker.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                enabled = enabled && !dirty, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-companion-sticker-packs-import")) {
                if (state.stickerPackImporting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else DroyIcon(R.drawable.native_companion_attach_file, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (state.stickerPackImporting) R.string.native_companion_sticker_packs_importing else R.string.native_companion_sticker_packs_import))
            }
            if (dirty) Text(stringResource(R.string.native_companion_sticker_packs_save_before_import),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
