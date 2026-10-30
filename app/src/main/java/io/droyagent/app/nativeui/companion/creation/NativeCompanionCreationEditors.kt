package io.droyagent.app.nativeui.companion.creation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.ui.CompanionAvatar
import io.droyagent.app.nativeui.companion.ui.*
import io.droyagent.app.nativeui.ui.DroyIcon
import org.json.JSONObject
import java.util.Locale

@Composable
internal fun CreationEditor(state: NativeCreationState, model: NativeCompanionCreationViewModel, chooseImage: (NativeCreationImageKind) -> Unit, modifier: Modifier) {
    val editor = state.editor ?: return
    var avatarPreview by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("native-creation-editor"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            CompanionAvatar(editor.name, editor.avatarUrl, model::loadImage, size = 96.dp,
                label = stringResource(R.string.native_companion_view_avatar),
                onClick = { avatarPreview = true }, modifier = Modifier.testTag("native-creation-avatar"))
            TextButton(onClick = { chooseImage(NativeCreationImageKind.AVATAR) }, enabled = state.runtime.ready && state.canEdit && state.image == null,
                modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_change_avatar)) }
            if (editor.avatarUpload != null) TextButton(onClick = model::resetAvatar, enabled = state.canEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_companion_creation_reset_avatar))
            }
        }
        OutlinedTextField(editor.name, { model.edit(editor.copy(name = it)) }, enabled = state.canEdit,
            label = { Text(stringResource(R.string.native_companion_name)) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            isError = editor.name.isNotEmpty() && !NativeCreationNames.valid(editor.name),
            supportingText = {
                if (editor.name.isNotEmpty() && !NativeCreationNames.valid(editor.name)) Text(stringResource(R.string.native_companion_creation_invalid_name))
                else if (editor.name.codePointCount(0, editor.name.length) >= 70) Text(stringResource(R.string.native_companion_creation_name_count, editor.name.codePointCount(0, editor.name.length)))
            }, modifier = Modifier.fillMaxWidth().testTag("native-creation-name"))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                CreationMenu(stringResource(R.string.native_companion_personality), state.canEdit, "native-creation-personality") { model.page(NativeCreationPage.PERSONALITY) }
                CreationMenu(stringResource(R.string.native_companion_model), state.canEdit, "native-creation-model",
                    editor.model?.name ?: stringResource(R.string.native_companion_default_model)) { model.page(NativeCreationPage.MODEL) }
                CreationMenu(stringResource(R.string.native_companion_interaction), state.canEdit, "native-creation-interaction") { model.page(NativeCreationPage.INTERACTION) }
            }
        }
    }
    if (avatarPreview) CompanionImageDialog(editor.avatarUrl, stringResource(R.string.native_companion_view_avatar), model::loadImage) { avatarPreview = false }
}

@Composable
internal fun CreationMenu(title: String, enabled: Boolean, tag: String = "", value: String? = null, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = if (value == null) null else ({ Text(value) }),
        trailingContent = { DroyIcon(R.drawable.native_companion_chevron_right, null) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow))
}

@Composable
internal fun CreationPersonality(state: NativeCreationState, model: NativeCompanionCreationViewModel, modifier: Modifier) {
    val editor = state.editor ?: return
    CompanionPersonalityPage(editor.personalityOverride, editor.template.personality, state.canEdit,
        { model.edit(editor.copy(personalityOverride = it)); model.page(NativeCreationPage.EDITOR) }, { model.page(NativeCreationPage.EDITOR) })
}

@Composable
internal fun CreationModels(state: NativeCreationState, model: NativeCompanionCreationViewModel, modifier: Modifier) {
    val editor = state.editor ?: return
    Column(modifier) {
        state.modelsError?.let { CreationError(it, model::refresh, refresh = true) }
        CompanionModelSelection(state.models, editor.model, state.defaultModel, state.canEdit,
            { model.edit(editor.copy(model = it)); model.page(NativeCreationPage.EDITOR) }, Modifier.fillMaxSize().navigationBarsPadding())
    }
}

@Composable
internal fun CreationInteraction(state: NativeCreationState, model: NativeCompanionCreationViewModel, modifier: Modifier, onExit: () -> Unit = {}) {
    val editor = state.editor ?: return
    CompanionInteractionPage(editor.settings, state.canEdit,
        { model.edit(editor.copy(settings = it)); onExit(); model.page(NativeCreationPage.EDITOR) },
        { onExit(); model.page(NativeCreationPage.EDITOR) }, { model.page(NativeCreationPage.BACKGROUND) })
}

@Composable
internal fun CreationBackground(state: NativeCreationState, model: NativeCompanionCreationViewModel, chooseImage: (NativeCreationImageKind) -> Unit, modifier: Modifier) {
    val editor = state.editor ?: return
    val original = rememberSaveable { editor.backgroundJson }
    CompanionBackgroundPage(original, model::loadImage, state.canEdit,
        if (state.runtime.ready && state.image == null) ({ _ -> chooseImage(NativeCreationImageKind.BACKGROUND) }) else null,
        { model.edit(editor.copy(backgroundJson = it)); model.page(NativeCreationPage.INTERACTION) },
        { model.edit(editor.copy(backgroundJson = original)); model.page(NativeCreationPage.INTERACTION) },
        incomingSelection = editor.backgroundJson.takeIf { it != original })
}
