package io.droyagent.app.nativeui.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.droyagent.app.R
import io.droyagent.app.NativeRuntimeHost
import io.droyagent.app.NativeCompanionDeletion
import io.droyagent.app.NativeProductSettingsHost
import io.droyagent.app.NativePetStudioHost
import io.droyagent.app.NativePersonalizationReset
import io.droyagent.app.NativeCameraInputHost
import io.droyagent.app.NativeStandardVoiceHost
import io.droyagent.app.nativeui.companion.data.*
import io.droyagent.app.nativeui.companion.ui.*
import io.droyagent.app.nativeui.data.*
import io.droyagent.app.nativeui.permissions.*
import io.droyagent.app.nativeui.schedule.*
import io.droyagent.app.nativeui.settings.*
import io.droyagent.app.nativeui.memory.*
import io.droyagent.app.nativeui.voice.*
import io.droyagent.app.nativeui.advanced.*
import io.droyagent.app.nativeui.productsettings.*
import io.droyagent.app.nativeui.petstudio.NativePetStudioScreen
import io.droyagent.app.nativeui.companion.creation.*
import io.droyagent.app.nativeui.engine.*
import io.droyagent.app.nativeui.tools.*
import io.droyagent.app.nativeui.media.NativeSharePreview
import io.droyagent.app.nativeui.media.NativeSharePreviewViewModel
import io.droyagent.app.nativeui.media.NativeAvatarResult
import io.droyagent.app.nativeui.voiceinput.*
import io.droyagent.app.nativeui.workspaces.*
import io.droyagent.app.nativeui.presentation.LocalNativeProductPresentation
import io.droyagent.app.nativeui.standardvoice.*
import io.droyagent.app.nativeui.navigation.*
import io.droyagent.app.nativeui.transcript.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.launch

data class NativeShellMedia(
    val chooseAttachments: ((String) -> Unit)? = null,
    val chooseImages: ((String) -> Unit)? = null,
    val chooseCamera: ((String) -> Unit)? = null,
    val chooseAvatar: CompanionAvatarPicker? = null,
    val chooseBackground: CompanionBackgroundPicker? = null,
    val openAttachment: ((String, NativeCompanionAttachment) -> Unit)? = null,
    val playVoice: ((String, NativeCompanionVoice) -> Unit)? = null,
    val avatarUrl: (String?) -> String? = { null },
    val backgroundResult: (String?) -> NativeAvatarResult? = { null },
    val consumeBackground: (String?) -> Unit = {},
    val consumeAvatar: (String?) -> Unit = {},
    val conversationChanged: (String?) -> Unit = {},
    val error: String? = null,
    val retry: (() -> Unit)? = null,
    val dismissError: () -> Unit = {},
    val notice: String? = null,
    val dismissNotice: () -> Unit = {},
)

private val rootRoutes = setOf("messages", "workbench", "settings")

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NativeAppShell(
    work: NativeWorkbenchState,
    workModel: NativeWorkbenchViewModel,
    companions: NativeCompanionState,
    companionModel: NativeCompanionViewModel,
    settings: NativeSettingsState,
    settingsModel: NativeSettingsViewModel,
    permissions: NativePermissionsState,
    permissionAction: (NativePermissionAction) -> Unit,
    permissionVisible: (Boolean) -> Unit,
    schedules: NativeSchedulesState,
    schedulesModel: NativeSchedulesViewModel,
    externalNavigation: NativeExternalNavigationViewModel,
    transcriptModel: NativeTranscriptViewModel,
    onOpenAudioAuthorization: (NativeExternalResolvedTarget.AudioAuthorization) -> Unit,
    media: NativeShellMedia = NativeShellMedia(),
    memoryModel: NativeMemoryViewModel,
    voiceModel: NativeVoiceViewModel,
    advancedModel: NativeAdvancedViewModel,
    onOpenUrl: (String) -> Unit,
    creationModel: NativeCompanionCreationViewModel,
    onChooseCreationImage: (NativeCreationImageKind) -> Unit,
    engineModel: NativeEngineViewModel,
    toolsModel: NativeToolsViewModel,
    shareModel: NativeSharePreviewViewModel,
    voiceInputModel: NativeVoiceInputViewModel,
    standardVoiceModel: NativeStandardVoiceViewModel,
    workspacesModel: NativeWorkspacesViewModel,
    onChooseWorkbenchAttachments: (Boolean) -> Unit,
    onChooseWorkbenchCamera: () -> Unit,
    onRefreshPresentation: () -> Unit = {},
    companionDeletion: NativeCompanionDeletion.Snapshot = NativeCompanionDeletion.Snapshot("idle", "", "", false, false, false),
    onDeleteCompanion: ((String) -> Boolean)? = null,
    onResumeCompanionDeletion: () -> Unit = {},
) {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val external by externalNavigation.state.collectAsStateWithLifecycle()
    val externalError by externalNavigation.error.collectAsStateWithLifecycle()
    val transcript by transcriptModel.state.collectAsStateWithLifecycle()
    val tools by toolsModel.state.collectAsStateWithLifecycle()
    var navigatedRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var startedWakeRequest by rememberSaveable { mutableStateOf<String?>(null) }
    var productReady by remember { mutableStateOf<ProductSettingsSection?>(null) }
    var pageResumed by remember(entry) { mutableStateOf(entry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) }
    DisposableEffect(entry) {
        val lifecycle = entry?.lifecycle
        val observer = LifecycleEventObserver { _, _ -> pageResumed = lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }
    var settingsRoot by rememberSaveable { mutableStateOf(true) }
    var settingsBottomInset by remember { mutableStateOf(0.dp) }
    val shellDensity = LocalDensity.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.native_retry)
    val presentationError = LocalNativeProductPresentation.current.error
    val presentationFailure = stringResource(R.string.native_presentation_unavailable)
    LaunchedEffect(presentationError) {
        if (presentationError != null && snackbar.showSnackbar(presentationFailure, retryLabel) == SnackbarResult.ActionPerformed)
            onRefreshPresentation()
    }
    val motion = droyNavigationMotion()
    val context = LocalContext.current
    val productHost = remember(context.applicationContext) { NativeProductSettingsHost(context.applicationContext) }
    val productPresentation = remember(productHost) { io.droyagent.app.nativeui.productsettings.ProductSettingsPresentationState() }
    val petStudioHost = remember(context.applicationContext) { NativePetStudioHost(context.applicationContext) }
    val resetter = remember(context.applicationContext, settingsModel) { NativePersonalizationReset(context.applicationContext) {
        settingsModel.refresh(); companionModel.refresh()
    } }
    val shared by shareModel.state.collectAsStateWithLifecycle()
    val voiceInput by voiceInputModel.state.collectAsStateWithLifecycle()
    val standardVoice by standardVoiceModel.state.collectAsStateWithLifecycle()
    val voiceSettings by voiceModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val shellScope = rememberCoroutineScope()
    val deletionFailed = stringResource(R.string.native_maintenance_failed)
    val copied = stringResource(R.string.native_copied)
    val copyFailed = stringResource(R.string.native_copy_failed)
    val workspaceState by workspacesModel.state.collectAsStateWithLifecycle()
    val workspaceFeedback = workspaceState.feedback
    val workspaceFeedbackText = stringResource(if (workspaceFeedback == "fork_title_unconfirmed")
        R.string.native_workspaces_fork_title_unconfirmed else R.string.native_workspaces_changed)
    LaunchedEffect(workspaceFeedback) {
        if (workspaceFeedback != null) {
            workModel.refresh()
            workspacesModel.onAction(NativeWorkspacesAction.ConsumeFeedback)
            if (workspaceFeedback != "changed") snackbar.showSnackbar(workspaceFeedbackText)
        }
    }
    var createdCompanion by rememberSaveable { mutableStateOf<String?>(null) }
    var presentedShare by rememberSaveable { mutableStateOf<String?>(null) }
    var acceptedShare by rememberSaveable { mutableStateOf<String?>(null) }
    var shareWasOpen by rememberSaveable { mutableStateOf(false) }
    var authorizeVoice by rememberSaveable { mutableStateOf(false) }
    var authorizeStandardVoice by rememberSaveable { mutableStateOf(false) }
    var authorizeStandardWake by rememberSaveable { mutableStateOf<Long?>(null) }
    var standardVoiceExpanded by rememberSaveable { mutableStateOf(false) }
    val standardVoiceVisible = standardVoiceExpanded && route in setOf("workbench", "work-session/{id}")
    var presetToCreate by rememberSaveable { mutableStateOf<String?>(null) }
    var presetDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetSpaceName by rememberSaveable { mutableStateOf("") }
    var permissionWasOpen by rememberSaveable { mutableStateOf(false) }
    fun back() { keyboard?.hide(); navigation.popBackStack() }
    fun open(page: String) {
        keyboard?.hide()
        if (page == "standard-voice") {
            standardVoiceExpanded = true
            if (route !in setOf("workbench", "work-session/{id}")) navigation.navigate("workbench") { launchSingleTop = true }
            return
        }
        if (route == "permissions" && page != "permissions") permissionAction(NativePermissionAction.CancelQueue)
        navigation.navigate(page) { launchSingleTop = true }
    }
    fun root(page: String, restoreScene: Boolean = true) {
        keyboard?.hide()
        if (route == "permissions") permissionAction(NativePermissionAction.CancelQueue)
        if (!restoreScene) navigation.clearBackStack(page)
        navigation.navigate(page) {
            popUpTo(navigation.graph.findStartDestination().id) { saveState = restoreScene }
            launchSingleTop = true
            restoreState = restoreScene
        }
    }
    fun openCompanion(id: String) { companionModel.selectCompanion(id); open("chat/${Uri.encode(id)}") }
    LaunchedEffect(companionDeletion.phase(), companionDeletion.companionId(), entry) {
        // A recovered completion can arrive before NavHost restores its first destination.
        if (entry == null) return@LaunchedEffect
        if (companionDeletion.phase() == "complete") {
            companionModel.refresh()
            workModel.refresh()
            root("messages", restoreScene = false)
            NativeCompanionDeletion.dismissResult(context.applicationContext)
        } else if (companionDeletion.phase() == "failed" && !companionDeletion.blocksEditing()) {
            snackbar.showSnackbar(deletionFailed)
            NativeCompanionDeletion.dismissResult(context.applicationContext)
        }
    }
    fun openWork(id: String) { workModel.selectSession(id); workModel.refresh(); open("work-session/${Uri.encode(id)}") }
    fun openWorkspaceSession(id: String) {
        val catalog = workspacesModel.state.value.catalog ?: return
        val row = catalog.sessions.firstOrNull { it.id == id } ?: return
        if (row.origin == "subagent") {
            externalNavigation.accept(NativeExternalNavigationTarget.EngineSession(id))
            return
        }
        workModel.selectSession(NativeSession(row.id, row.title ?: row.id, row.running, row.workspaceId, row.readOnly, row.id in catalog.archivedSessionIds))
        workModel.refresh()
        open("work-session/${Uri.encode(id)}")
    }
    fun openSkill(sessionId: String, path: String) { toolsModel.selectSession(sessionId); toolsModel.openFile(path); open("tools") }
    fun openWorkLink(url: String) {
        val parsed = Uri.parse(url)
        if (parsed.scheme in setOf("http", "https")) {
            toolsModel.openBrowser(url)
            open("tools")
        }
        else if (parsed.scheme == null && parsed.authority == null && parsed.query == null && parsed.fragment == null && url.isNotBlank()) {
            work.selectedSessionId?.let { openSkill(it, url) }
        } else onOpenUrl(url)
    }
    fun openVoice(target: NativeVoiceInputTarget) {
        keyboard?.hide()
        voiceInputModel.open(target)
        if (voiceInputModel.state.value.target != target) open("voice-recovery")
    }
    fun startStandardVoice(wakeEventId: Long? = null) {
        if (!NativeStandardVoiceHost.productAccessGranted(context)) {
            authorizeStandardVoice = true; authorizeStandardWake = wakeEventId
        } else if (wakeEventId != null) standardVoiceModel.startFromWake(wakeEventId)
        else standardVoiceModel.onAction(NativeStandardVoiceAction.Start)
    }
    fun standardVoiceAction(action: NativeStandardVoiceAction) {
        when (action) {
            NativeStandardVoiceAction.Back -> { standardVoiceModel.onAction(action); standardVoiceExpanded = false }
            NativeStandardVoiceAction.Start -> startStandardVoice()
            NativeStandardVoiceAction.OpenConversation -> {
                standardVoiceExpanded = false
                standardVoice.journal?.submissions?.mapNotNull { it.receipt?.sessionId }?.lastOrNull()?.let(::openWork)
            }
            is NativeStandardVoiceAction.Copy -> standardVoiceModel.retainedText(action.nonce)?.let { text ->
                val message = try { clipboard.setText(AnnotatedString(text)); copied } catch (_: SecurityException) { copyFailed }
                shellScope.launch { snackbar.showSnackbar(message) }
            }
            else -> standardVoiceModel.onAction(action)
        }
    }
    fun chooseCamera(capture: () -> Unit) {
        val facts = NativeCameraInputHost.inspect(context)
        when {
            facts.paused -> open("permissions")
            !facts.granted -> permissionAction(NativePermissionAction.RequestSystem("camera"))
            else -> capture()
        }
    }
    fun createPresetDraft(preset: String, expectedDraft: String) {
        val current = workModel.state.value
        if (current.sending || current.pendingRequestId != null || current.draftId != expectedDraft || current.readOnly) return
        workModel.createSession()
        engineModel.stagePreset(workModel.state.value.draftId, preset)
        root("workbench", restoreScene = false)
    }
    fun showingVoiceOwner(owner: NativeVoiceInputTarget?): Boolean = route == "voice-recovery"
        || route == "chat/{id}" && owner?.companionId == entry?.arguments?.getString("id")
        || route in setOf("workbench", "work-session/{id}") && owner?.workbench?.let(workModel::isCurrentMediaTarget) == true
    fun voiceAction(action: NativeVoiceInputAction) {
        val sent = voiceInputModel.state.value
        voiceInputModel.onAction(action)
        if (action == NativeVoiceInputAction.Done && sent.accepted && sent.visible && showingVoiceOwner(sent.target)) {
            if (route == "voice-recovery") navigation.popBackStack()
            when {
                sent.target?.companionId != null -> openCompanion(sent.target.companionId)
                sent.resultSessionId != null -> openWork(sent.resultSessionId)
            }
        }
    }
    LaunchedEffect(voiceInput.accepted, voiceInput.operationId) {
        if (voiceInput.accepted) voiceAction(NativeVoiceInputAction.Done)
    }
    val materializedEngineSession = work.selectedSessionId?.takeIf { selected ->
        work.sessionBaselineId == selected || work.sessions.any { it.id == selected }
    }
    LaunchedEffect(materializedEngineSession, work.draftId) {
        // A first send selects its future ID before DSH has created the session.
        engineModel.bindSession(materializedEngineSession)
        engineModel.bindDraft(if (materializedEngineSession == null) work.draftId else null)
    }
    LaunchedEffect(shared.importId, shared.pendingImportIds, work.workspaceResolved) {
        if (shared.importId == null && work.workspaceResolved) shareModel.advanceInbox(workModel::mediaTarget)
    }
    LaunchedEffect(shared.visible, shared.requestId, route) {
        if (route == null) return@LaunchedEffect
        if (shared.visible && !shared.accepted && shared.requestId != presentedShare) {
            presentedShare = shared.requestId
            open("share")
        }
    }
    LaunchedEffect(route) {
        if (route == null) return@LaunchedEffect
        if (route == "share") shareWasOpen = true
        else if (shareWasOpen) { shareWasOpen = false; if (shareModel.state.value.visible) shareModel.cancel() }
        if (route == "permissions") permissionWasOpen = true
        else if (permissionWasOpen) { permissionWasOpen = false; permissionAction(NativePermissionAction.CancelQueue) }
    }
    LaunchedEffect(standardVoiceVisible) {
        standardVoiceModel.setVisible(standardVoiceVisible)
        if (!standardVoiceVisible) { authorizeStandardVoice = false; authorizeStandardWake = null }
    }
    LaunchedEffect(route, work.runtime.ready, voiceSettings.saving, voiceSettings.snapshot?.standardEntry) {
        if (work.runtime.ready && !voiceSettings.saving && route in setOf("workbench", "work-session/{id}", "standard-voice"))
            standardVoiceModel.refreshEntry()
    }
    LaunchedEffect(route, entry?.arguments?.getString("id"), work.selectedSessionId, work.draftId, voiceInput.target, voiceInput.visible) {
        if (route == null || !voiceInput.visible) return@LaunchedEffect
        if (!showingVoiceOwner(voiceInput.target)) voiceInputModel.leave()
    }
    LaunchedEffect(shared.accepted, shared.requestId, shared.visible) {
        val id = shared.requestId ?: return@LaunchedEffect
        if (shared.accepted && shared.visible && acceptedShare != id) {
            acceptedShare = id
            val target = shared.destination
            val sessionId = shared.resultSessionId
            if (route == "share") navigation.popBackStack()
            shareWasOpen = false
            when {
                target?.companionId != null -> openCompanion(target.companionId)
                sessionId != null -> openWork(sessionId)
                else -> root("workbench")
            }
        }
    }
    LaunchedEffect(shared.accepted, shared.requestId) {
        if (shared.accepted) shared.requestId?.let(shareModel::consumeAccepted)
    }
    LaunchedEffect(createdCompanion, companions.companions, route) {
        val id = createdCompanion ?: return@LaunchedEffect
        if (route != "new-companion") { createdCompanion = null; return@LaunchedEffect }
        if (companions.companions.any { it.id == id }) {
            createdCompanion = null
            navigation.popBackStack()
            openCompanion(id)
        }
    }

    LaunchedEffect(settings.defaultModel?.provider, settings.defaultModel?.id) {
        if (settings.defaultModel != null) { workModel.refreshModels(); companionModel.refresh() }
    }
    LaunchedEffect(external.request?.id, external.phase, external.resolved, entry != null, companions.companions,
        shared.importId, shared.loading, work.workspaceResolved) {
        if (entry == null) return@LaunchedEffect
        val request = external.request ?: return@LaunchedEffect
        if (external.phase != NativeExternalNavigationPhase.READY || navigatedRequest == request.id) return@LaunchedEffect
        when (val target = external.resolved ?: return@LaunchedEffect) {
            is NativeExternalResolvedTarget.EngineSession -> {
                val row = target.session
                if (row.owner == NativeExternalSessionOwner.SUBAGENT) {
                    transcriptModel.open(row.parentSessionId!!, row.id, null)
                    open("transcript/${Uri.encode(row.parentSessionId)}/${Uri.encode(row.id)}")
                } else {
                    workModel.selectSession(NativeSession(row.id, work.sessions.firstOrNull { it.id == row.id }?.title ?: row.id,
                        false, row.workspaceId, row.readOnly, row.archived))
                    open("work-session/${Uri.encode(row.id)}")
                }
            }
            is NativeExternalResolvedTarget.CompanionTask -> {
                val row = target.session
                workModel.selectSession(NativeSession(row.id, work.sessions.firstOrNull { it.id == row.id }?.title ?: row.id,
                    false, row.workspaceId, true, row.archived))
                open("work-session/${Uri.encode(row.id)}")
            }
            is NativeExternalResolvedTarget.CompanionChat -> {
                if (companions.companions.none { it.id == target.companionId }) { companionModel.refresh(); return@LaunchedEffect }
                openCompanion(target.companionId)
            }
            is NativeExternalResolvedTarget.BrowserTab -> { toolsModel.selectBrowser(target.browserSessionId); toolsModel.open(NativeToolsPage.BROWSER); open("tools") }
            NativeExternalResolvedTarget.NotificationManagement -> open("product/notifications")
            is NativeExternalResolvedTarget.VoiceWake -> open("standard-voice")
            is NativeExternalResolvedTarget.AudioAuthorization -> onOpenAudioAuthorization(target)
            is NativeExternalResolvedTarget.ImportedMedia -> {
                if (!work.workspaceResolved) return@LaunchedEffect
                shareModel.advanceInbox(workModel::mediaTarget)
                if (shared.importId != target.previewImportId) return@LaunchedEffect
                shareModel.showPending(); open("share")
            }
            is NativeExternalResolvedTarget.Root -> root(target.page.wire)
        }
        navigatedRequest = request.id
    }
    LaunchedEffect(external.request?.id, external.phase, route, standardVoiceVisible, pageResumed, work.workspaceResolved) {
        val request = external.request ?: return@LaunchedEffect
        val target = external.resolved as? NativeExternalResolvedTarget.VoiceWake ?: return@LaunchedEffect
        if (external.phase == NativeExternalNavigationPhase.READY && standardVoiceVisible && pageResumed
            && work.workspaceResolved && startedWakeRequest != request.id) {
            startedWakeRequest = request.id; startStandardVoice(target.eventId)
        }
    }
    LaunchedEffect(external, route, entry?.arguments?.getString("id"), pageResumed, work.sessionBaselineId,
        companions.selectedCompanionId, companions.loading, companions.selectedCompanion, transcript.baselineReady, transcript.live,
        tools.browser, shared.importId, shared.visible, shared.loading, standardVoiceVisible, standardVoice.journal, productReady, work.workspaceResolved) {
        val request = external.request ?: return@LaunchedEffect
        val target = external.resolved ?: return@LaunchedEffect
        if (external.phase != NativeExternalNavigationPhase.READY || !pageResumed) return@LaunchedEffect
        val ready = when (target) {
            is NativeExternalResolvedTarget.EngineSession -> if (target.session.owner == NativeExternalSessionOwner.SUBAGENT)
                route == "transcript/{parent}/{child}" && transcript.address?.parentSessionId == target.session.parentSessionId
                    && transcript.address?.childSessionId == target.session.id && transcript.baselineReady && transcript.live
                else route == "work-session/{id}" && entry?.arguments?.getString("id") == target.session.id && work.sessionBaselineId == target.session.id
            is NativeExternalResolvedTarget.CompanionTask -> route == "work-session/{id}" && entry?.arguments?.getString("id") == target.session.id && work.sessionBaselineId == target.session.id
            is NativeExternalResolvedTarget.CompanionChat -> route == "chat/{id}" && entry?.arguments?.getString("id") == target.companionId
                && companions.selectedCompanionId == target.companionId && companions.selectedCompanion != null && !companions.loading && companions.error == null
            is NativeExternalResolvedTarget.BrowserTab -> route == "tools" && tools.page == NativeToolsPage.BROWSER && tools.browser?.id == target.browserSessionId && tools.browser?.open == true
            NativeExternalResolvedTarget.NotificationManagement -> route == "product/{section}" && entry?.arguments?.getString("section") == "notifications" && productReady == ProductSettingsSection.Notifications
            is NativeExternalResolvedTarget.ImportedMedia -> route == "share" && shared.importId == target.previewImportId && shared.visible && !shared.loading && shared.destination != null
            is NativeExternalResolvedTarget.VoiceWake -> standardVoiceVisible && standardVoice.journal?.wake?.let { it.eventId == target.eventId && it.phase != NativeStandardVoiceWakePhase.REJECTED } == true
            is NativeExternalResolvedTarget.Root -> route == target.page.wire
            is NativeExternalResolvedTarget.AudioAuthorization -> false
        }
        if (ready) externalNavigation.acknowledge(request.id, NativeExternalNavigationOpened(target.surface, true, true))
    }
    val pendingPermission = permissions.pendingRequests.firstOrNull()
    LaunchedEffect(authorizeVoice, authorizeStandardVoice, pendingPermission?.id, presetToCreate, companionDeletion.blocksEditing()) {
        externalNavigation.setModalBlocked(authorizeVoice || authorizeStandardVoice || pendingPermission != null || presetToCreate != null || companionDeletion.blocksEditing())
    }
    DisposableEffect(externalNavigation) { onDispose { externalNavigation.setModalBlocked(false) } }
    LaunchedEffect(route, pendingPermission?.id) {
        permissionVisible(route == "permissions" || pendingPermission != null)
        if (pendingPermission != null) keyboard?.hide()
    }
    DisposableEffect(Unit) { onDispose { permissionVisible(false) } }
    val displayedConversation = if (route == "chat/{id}") entry?.arguments?.getString("id") else null
    LaunchedEffect(displayedConversation) { media.conversationChanged(displayedConversation) }
    LaunchedEffect(media.error) {
        val error = media.error ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(error, actionLabel = if (media.retry != null) retryLabel else null, withDismissAction = true)
        media.dismissError()
        if (result == SnackbarResult.ActionPerformed) media.retry?.invoke()
    }
    LaunchedEffect(media.notice) {
        media.notice?.let { snackbar.showSnackbar(it, withDismissAction = true); media.dismissNotice() }
    }
    val rootPage = route in rootRoutes && (route != "settings" || settingsRoot)
    DisposableEffect(route, pageResumed, work.selectedSessionId) {
        workModel.presentSession(work.selectedSessionId.takeIf { pageResumed && (route == "workbench" || route == "work-session/{id}") })
        onDispose { workModel.presentSession(null) }
    }
    val workVoiceVisible = voiceInput.visible && voiceInput.target?.workbench?.let(workModel::isCurrentMediaTarget) == true
    val workPendingVoiceId = voiceInput.operationId?.takeIf { operation ->
        workVoiceVisible && voiceInput.showPendingBubble && work.messages.none { it.requestId == operation || it.id == "request:$operation" }
    }
    val pendingVoiceBubble: @Composable () -> Unit = { NativePendingVoiceBubble(voiceInput, ::voiceAction) }
    val voicePanel: @Composable () -> Unit = {
        NativeVoiceInputPanel(voiceInput, ::voiceAction, onRequestPermissions = { authorizeVoice = true },
            onOpenPermissions = { open("permissions") }, onOpenVoiceSettings = { open("voice") })
    }
    val inlineVoice: @Composable () -> Unit = {
        NativeVoiceInputStatus(voiceInput, ::voiceAction, onOpenPermissions = { open("permissions") },
            onOpenVoiceSettings = { open("voice") })
    }
    val workMic: @Composable () -> Unit = {
        NativeHoldToRecordButton(voiceInput, onBegin = {
            workModel.mediaTargetOrNull()?.let { target ->
                voiceInputModel.open(NativeVoiceInputTarget(workbench = target))
                voiceInputModel.beginHold()
            }
        }, onAction = ::voiceAction, onRequestPermissions = { authorizeVoice = true })
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("native-app-shell"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                if (companionDeletion.phase() == "waiting") {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_maintenance_waiting), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = NativeCompanionDeletion::cancel, enabled = companionDeletion.canCancel()) {
                                Text(stringResource(R.string.native_companion_cancel))
                            }
                        }
                    }
                }
                if (!companionDeletion.active() && !companionDeletion.blocksEditing() && !work.runtime.ready && !work.runtime.active && route !in setOf("workbench", "work-session/{id}")) {
                    Surface(color = MaterialTheme.colorScheme.errorContainer) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.native_engine_start_failed), Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = workModel::startRuntime, enabled = work.runtime.canStart) { Text(retryLabel) }
                        }
                    }
                }
                NativeExternalNavigationNotice(external, externalError, onRetry = {
                    navigatedRequest = null; externalNavigation.retry()
                }, onDismiss = externalNavigation::dismiss,
                    onStartRuntime = workModel::startRuntime, canStartRuntime = work.runtime.canStart)
            val sharePending = shared.importId != null && route != "share" || shared.pendingImportIds.isNotEmpty() && shared.importId == null
            val voicePending = voiceInput.operationId != null && !voiceInput.visible
            val standardPending = !standardVoiceVisible && standardVoice.journal != null
                && (standardVoice.phase !in setOf(NativeStandardVoicePhase.IDLE, NativeStandardVoicePhase.ENDED) || standardVoice.pending.isNotEmpty())
            if (sharePending || voicePending || standardPending) Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sharePending) TextButton(onClick = {
                        if (shared.importId != null) shareModel.showPending() else if (work.workspaceResolved) shareModel.advanceInbox(workModel::mediaTarget)
                        open("share")
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-pending-share")) { Text(stringResource(R.string.native_shell_pending_share)) }
                    if (voicePending) TextButton(onClick = {
                        voiceInput.target?.let(voiceInputModel::open); open("voice-recovery")
                    }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-pending-voice")) { Text(stringResource(R.string.native_shell_pending_voice)) }
                    if (standardPending) TextButton(onClick = { open("standard-voice") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-pending-standard-voice")) { Text(stringResource(R.string.nsv_title)) }
                }
            }
            }
        },
        bottomBar = {
            if (rootPage && !WindowInsets.isImeVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.testTag("native-root-navigation").onSizeChanged { size ->
                    if (size.height > 0) settingsBottomInset = with(shellDensity) { size.height.toDp() }
                }) {
                val tabs = listOf(
                    Triple("messages", R.string.native_shell_messages, R.drawable.native_ui_chat_bubble),
                    Triple("workbench", R.string.native_workbench, R.drawable.native_ui_workbench),
                    Triple("settings", R.string.native_shell_settings, R.drawable.native_settings_settings),
                )
                tabs.forEach { (id, title, icon) ->
                    val unread = when (id) {
                        "messages" -> companions.companions.sumOf { it.unreadCount }
                        "workbench" -> (work.unreadSessionIds + work.decisions.map { it.sessionId }).count { session ->
                            work.sessions.any { it.id == session && !it.readOnly }
                        }
                        else -> 0
                    }
                    NavigationBarItem(selected = route == id, onClick = { root(id) }, icon = {
                        BadgedBox(badge = {
                            if (unread > 0) {
                                val description = stringResource(R.string.native_review_unread, unread)
                                Badge(Modifier.semantics { contentDescription = description }) { Text(if (unread > 99) "99+" else unread.toString()) }
                            }
                        }) { DroyIcon(icon, null) }
                    },
                        label = { Text(stringResource(title)) }, modifier = Modifier.testTag("native-tab-$id"))
                }
            }
        },
    ) { padding ->
        NavHost(navigation, startDestination = "messages", modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            enterTransition = { if (initialState.destination.route in rootRoutes && targetState.destination.route in rootRoutes) motion.rootEnter else motion.enter },
            exitTransition = { if (initialState.destination.route in rootRoutes && targetState.destination.route in rootRoutes) motion.rootExit else motion.exit },
            popEnterTransition = { if (initialState.destination.route in rootRoutes && targetState.destination.route in rootRoutes) motion.rootEnter else motion.popEnter },
            popExitTransition = { if (initialState.destination.route in rootRoutes && targetState.destination.route in rootRoutes) motion.rootExit else motion.popExit }) {
            composable("messages") {
                CompanionMessagesScreen(companions, companionModel, onOpenChat = ::openCompanion,
                    onOpenUserProfile = { open("user-profile") }, onAddCompanion = { open("new-companion") })
            }
            composable("new-companion") {
                ObserveNativePage(creationModel::startObserving, creationModel::stopObserving)
                val state by creationModel.state.collectAsStateWithLifecycle()
                if (createdCompanion == null) NativeCompanionCreationScreen(state, creationModel, onBack = ::back,
                    onCreated = { id -> createdCompanion = id; companionModel.refresh() }, onChooseImage = onChooseCreationImage)
                else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (companions.error == null) { CircularProgressIndicator(); Text(stringResource(R.string.native_loading)) }
                    else CompanionError(companions.error, companionModel::dismissError, companionModel::refresh)
                    TextButton(onClick = { createdCompanion = null; back() }) { Text(stringResource(R.string.native_companion_back)) }
                }
            }
            composable("workbench") {
                WorkbenchScreen(work, workModel, onConfigureModels = { open("settings-models") }, onSchedules = { open("schedules") },
                    onChooseAttachments = onChooseWorkbenchAttachments,
                    onVoice = { workModel.mediaTargetOrNull()?.let { openVoice(NativeVoiceInputTarget(workbench = it)) } },
                    onStandardVoice = { open("standard-voice") },
                    voiceVisible = workVoiceVisible, voicePanel = voicePanel,
                    voiceButton = workMic, voiceStatus = { if (workVoiceVisible) inlineVoice() },
                    pendingVoiceId = workPendingVoiceId, pendingVoiceBubble = pendingVoiceBubble,
                    onLoadAttachment = { message, attachment ->
                        val session = work.selectedSessionId
                        if (session != null && message.canOpenAttachment(attachment))
                            toolsModel.loadMessageAttachment(session, message.seq!!, attachment.attachmentId!!)
                        else workModel.loadLocalAttachment(requireNotNull(attachment.local))
                    },
                    onOpenAttachment = { message, attachment ->
                        val session = work.selectedSessionId
                        val seq = message.seq
                        val id = attachment.attachmentId
                        if (session != null && seq != null && id != null) { toolsModel.openAttachment(session, seq, id); open("tools") }
                    }, onOpenLink = ::openWorkLink, engineModel = engineModel, onPreviewSkill = ::openSkill,
                    onCamera = { chooseCamera(onChooseWorkbenchCamera) }, onSpaces = { open("spaces") })
            }
            composable("spaces") {
                ObserveNativePage(workspacesModel::startObserving, workspacesModel::stopObserving)
                val state = workspaceState
                NativeWorkspacesScreen(state, workspacesModel, work.selectedWorkspaceId, work.selectedSessionId,
                    unreadSessionIds = work.unreadSessionIds,
                    onBack = ::back, onOpenSession = ::openWorkspaceSession,
                    hasWorkspaceDraft = workModel::hasWorkspaceDraft,
                    onOpenWorkspaceDraft = { space ->
                        val pending = workModel.workspacePendingSession(space)
                        if (pending != null) openWork(pending)
                        else if (workModel.openDraft(space)) root("workbench", restoreScene = false)
                    },
                    onNewInSpace = { space -> if (workModel.openDraft(space)) root("workbench", restoreScene = false) })
            }
            composable("settings-models") {
                NativeModelSettingsScreen(settings, settingsModel, onBack = ::back, embedded = false)
            }
            composable("settings") {
                // Removing the shell bar during exit must not expand this list's viewport and
                // clamp its scroll offset before Navigation saves the outgoing destination.
                val retainedInset = remember(padding) { object : PaddingValues {
                    override fun calculateLeftPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = 0.dp
                    override fun calculateRightPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = 0.dp
                    override fun calculateTopPadding() = 0.dp
                    // Scaffold updates its padding during measurement, before recomposition.
                    override fun calculateBottomPadding() = if (settingsRoot)
                        (settingsBottomInset - padding.calculateBottomPadding()).coerceAtLeast(0.dp) else 0.dp
                } }
                NativeSettingsScreen(settings, settingsModel,
                    modifier = Modifier.padding(retainedInset).consumeWindowInsets(retainedInset), onMyProfile = { open("user-profile") },
                    onPermissions = { open("permissions") }, onModels = { open("settings-models") }, onRootChanged = { settingsRoot = it },
                    onOpenFeature = { feature -> open(when (feature) {
                        NativeSettingsFeature.GENERAL -> "product/general"
                        NativeSettingsFeature.MEMORY -> "memory"
                        NativeSettingsFeature.VOICE -> "voice"
                        NativeSettingsFeature.TOOLS -> "tools"
                        NativeSettingsFeature.NOTIFICATIONS -> "product/notifications"
                        NativeSettingsFeature.NOTIFICATION_SORTING -> "product/notifications"
                        NativeSettingsFeature.PET -> "product/pet"
                        NativeSettingsFeature.WEB_SEARCH -> "web-search"
                        NativeSettingsFeature.PLUGINS -> "plugins"
                        NativeSettingsFeature.ADVANCED -> "advanced"
                        NativeSettingsFeature.ABOUT -> "product/diagnostics"
                    }) })
            }
            composable("standard-voice") {
                LaunchedEffect(Unit) { standardVoiceExpanded = true; root("workbench") }
            }
            composable("memory") {
                ObserveNativePage(memoryModel::startObserving, memoryModel::stopObserving)
                val state by memoryModel.state.collectAsStateWithLifecycle()
                NativeMemoryScreen(state, memoryModel, onBack = ::back, onOpenPermissions = { open("permissions") })
            }
            composable("voice") {
                ObserveNativePage(voiceModel::startObserving, voiceModel::stopObserving)
                LaunchedEffect(permissions) { voiceModel.updatePermissions(permissions) }
                val state by voiceModel.state.collectAsStateWithLifecycle()
                NativeVoiceScreen(state, voiceModel::onAction, onBack = ::back, onPermissions = { open("permissions") })
            }
            composable("plugins") {
                io.droyagent.app.nativeui.settings.NativePluginSettingsScreen(::back) { section -> open(when (section) {
                    "web-search" -> "web-search"
                    "subtask-models" -> "plugin-subtask-models"
                    "agent-loop" -> "plugin-agent-loop"
                    "terminal" -> "plugin-terminal"
                    else -> "approved-plugin/$section"
                }) }
            }
            composable("approved-plugin/{id}") { entry ->
                ObserveNativePage(advancedModel::startObserving, advancedModel::stopObserving)
                val state by advancedModel.state.collectAsStateWithLifecycle()
                io.droyagent.app.nativeui.advanced.NativeApprovedPluginScreen(
                    entry.arguments?.getString("id") ?: "agent-team", state, advancedModel, ::back,
                    onOpenTeam = { open("agent-team") })
            }
            composable("agent-team") {
                io.droyagent.app.nativeui.team.NativeAgentTeamScreen(work.selectedSessionId, ::back,
                    onOpenSession = { externalNavigation.accept(NativeExternalNavigationTarget.EngineSession(it)) })
            }
            composable("plugin-subtask-models") {
                NativeSubtaskModelSettingsScreen(settings, settingsModel, onBack = ::back)
            }
            composable("plugin-agent-loop") {
                ObserveNativePage(advancedModel::startObserving, advancedModel::stopObserving)
                val state by advancedModel.state.collectAsStateWithLifecycle()
                NativeAdvancedScreen(state, advancedModel, onBack = ::back, onOpenUrl = onOpenUrl, requestedSection = "agent-loop")
            }
            composable("plugin-terminal") {
                ObserveNativePage(advancedModel::startObserving, advancedModel::stopObserving)
                val state by advancedModel.state.collectAsStateWithLifecycle()
                NativeAdvancedScreen(state, advancedModel, onBack = ::back, onOpenUrl = onOpenUrl, requestedSection = "bash-sandbox")
            }
            composable("advanced") {
                NativeSettingsGroup(stringResource(R.string.native_advanced_title), ::back,
                    listOf(
                        Triple("presets", stringResource(R.string.native_engine_presets), R.drawable.native_ui_task_type),
                        Triple("permissions", stringResource(R.string.native_engine_permissions), R.drawable.native_engine_shield),
                        Triple("plugins", stringResource(R.string.native_shell_plugins_accounts), R.drawable.native_settings_hub),
                    )) { section -> open(if (section == "plugins") "advanced-plugins" else "engine/$section") }
            }
            composable("advanced-plugins") {
                ObserveNativePage(advancedModel::startObserving, advancedModel::stopObserving)
                val state by advancedModel.state.collectAsStateWithLifecycle()
                NativeAdvancedScreen(state, advancedModel, onBack = ::back, onOpenUrl = onOpenUrl)
            }
            composable("engine/{section}", arguments = listOf(navArgument("section") { type = NavType.StringType })) { current ->
                val section = if (current.arguments!!.getString("section") == "permissions") NativeEngineSettingsSection.PERMISSIONS else NativeEngineSettingsSection.PRESETS
                val state by engineModel.state.collectAsStateWithLifecycle()
                NativeEngineSettingsScreen(state, engineModel::onAction, onBack = ::back, section = section,
                    onCreatePreset = { preset ->
                        if (!workModel.state.value.sending && workModel.state.value.pendingRequestId == null) {
                            val before = workModel.state.value
                            if (before.readOnly || before.selectedSessionId != null)
                                workModel.openDraft(if (before.readOnly) null else before.selectedWorkspaceId)
                            val target = workModel.state.value
                            if (target.selectedSessionId == null && (target.draft.isNotBlank() || target.draftAttachments.isNotEmpty())) {
                                presetDraftId = target.draftId
                                presetSpaceName = target.workspaces.firstOrNull { it.id == target.selectedWorkspaceId }?.title
                                    ?: context.getString(R.string.native_ungrouped)
                                presetToCreate = preset
                            } else createPresetDraft(preset, target.draftId)
                        }
                    })
            }
            composable("tools") {
                val state by toolsModel.state.collectAsStateWithLifecycle()
                NativeToolsScreen(state, toolsModel, onBack = ::back, focusedContent = true)
            }
            composable("share") {
                NativeSharePreview(shared, companions.companions, shareModel::setText,
                    onSelectWorkbench = { workModel.mediaTargetOrNull()?.let(shareModel::selectWorkbench) },
                    onSelectCompanion = shareModel::selectCompanion, onRemoveAttachment = shareModel::removeAttachment,
                    onConfirm = { if (shared.accepted) { shared.requestId?.let(shareModel::consumeAccepted); back() } else shareModel.confirm() },
                    onCancel = { shareModel.cancel(); back() }, loadAttachment = shareModel::loadAttachment,
                    modifier = Modifier.fillMaxSize().testTag("native-share-preview"))
            }
            composable("voice-recovery") {
                val target = voiceInput.target
                val title = if (target?.companionId != null) companions.companions.firstOrNull { it.id == target.companionId }?.name
                    ?: stringResource(R.string.native_media_owner_unavailable)
                else target?.workbench?.sessionId?.let { id -> work.sessions.firstOrNull { it.id == id }?.title }
                    ?: stringResource(R.string.native_workbench)
                Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), { voiceInputModel.leave(); back() })
                        Text(title, style = MaterialTheme.typography.titleLarge)
                    }
                }) { padding -> Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().verticalScroll(rememberScrollState())) { voicePanel() } }
                LaunchedEffect(voiceInput.visible, voiceInput.operationId) { if (!voiceInput.visible && voiceInput.operationId == null) back() }
            }
            composable("notifications-and-pet") {
                NativeSettingsGroup(stringResource(R.string.native_settings_notifications), ::back,
                    listOf(
                        Triple("notifications", stringResource(R.string.nps_notifications), R.drawable.native_settings_notifications),
                        Triple("proactive", stringResource(R.string.nps_proactive), R.drawable.native_settings_notifications),
                        Triple("pet", stringResource(R.string.nps_pet), R.drawable.native_settings_pets),
                    )) { section -> open("product/$section") }
            }
            composable("product/{section}", arguments = listOf(navArgument("section") { type = NavType.StringType })) { current ->
                val section = ProductSettingsSection.entries.first { it.wire == current.arguments!!.getString("section") }
                NativeProductSettingsScreen(productHost, ProductSettingsActions(onBack = ::back,
                    onPermissions = { open("permissions") },
                    onOverlayPermission = { open("permissions"); permissionAction(NativePermissionAction.RequestSystem("petOverlay")) },
                    onStartRuntime = { NativeRuntimeHost.start(context) }, onStopRuntime = { NativeRuntimeHost.stop(context) },
                    onOpenPetStudio = { open("pet-studio") }, onResetPersonalization = { resetter.reset() },
                    resetPersonalizationReady = work.runtime.ready), initialSection = section, presentation = productPresentation,
                    generalPreferences = { NativeGeneralPreferences(settings, settingsModel) },
                    onReady = { ready -> productReady = section.takeIf { ready } })
            }
            composable("pet-studio") { NativePetStudioScreen(petStudioHost, onBack = ::back) }
            composable("permissions") {
                NativePermissionsScreen(permissions, permissionAction, onBack = ::back,
                    onMemoryAccess = { memoryModel.page(NativeMemoryPage.ENDPOINTS); open("memory") })
            }
            composable("web-search") {
                NativeWebSearchSettingsScreen(settings, settingsModel, onBack = ::back)
            }
            composable("schedules") {
                ObserveSchedulePage(schedulesModel)
                NativeSchedulesScreen(schedules, schedulesModel, onBack = ::back, onOpenSession = ::openWork,
                    onCreateConversation = {
                        shellScope.launch {
                            try {
                                if (workModel.openScheduledTaskDraft(context.getString(R.string.native_review_schedule_request)))
                                    root("workbench", restoreScene = false)
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                snackbar.showSnackbar(context.getString(R.string.native_error_generic))
                            }
                        }
                    })
            }
            composable("user-profile") {
                NativeUserProfileScreen(companions, companionModel, onBack = ::back,
                    onChooseAvatar = media.chooseAvatar, uploadedAvatarUrl = media.avatarUrl(null), onAvatarConsumed = { media.consumeAvatar(null) })
            }
            composable("chat/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { current ->
                val id = current.arguments!!.getString("id")!!
                LaunchedEffect(id) { if (companions.selectedCompanionId != id) companionModel.selectCompanion(id) }
                if (companions.selectedCompanionId == id) CompanionChatScreen(companions, companionModel, onBack = ::back,
                    onConfigureModels = { open("settings-models") },
                    onOpenCompanionProfile = { open("profile/${Uri.encode(it)}") }, onOpenUserProfile = { open("user-profile") },
                    onChooseAttachments = media.chooseAttachments?.let { choose -> { choose(id) } },
                    onOpenAttachment = media.openAttachment?.let { show -> { show(id, it) } },
                    onPlayVoice = media.playVoice?.let { play -> { play(id, it) } },
                    onChooseImages = media.chooseImages?.let { choose -> { choose(id) } },
                    onCamera = media.chooseCamera?.let { capture -> { chooseCamera { capture(id) } } },
                    voiceButton = {
                        NativeHoldToRecordButton(voiceInput, onBegin = {
                            voiceInputModel.open(NativeVoiceInputTarget(companionId = id)); voiceInputModel.beginHold()
                        }, onAction = ::voiceAction, onRequestPermissions = { authorizeVoice = true })
                    },
                    voiceStatus = { if (voiceInput.target?.companionId == id) inlineVoice() },
                    pendingVoiceKey = voiceInput.operationId?.takeIf { operation ->
                        voiceInput.target?.companionId == id && voiceInput.showPendingBubble && companions.messages.none { it.clientId == operation }
                    }, pendingVoiceBubble = pendingVoiceBubble,
                    onRetryVoiceTranscript = companionModel::retryVoiceTranscript,
                    onOpenTask = { task ->
                        val taskId = task.id; val sessionId = task.sessionId
                        if (taskId != null && sessionId != null) externalNavigation.accept(NativeExternalNavigationTarget.CompanionTask(id, taskId, sessionId))
                    })
            }
            composable("profile/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { current ->
                val id = current.arguments!!.getString("id")!!
                CompanionProfileScreen(companions, companionModel, id, onBack = ::back,
                    onChooseAvatar = media.chooseAvatar, uploadedAvatarUrl = media.avatarUrl(id), onAvatarConsumed = { media.consumeAvatar(id) },
                    onChooseBackground = media.chooseBackground, uploadedBackground = media.backgroundResult(id),
                    onBackgroundConsumed = { media.consumeBackground(id) },
                    onDeleteCompanion = onDeleteCompanion?.let { begin -> { owner ->
                        if (!begin(owner)) shellScope.launch { snackbar.showSnackbar(deletionFailed) }
                    } })
            }
            composable("work-session/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { current ->
                val id = current.arguments!!.getString("id")!!
                LaunchedEffect(id) { if (work.selectedSessionId != id) { workModel.selectSession(id); workModel.refresh() } }
                if (work.selectedSessionId == id) WorkbenchScreen(work, workModel, onConfigureModels = { open("settings-models") }, onSchedules = { open("schedules") }, onBack = ::back,
                    onChooseAttachments = onChooseWorkbenchAttachments,
                    onStandardVoice = { open("standard-voice") },
                    onVoice = { workModel.mediaTargetOrNull()?.let { openVoice(NativeVoiceInputTarget(workbench = it)) } },
                    voiceVisible = workVoiceVisible, voicePanel = voicePanel,
                    voiceButton = workMic, voiceStatus = { if (workVoiceVisible) inlineVoice() },
                    pendingVoiceId = workPendingVoiceId, pendingVoiceBubble = pendingVoiceBubble,
                    onLoadAttachment = { message, attachment ->
                        if (message.canOpenAttachment(attachment)) toolsModel.loadMessageAttachment(id, message.seq!!, attachment.attachmentId!!)
                        else workModel.loadLocalAttachment(requireNotNull(attachment.local))
                    },
                    onOpenAttachment = { message, attachment ->
                        val seq = message.seq
                        val attachmentId = attachment.attachmentId
                        if (seq != null && attachmentId != null) { toolsModel.openAttachment(id, seq, attachmentId); open("tools") }
                    }, onOpenLink = ::openWorkLink, engineModel = engineModel, onPreviewSkill = ::openSkill,
                    onCamera = { chooseCamera(onChooseWorkbenchCamera) }, onSpaces = { open("spaces") },
                    onDraftOpened = { root("workbench", restoreScene = false) })
            }
            composable("transcript/{parent}/{child}", arguments = listOf(
                navArgument("parent") { type = NavType.StringType }, navArgument("child") { type = NavType.StringType })) { current ->
                val parent = current.arguments!!.getString("parent")!!; val child = current.arguments!!.getString("child")!!
                LaunchedEffect(parent, child) { transcriptModel.open(parent, child, null) }
                ObserveNativePage(transcriptModel::startObserving, transcriptModel::stopObserving)
                NativeTranscriptScreen(transcript, transcriptModel, onBack = ::back, onOpenLink = onOpenUrl)
            }
        }
    }
    NativeMemoryAccessPrompt(runtimeReady = work.runtime.ready && !companionDeletion.blocksEditing())
    if (companionDeletion.blocksEditing()) AlertDialog(
        onDismissRequest = {}, modifier = Modifier.testTag("native-companion-maintenance"),
        title = { Text(stringResource(if (companionDeletion.active()) R.string.native_maintenance_cleaning else R.string.native_maintenance_failed)) },
        text = { if (companionDeletion.active()) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        } },
        confirmButton = { if (!companionDeletion.active()) TextButton(onClick = onResumeCompanionDeletion) { Text(retryLabel) } },
    )
    if (standardVoiceVisible && !authorizeStandardVoice && pendingPermission == null) {
        ModalBottomSheet(onDismissRequest = { standardVoiceAction(NativeStandardVoiceAction.Back) },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            NativeStandardVoicePanel(standardVoice, ::standardVoiceAction,
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                canInsertRetained = standardVoiceModel.canInsertRetained())
        }
    }
    if (pendingPermission != null && route != "permissions") NativePermissionRequestSheet(permissions, pendingPermission, permissionAction,
        sourceLabel = work.sessions.firstOrNull { it.id == pendingPermission.taskId }?.title
            ?: companions.companions.firstOrNull { it.chatSessionId == pendingPermission.taskId }?.name)
    if (authorizeVoice || authorizeStandardVoice) AlertDialog(onDismissRequest = { authorizeVoice = false; authorizeStandardVoice = false; authorizeStandardWake = null },
        modifier = Modifier.testTag("native-voice-access-confirmation"),
        title = { Text(stringResource(R.string.native_voice_input_permission)) },
        confirmButton = { TextButton(onClick = {
            val standard = authorizeStandardVoice; val wake = authorizeStandardWake
            authorizeVoice = false; authorizeStandardVoice = false; authorizeStandardWake = null
            permissionAction(NativePermissionAction.GrantVoiceCapabilities)
            if (standard) {
                if (standardVoiceVisible && NativeStandardVoiceHost.productAccessGranted(context)) startStandardVoice(wake)
            } else permissionAction(NativePermissionAction.RequestSystem("microphone"))
        }, modifier = Modifier.testTag("native-voice-access-allow")) { Text(stringResource(R.string.native_voice_input_authorize)) } },
        dismissButton = { TextButton(onClick = { authorizeVoice = false; authorizeStandardVoice = false; authorizeStandardWake = null },
            modifier = Modifier.testTag("native-voice-access-cancel")) { Text(stringResource(R.string.native_companion_cancel)) } })
    if (presetToCreate != null) AlertDialog(onDismissRequest = { presetToCreate = null },
        title = { Text(stringResource(R.string.native_shell_discard_space_draft, presetSpaceName)) },
        confirmButton = { TextButton(onClick = {
            val preset = presetToCreate; val draft = presetDraftId; presetToCreate = null
            if (preset != null && draft != null) createPresetDraft(preset, draft)
        }) { Text(stringResource(R.string.native_companion_discard)) } },
        dismissButton = { TextButton(onClick = { presetToCreate = null }) { Text(stringResource(R.string.native_companion_cancel)) } })
    LaunchedEffect(work.draftId) { if (presetToCreate != null && presetDraftId != work.draftId) presetToCreate = null }
}

@Composable
private fun ObserveSchedulePage(model: NativeSchedulesViewModel) {
    ObserveNativePage(model::startObserving, model::stopObserving)
}

@Composable
private fun ObserveNativePage(start: () -> Unit, stop: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val startPage by rememberUpdatedState(start)
    val stopPage by rememberUpdatedState(stop)
    DisposableEffect(lifecycle) {
        fun update() { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startPage() else stopPage() }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); stopPage() }
    }
}

@Composable
private fun NativeSettingsGroup(title: String, onBack: () -> Unit, items: List<Triple<String, String, Int>>, onOpen: (String) -> Unit) {
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize().testTag("native-settings-group"), topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroyIconAction(R.drawable.native_companion_arrow_back, stringResource(R.string.native_companion_back), onBack)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items.forEach { (id, label, icon) ->
                OutlinedCard(onClick = { onOpen(id) }, modifier = Modifier.fillMaxWidth().testTag("native-settings-group:$id")) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) { DroyIcon(icon, null); Text(label, style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }
    }
}
