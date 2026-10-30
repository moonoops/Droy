package io.droyagent.app.nativeui.companion.creation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.ui.*
import io.droyagent.app.nativeui.ui.DroySettingsLink
import io.droyagent.app.nativeui.ui.DroySettingsLinkGroup
import io.droyagent.app.nativeui.ui.DroySwitch
import io.droyagent.app.nativeui.ui.DroyIcon

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun CreationEditor(state: NativeCreationState, model: NativeCompanionCreationViewModel, chooseImage: (NativeCreationImageKind) -> Unit, modifier: Modifier) {
    val editor = state.editor ?: return
    val invalidName = editor.name.isNotEmpty() && !NativeCreationNames.valid(editor.name)
    val nameLength = editor.name.codePointCount(0, editor.name.length)
    var avatarPreview by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("native-creation-editor"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CompanionAvatar(editor.name, editor.avatarUrl, model::loadImage, size = 96.dp,
                label = stringResource(R.string.native_companion_view_avatar),
                onClick = { avatarPreview = true }, modifier = Modifier.testTag("native-creation-avatar"))
            FilledTonalButton(onClick = { chooseImage(NativeCreationImageKind.AVATAR) }, shapes = ButtonDefaults.shapes(), enabled = state.runtime.ready && state.canEdit && state.image == null,
                modifier = Modifier.heightIn(min = 48.dp)) {
                DroyIcon(R.drawable.native_ui_photo_camera, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.native_companion_change_avatar))
            }
            if (editor.avatarUpload != null) TextButton(onClick = model::resetAvatar, enabled = state.canEdit, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.native_companion_creation_reset_avatar))
            }
        }
        CompanionFilledField(editor.name, { model.edit(editor.copy(name = it)) }, enabled = state.canEdit,
            label = { Text(stringResource(R.string.native_companion_name)) }, singleLine = true,
            isError = invalidName,
            supportingText = if (invalidName || nameLength >= 70) ({
                Text(if (invalidName) stringResource(R.string.native_companion_creation_invalid_name)
                    else stringResource(R.string.native_companion_creation_name_count, nameLength))
            }) else null, modifier = Modifier.fillMaxWidth().testTag("native-creation-name"))
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().toggleable(value = editor.settings.proactiveEnabled, enabled = state.canEdit, role = Role.Switch,
                onValueChange = { model.edit(editor.copy(settings = editor.settings.copy(proactiveEnabled = it))) }).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DroyIcon(R.drawable.native_settings_notifications, null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.native_companion_proactive), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.native_companion_proactive_dynamic), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DroySwitch(checked = editor.settings.proactiveEnabled, onCheckedChange = null, enabled = state.canEdit)
            }
        }
        DroySettingsLinkGroup(title = stringResource(R.string.native_companion_settings), enabled = state.canEdit, items = listOf(
            DroySettingsLink(stringResource(R.string.native_companion_personality), R.drawable.native_companion_person,
                "native-creation-personality") { model.page(NativeCreationPage.PERSONALITY) },
            DroySettingsLink(stringResource(R.string.native_companion_model), R.drawable.native_settings_hub,
                "native-creation-model", supporting = editor.model?.name ?: stringResource(R.string.native_companion_default_model)) { model.page(NativeCreationPage.MODEL) },
            DroySettingsLink(stringResource(R.string.native_companion_interaction), R.drawable.native_settings_tune,
                "native-creation-interaction") { model.page(NativeCreationPage.INTERACTION) },
        ))
    }
    if (avatarPreview) CompanionImageDialog(editor.avatarUrl, stringResource(R.string.native_companion_view_avatar), model::loadImage) { avatarPreview = false }
}

@Composable
internal fun CreationPersonality(state: NativeCreationState, model: NativeCompanionCreationViewModel, modifier: Modifier) {
    val editor = state.editor ?: return
    CompanionPersonalityPage(editor.personalityOverride, editor.template.personality, state.canEdit,
        { identity, behavior, reply -> model.edit(editor.copy(personalityOverride = identity, behaviorStyleOverride = behavior, replyStyleOverride = reply)); model.page(NativeCreationPage.EDITOR) },
        { model.page(NativeCreationPage.EDITOR) }, initialBehavior = editor.behaviorStyleOverride, initialReply = editor.replyStyleOverride,
        presetBehavior = editor.template.behaviorStyle, presetReply = editor.template.replyStyle,
        customBehavior = editor.template.customBehavior, customReply = editor.template.customReply)
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
