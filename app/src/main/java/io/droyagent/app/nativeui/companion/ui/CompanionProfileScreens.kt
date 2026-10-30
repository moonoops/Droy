package io.droyagent.app.nativeui.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.data.NativeModel
import io.droyagent.app.nativeui.ui.DroySettingsLink
import io.droyagent.app.nativeui.ui.DroySettingsLinkGroup
import io.droyagent.app.nativeui.ui.DroyIcon
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompanionProfileScreen(
    state: NativeCompanionState,
    model: NativeCompanionViewModel,
    companionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: CompanionImageLoader = model::loadImage,
    onChooseAvatar: CompanionAvatarPicker? = null,
    uploadedAvatarUrl: String? = null,
    onAvatarConsumed: () -> Unit = {},
    onChooseBackground: CompanionAvatarPicker? = null,
    onDeleteCompanion: ((String) -> Unit)? = null,
    uploadedBackgroundUrl: String? = null,
    onBackgroundConsumed: () -> Unit = {},
) {
    val companion = state.companions.firstOrNull { it.id == companionId }
    if (companion == null) {
        Scaffold(modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = { CompanionTopBar(stringResource(R.string.native_companion_profile), onBack) }) { padding ->
            Column(Modifier.padding(padding).padding(24.dp)) {
                if (state.loading) CircularProgressIndicator() else Text(stringResource(R.string.native_companion_error_profile))
                CompanionError(state.error, model::dismissError, model::refresh)
            }
        }
        return
    }
    var name by rememberSaveable(companionId) { mutableStateOf(companion.name) }
    var nameEdited by rememberSaveable(companionId) { mutableStateOf(false) }
    var avatar by rememberSaveable(companionId) { mutableStateOf(companion.avatarUrl) }
    var avatarEdited by rememberSaveable(companionId) { mutableStateOf(false) }
    var avatarPreview by rememberSaveable(companionId) { mutableStateOf(false) }
    var deleteConfirm by rememberSaveable(companionId) { mutableStateOf(false) }
    val editorStates = rememberSaveableStateHolder()
    var sheet by rememberSaveable(companionId) { mutableStateOf<String?>(null) }
    var submittedSheet by rememberSaveable(companionId) { mutableStateOf<String?>(null) }
    var discard by rememberSaveable(companionId) { mutableStateOf(false) }
    val consumeAvatar by rememberUpdatedState(onAvatarConsumed)
    val keyboard = LocalSoftwareKeyboardController.current
    val dirty = name.trim() != companion.name || avatar != companion.avatarUrl
    val imeVisible = WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    fun requestBack() { if (imeVisible) { focus.clearFocus(); keyboard?.hide() } else if (dirty && !state.profileSaving) discard = true else onBack() }
    BackHandler { requestBack() }
    LaunchedEffect(companion.name) {
        if (!nameEdited) name = companion.name
        if (name.trim() == companion.name) nameEdited = false
    }
    LaunchedEffect(companion.avatarUrl) {
        if (!avatarEdited) avatar = companion.avatarUrl
        if (avatar == companion.avatarUrl) avatarEdited = false
    }
    LaunchedEffect(companionId, uploadedAvatarUrl) {
        if (!uploadedAvatarUrl.isNullOrBlank()) {
            avatar = uploadedAvatarUrl
            avatarEdited = true
            consumeAvatar()
        }
    }
    LaunchedEffect(state.profileSaving, state.error, submittedSheet) {
        if (submittedSheet != null && !state.profileSaving) {
            if (state.error == null) {
                sheet = if (submittedSheet == "background") "interaction" else null
                if (submittedSheet == "interaction") editorStates.removeState("interaction")
            }
            submittedSheet = null
        }
    }
    val motion = io.droyagent.app.nativeui.ui.droyNavigationMotion()
    AnimatedContent(targetState = sheet?.takeIf { it in setOf("personality", "interaction", "background") } ?: "profile",
        transitionSpec = {
            val forward = targetState != "profile" && !(initialState == "background" && targetState == "interaction")
            ((if (forward) motion.enter else motion.popEnter) togetherWith
                (if (forward) motion.exit else motion.popExit)).using(null)
        }, label = "companion-profile-pages") { page ->
    if (page == "personality") {
        CompanionPersonalityPage(companion.personalityOverride, companion.presetPersonality.ifBlank { companion.personality }, state.runtime.ready && !state.profileSaving,
            { identity, behavior, reply -> submittedSheet = "personality"; model.updateProfile(companionId, NativeCompanionProfilePatch(personalityOverride = identity, behaviorStyleOverride = behavior, replyStyleOverride = reply)) }, { sheet = null },
            errorContent = { CompanionError(state.error, model::dismissError, modifier = Modifier.padding(horizontal = 20.dp)) },
            initialBehavior = companion.behaviorStyleOverride, initialReply = companion.replyStyleOverride,
            presetBehavior = companion.presetBehavior, presetReply = companion.presetReply,
            customBehavior = companion.customBehavior, customReply = companion.customReply)
    } else if (page == "interaction") {
        editorStates.SaveableStateProvider("interaction") {
            CompanionInteractionPage(companion.settings, state.runtime.ready && !state.profileSaving,
                { submittedSheet = "interaction"; model.updateProfile(companionId, NativeCompanionProfilePatch(settings = it)) },
                { sheet = null; editorStates.removeState("interaction") }, { sheet = "background" },
                errorContent = { CompanionError(state.error, model::dismissError, modifier = Modifier.padding(horizontal = 20.dp)) })
        }
    } else if (page == "background") {
        CompanionBackgroundPage(companion.chatBackgroundJson, imageLoader, state.runtime.ready && !state.profileSaving,
            onChooseBackground?.let { choose -> { selected -> choose(companionId, selected) } },
            { submittedSheet = "background"; model.updateProfile(companionId, NativeCompanionProfilePatch(chatBackgroundJson = it)) }, { sheet = "interaction" },
            incomingSelection = uploadedBackgroundUrl?.let { JSONObject().put("type", "upload").put("url", it).put("position", JSONObject().put("x", 50).put("y", 50)).toString() },
            onIncomingConsumed = onBackgroundConsumed,
            errorContent = { CompanionError(state.error, model::dismissError, modifier = Modifier.padding(horizontal = 20.dp)) })
    } else {
    Scaffold(modifier.fillMaxSize().testTag("native-companion-profile"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { CompanionTopBar(stringResource(R.string.native_companion_profile), ::requestBack) {
            ProfileSaveBar(state.profileSaving, enabled = state.runtime.ready && dirty && name.isNotBlank() && name.codePointCount(0, name.length) <= 80,
                tag = "native-companion-profile-save") {
                model.updateProfile(companionId, NativeCompanionProfilePatch(
                    name = name.trim().takeIf { it != companion.name }, avatarUrl = avatar.takeIf { it != companion.avatarUrl }))
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.runtime.ready) CompanionRuntime(state.runtime, model::startRuntime)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanionAvatar(name, avatar, imageLoader, size = 96.dp, label = stringResource(R.string.native_companion_view_avatar),
                    onClick = { avatarPreview = true },
                    modifier = Modifier.testTag("native-companion-avatar-edit"))
                FilledTonalButton(onClick = { onChooseAvatar?.invoke(companionId) { avatar = it; avatarEdited = true } },
                    enabled = !state.profileSaving && onChooseAvatar != null && state.runtime.ready,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    DroyIcon(R.drawable.native_ui_photo_camera, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.native_companion_change_avatar))
                }
            }
            ProfileNameField(name, { name = it; nameEdited = true }, state.profileSaving || !state.runtime.ready, nameEdited, "native-companion-name")
            DroySettingsLinkGroup(title = stringResource(R.string.native_companion_settings),
                enabled = !state.profileSaving && state.runtime.ready, items = listOf(
                    DroySettingsLink(stringResource(R.string.native_companion_personality), R.drawable.native_companion_person,
                        "native-companion-personality") { keyboard?.hide(); sheet = "personality" },
                    DroySettingsLink(stringResource(R.string.native_companion_model), R.drawable.native_settings_hub,
                        "native-companion-model", supporting = if (companion.modelInherited) stringResource(R.string.native_companion_default_model)
                            else companion.model?.name ?: stringResource(R.string.native_companion_default_model)) { keyboard?.hide(); sheet = "model" },
                    DroySettingsLink(stringResource(R.string.native_companion_interaction), R.drawable.native_settings_tune,
                        "native-companion-interaction") { keyboard?.hide(); sheet = "interaction" },
                ))
            onDeleteCompanion?.let { delete -> TextButton(onClick = { deleteConfirm = true }, enabled = !state.profileSaving,
                modifier = Modifier.fillMaxWidth().testTag("native-companion-delete-profile")) { Text(stringResource(R.string.native_companion_delete_profile), color = MaterialTheme.colorScheme.error) } }
            CompanionError(state.error, model::dismissError)
        }
    }
    }
    }
    if (avatarPreview) CompanionImageDialog(avatar, stringResource(R.string.native_companion_view_avatar), imageLoader) { avatarPreview = false }
    if (deleteConfirm) AlertDialog(onDismissRequest = { deleteConfirm = false }, title = { Text(stringResource(R.string.native_companion_delete_profile_title, companion.name)) },
        text = { Text(stringResource(R.string.native_companion_delete_profile_scope)) },
        confirmButton = { TextButton(onClick = { deleteConfirm = false; onDeleteCompanion?.invoke(companionId) }) { Text(stringResource(R.string.native_companion_confirm_delete)) } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text(stringResource(R.string.native_companion_cancel)) } })
    if (discard) DiscardProfileDialog(onDiscard = onBack, onCancel = { discard = false })
    if (sheet == "model") ModalBottomSheet(onDismissRequest = { sheet = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        CompanionError(state.error, model::dismissError, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        when (sheet) {
            "model" -> CompanionModelPicker(companion, state, onSelect = { choice ->
                submittedSheet = "model"
                model.updateProfile(companionId, NativeCompanionProfilePatch(model = choice, clearModel = choice == null))
            }, onClose = { sheet = null })

        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NativeUserProfileScreen(
    state: NativeCompanionState,
    model: NativeCompanionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    imageLoader: CompanionImageLoader = model::loadImage,
    onChooseAvatar: CompanionAvatarPicker? = null,
    uploadedAvatarUrl: String? = null,
    onAvatarConsumed: () -> Unit = {},
) {
    var avatarPreview by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf(state.userProfile.name) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    var bio by rememberSaveable { mutableStateOf(state.userProfile.bio) }
    var bioEdited by rememberSaveable { mutableStateOf(false) }
    var avatar by rememberSaveable { mutableStateOf(state.userProfile.avatarUrl) }
    var avatarEdited by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val consumeAvatar by rememberUpdatedState(onAvatarConsumed)
    val bioTooLong = bio.codePointCount(0, bio.length) > 2000
    val dirty = name.trim() != state.userProfile.name || avatar != state.userProfile.avatarUrl || bio.trim() != state.userProfile.bio
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val focus = LocalFocusManager.current
    fun requestBack() { if (imeVisible) { focus.clearFocus(); keyboard?.hide() } else if (dirty && !state.profileSaving) discard = true else onBack() }
    BackHandler { requestBack() }
    LaunchedEffect(state.userProfile.name) {
        if (!nameEdited) name = state.userProfile.name
        if (name.trim() == state.userProfile.name) nameEdited = false
    }
    LaunchedEffect(state.userProfile.bio) {
        if (!bioEdited) bio = state.userProfile.bio
        if (bio.trim() == state.userProfile.bio) bioEdited = false
    }
    LaunchedEffect(state.userProfile.avatarUrl) {
        if (!avatarEdited) avatar = state.userProfile.avatarUrl
        if (avatar == state.userProfile.avatarUrl) avatarEdited = false
    }
    LaunchedEffect(null, uploadedAvatarUrl) {
        if (!uploadedAvatarUrl.isNullOrBlank()) {
            avatar = uploadedAvatarUrl
            avatarEdited = true
            consumeAvatar()
        }
    }
    Scaffold(modifier.fillMaxSize().testTag("native-user-profile"), containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { CompanionTopBar(stringResource(R.string.native_companion_my_profile), ::requestBack) {
            ProfileSaveBar(state.profileSaving, state.runtime.ready && dirty && name.isNotBlank() && name.codePointCount(0, name.length) <= 80 && !bioTooLong, "native-user-profile-save") {
                model.updateUserProfile(name.trim(), avatar.takeIf { it != state.userProfile.avatarUrl }, bio.trim())
            }
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!state.runtime.ready) CompanionRuntime(state.runtime, model::startRuntime)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanionAvatar(name, avatar, imageLoader, size = 96.dp, label = stringResource(R.string.native_companion_view_avatar),
                    onClick = { avatarPreview = true },
                    modifier = Modifier.testTag("native-user-avatar-edit"))
                FilledTonalButton(onClick = { onChooseAvatar?.invoke(null) { avatar = it; avatarEdited = true } },
                    enabled = !state.profileSaving && onChooseAvatar != null && state.runtime.ready,
                    modifier = Modifier.heightIn(min = 48.dp)) {
                    DroyIcon(R.drawable.native_ui_photo_camera, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.native_companion_change_avatar))
                }
            }
            ProfileNameField(name, { name = it; nameEdited = true }, state.profileSaving || !state.runtime.ready, nameEdited, "native-user-name")
            CompanionFilledField(bio, { bio = it; bioEdited = true },
                Modifier.fillMaxWidth().testTag("native-user-bio"),
                label = { Text(stringResource(R.string.native_companion_user_bio)) },
                enabled = !state.profileSaving && state.runtime.ready, minLines = 3, maxLines = 6,
                isError = bioTooLong,
                supportingText = if (bioTooLong) ({ Text(stringResource(R.string.native_companion_user_bio_too_long)) }) else null,
                )
            CompanionError(state.error, model::dismissError)
        }
    }
    if (avatarPreview) CompanionImageDialog(avatar, stringResource(R.string.native_companion_view_avatar), imageLoader) { avatarPreview = false }
    if (discard) DiscardProfileDialog(onDiscard = onBack, onCancel = { discard = false })
}

@Composable
private fun ProfileNameField(name: String, onChange: (String) -> Unit, saving: Boolean, edited: Boolean, tag: String) {
    val invalid = edited && (name.isBlank() || name.codePointCount(0, name.length) > 80)
    CompanionFilledField(name, onChange, Modifier.fillMaxWidth().testTag(tag), label = { Text(stringResource(R.string.native_companion_name)) },
        enabled = !saving, singleLine = true, isError = invalid,
        supportingText = if (invalid) ({ Text(stringResource(if (name.isBlank()) R.string.native_companion_name_required else R.string.native_companion_name_too_long)) }) else null)
}

@Composable
private fun ProfileSaveBar(saving: Boolean, enabled: Boolean, tag: String, onSave: () -> Unit) {
    TextButton(onClick = onSave, enabled = enabled && !saving, modifier = Modifier.heightIn(min = 48.dp).testTag(tag)) {
        Text(stringResource(if (saving) R.string.native_companion_saving else R.string.native_companion_save))
    }
}

@Composable
private fun DiscardProfileDialog(onDiscard: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(onDismissRequest = onCancel, title = { Text(stringResource(R.string.native_companion_discard_title)) },
        confirmButton = { TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_discard)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_companion_keep_editing)) } })
}

@Composable
private fun CompanionModelPicker(companion: NativeCompanion, state: NativeCompanionState, onSelect: (NativeModel?) -> Unit, onClose: () -> Unit) {
    CompanionModelSelection(state.models, companion.model.takeUnless { companion.modelInherited }, state.defaultModel,
        state.runtime.ready && !state.profileSaving, onSelect, Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding(), showTitle = true)
}
