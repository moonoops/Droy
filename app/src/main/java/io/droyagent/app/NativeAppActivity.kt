package io.droyagent.app

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.droyagent.app.nativeui.data.NativeWorkbenchViewModel
import io.droyagent.app.nativeui.companion.data.NativeCompanionViewModel
import io.droyagent.app.nativeui.companion.maintenance.NativeCompanionLocalMaintenance
import io.droyagent.app.nativeui.settings.NativeSettingsViewModel
import io.droyagent.app.nativeui.media.NativeMediaCoordinator
import io.droyagent.app.nativeui.schedule.NativeSchedulesViewModel
import io.droyagent.app.nativeui.setup.NativeOnboardingViewModel
import io.droyagent.app.nativeui.setup.NativeOnboardingScreen
import io.droyagent.app.nativeui.permissions.NativePermissionAction
import io.droyagent.app.nativeui.memory.NativeMemoryViewModel
import io.droyagent.app.nativeui.voice.NativeVoiceViewModel
import io.droyagent.app.nativeui.advanced.NativeAdvancedViewModel
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationViewModel
import io.droyagent.app.nativeui.companion.creation.NativeCompanionCreationMediaCoordinator
import io.droyagent.app.nativeui.media.NativeSharePreviewViewModel
import io.droyagent.app.nativeui.engine.NativeEngineViewModel
import io.droyagent.app.nativeui.tools.NativeToolsViewModel
import io.droyagent.app.nativeui.voiceinput.NativeVoiceInputViewModel
import io.droyagent.app.nativeui.workspaces.NativeWorkspacesViewModel
import io.droyagent.app.nativeui.presentation.LocalNativeProductPresentation
import io.droyagent.app.nativeui.presentation.NativeProductPresentationViewModel
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoiceViewModel
import io.droyagent.app.nativeui.standardvoice.NativeStandardVoiceWakePhase
import io.droyagent.app.nativeui.navigation.*
import io.droyagent.app.nativeui.transcript.NativeTranscriptViewModel
import io.droyagent.app.nativeui.ui.DroyTheme
import io.droyagent.app.nativeui.ui.NativeAppShell
import io.droyagent.app.nativeui.ui.NativeShellMedia
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private class NativeMaintenanceOwner(val participant: NativeCompanionLocalMaintenance) : ViewModel()

/** Native product entry. Runtime and device capabilities remain owned by their services. */
class NativeAppActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[NativeWorkbenchViewModel::class.java] }
    private val companions by lazy { ViewModelProvider(this)[NativeCompanionViewModel::class.java] }
    private val settings by lazy { ViewModelProvider(this)[NativeSettingsViewModel::class.java] }
    private val schedules by lazy { ViewModelProvider(this)[NativeSchedulesViewModel::class.java] }
    private val onboarding by lazy { ViewModelProvider(this)[NativeOnboardingViewModel::class.java] }
    private val memoryModel by lazy { ViewModelProvider(this)[NativeMemoryViewModel::class.java] }
    private val voiceModel by lazy { ViewModelProvider(this)[NativeVoiceViewModel::class.java] }
    private val advancedModel by lazy { ViewModelProvider(this)[NativeAdvancedViewModel::class.java] }
    private val creationModel by lazy { ViewModelProvider(this)[NativeCompanionCreationViewModel::class.java] }
    private val engineModel by lazy { ViewModelProvider(this)[NativeEngineViewModel::class.java] }
    private val toolsModel by lazy { ViewModelProvider(this)[NativeToolsViewModel::class.java] }
    private val workspacesModel by lazy { ViewModelProvider(this)[NativeWorkspacesViewModel::class.java] }
    private val presentationModel by lazy { ViewModelProvider(this)[NativeProductPresentationViewModel::class.java] }
    private val shareModel by lazy { ViewModelProvider(this)[NativeSharePreviewViewModel::class.java] }
    private val voiceInputModel by lazy { ViewModelProvider(this)[NativeVoiceInputViewModel::class.java] }
    private val standardVoiceModel by lazy { ViewModelProvider(this)[NativeStandardVoiceViewModel::class.java] }
    private val externalNavigation by lazy { ViewModelProvider(this)[NativeExternalNavigationViewModel::class.java] }
    private val transcriptModel by lazy { ViewModelProvider(this)[NativeTranscriptViewModel::class.java] }
    private lateinit var media: NativeMediaCoordinator
    private lateinit var permissions: NativePermissionCoordinator
    private lateinit var creationMedia: NativeCompanionCreationMediaCoordinator
    private val maintenance by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = NativeMaintenanceOwner(
                NativeCompanionLocalMaintenance(application, companions, media.mediaModel, shareModel,
                    voiceInputModel, standardVoiceModel, creationModel, model)
            ) as T
        })[NativeMaintenanceOwner::class.java].participant
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeCompanionLocalMaintenance.restoreAdmission(application)
        permissions = NativePermissionCoordinator(this, savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        media = NativeMediaCoordinator(this, companions, NativeRuntimeHost::backgroundWorkChanged, workbench = model)
        creationMedia = NativeCompanionCreationMediaCoordinator(this, creationModel)
        model.bindSessionPreparation(engineModel::captureSelection, engineModel::prepareSession)
        shareModel.bind(model, companions)
        voiceInputModel.bind(model, companions)
        standardVoiceModel.bind(model)
        externalNavigation.bindWakeOwner { id -> standardVoiceModel.state.value.journal?.wake?.let {
            it.eventId == id && it.phase != NativeStandardVoiceWakePhase.REJECTED
        } == true }
        if (savedInstanceState == null) acceptNavigation(intent)
        if (model.state.value.workspaceResolved) shareModel.advanceInbox(model::mediaTarget)
        setContent {
            val deletion by produceState(NativeCompanionDeletion.inspect(applicationContext)) {
                while (isActive) {
                    value = NativeCompanionDeletion.inspect(applicationContext)
                    delay(300)
                }
            }
            val state by model.state.collectAsStateWithLifecycle()
            val companionState by companions.state.collectAsStateWithLifecycle()
            val settingsState by settings.state.collectAsStateWithLifecycle()
            val mediaState by media.state.collectAsStateWithLifecycle()
            val permissionState by permissions.state.collectAsStateWithLifecycle()
            val scheduleState by schedules.state.collectAsStateWithLifecycle()
            val onboardingState by onboarding.state.collectAsStateWithLifecycle()
            val presentationState by presentationModel.state.collectAsStateWithLifecycle()
            var setupWasRequired by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(onboardingState.requiresSetup) {
                if (onboardingState.requiresSetup == true) setupWasRequired = true
                else if (onboardingState.requiresSetup == false && setupWasRequired) {
                    if (externalNavigation.state.value.request == null)
                        externalNavigation.accept(NativeExternalNavigationTarget.Root(NativeExternalRoot.MESSAGES))
                    setupWasRequired = false
                }
            }
            LaunchedEffect(onboardingState.requiresSetup, state.runtime.ready, permissionState.queue.active, permissionState.pendingRequests.isEmpty()) {
                externalNavigation.updateEnvironment(onboardingState.requiresSetup == false, state.runtime.ready,
                    !permissionState.queue.active && permissionState.pendingRequests.isEmpty())
            }
            LaunchedEffect(state.workspaceResolved) {
                if (state.workspaceResolved) shareModel.advanceInbox(model::mediaTarget)
            }
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedContext = remember(context, configuration, state.runtime.locale) {
                val localized = Configuration(configuration)
                localized.setLocale(Locale.forLanguageTag(state.runtime.locale))
                android.view.ContextThemeWrapper(context, context.theme).apply { applyOverrideConfiguration(localized) }
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides localizedContext.resources.configuration,
                LocalResources provides localizedContext.resources,
                LocalNativeProductPresentation provides presentationState,
            ) {
                DroyTheme(window = window) {
                    val actions = NativeShellMedia(
                        chooseAttachments = { owner -> media.chooseAttachments(owner) },
                        chooseImages = { owner -> media.chooseAttachments(owner, imagesOnly = true) },
                        chooseCamera = media::chooseCompanionCamera,
                        chooseAvatar = media::chooseAvatar,
                        chooseBackground = media::chooseBackground,
                        openAttachment = media::openAttachment,
                        playVoice = media::playVoice,
                        avatarUrl = { owner -> mediaState.avatarFor(owner)?.url },
                        backgroundResult = { owner -> mediaState.backgroundFor(owner) },
                        consumeBackground = { owner -> mediaState.backgroundFor(owner)?.let { media.consumeBackground(owner, it.id) } },
                        consumeAvatar = { owner -> mediaState.avatarFor(owner)?.let { media.consumeAvatar(owner, it.id) } },
                        conversationChanged = media::onConversationChanged,
                        error = mediaState.error?.let { stringResource(it.messageRes) },
                        retry = if (mediaState.canRetry) media::retryPending else null,
                        dismissError = media::dismissError,
                        notice = mediaState.cameraAlbumResult?.let { result ->
                            if (result.saved) stringResource(R.string.native_voice_input_album_saved)
                            else if (result.failed) stringResource(R.string.native_voice_input_album_failed) else null
                        }, dismissNotice = media::dismissCameraAlbumResult,
                    )
                    if (onboardingState.requiresSetup != false) {
                        NativeOnboardingScreen(onboardingState, onboarding, settingsState, settings, companionState, companions,
                            permissionState, permissions::onAction, permissions::setScreenVisible,
                            onCompleted = { onboarding.refresh() }, onExit = ::finish,
                            onChooseAvatar = media::chooseAvatar, uploadedAvatarUrl = mediaState.avatarFor(null)?.url,
                            onAvatarConsumed = { mediaState.avatarFor(null)?.let { media.consumeAvatar(null, it.id) } },
                            mediaError = actions.error, onRetryMedia = actions.retry,
                            onDismissMediaError = actions.dismissError, mediaBusy = mediaState.busy)
                    } else NativeAppShell(state, model, companionState, companions, settingsState, settings,
                            permissionState, permissions::onAction, permissions::setScreenVisible, scheduleState, schedules,
                            externalNavigation = externalNavigation, transcriptModel = transcriptModel,
                            onOpenAudioAuthorization = { externalNavigation.openAudio(this@NativeAppActivity, it) }, media = actions,
                            memoryModel = memoryModel, voiceModel = voiceModel, advancedModel = advancedModel,
                            creationModel = creationModel, onChooseCreationImage = creationMedia::chooseImage,
                            engineModel = engineModel, toolsModel = toolsModel, shareModel = shareModel,
                            workspacesModel = workspacesModel,
                            onRefreshPresentation = presentationModel::refresh,
                            voiceInputModel = voiceInputModel,
                            standardVoiceModel = standardVoiceModel,
                            onChooseWorkbenchAttachments = media::chooseWorkbenchAttachments,
                            onChooseWorkbenchCamera = media::chooseWorkbenchCamera,
                            companionDeletion = deletion,
                            onDeleteCompanion = { id -> NativeCompanionDeletion.begin(applicationContext, id, maintenance) },
                            onResumeCompanionDeletion = { NativeCompanionDeletion.resume(applicationContext, maintenance) },
                            onOpenUrl = ::openUrl)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        NativeRuntimeHost.onStarted(this)
        standardVoiceModel.attachPermissionLauncher { id -> NativeStandardVoiceHost.openAudioPermission(this, id) }
        model.startObserving()
        companions.startObserving()
        settings.startObserving()
        onboarding.startObserving()
        engineModel.startObserving()
        presentationModel.startObserving()
        voiceInputModel.startObserving()
        if (model.state.value.workspaceResolved) shareModel.advanceInbox(model::mediaTarget)
    }

    override fun onStop() {
        standardVoiceModel.attachPermissionLauncher(null)
        model.stopObserving()
        companions.stopObserving()
        settings.stopObserving()
        onboarding.stopObserving()
        engineModel.stopObserving()
        presentationModel.stopObserving()
        voiceInputModel.stopObserving()
        NativeRuntimeHost.onStopped()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptNavigation(intent)
    }

    override fun onResume() {
        super.onResume()
        maintenance.setForeground(true)
        NativeCompanionDeletion.resume(applicationContext, maintenance)
        // Opening the app retries a stopped engine once; observers never restart it.
        if (NativeRuntimeHost.inspect(this).canStart) model.startRuntime()
        permissions.resume()
        externalNavigation.setHostResumed(true)
        HardwareRuntime.onPermissionsChanged(this)
        io.droyagent.androiduse.AccessibilityRecovery.get(this).onAppOpened(!permissions.state.value.queue.active
            && ProductPermissions.allowed(getSharedPreferences("product", MODE_PRIVATE), "allowRoot"))
        NotificationManagement.get(this).rescan()
        Thread({ VoiceRuntime.resume(this, NativeAppActivity::class.java) }, "droy-native-wake-resume").start()
        voiceInputModel.refresh()
    }
    override fun onPause() { maintenance.setForeground(false); externalNavigation.setHostResumed(false); voiceInputModel.leave(); permissions.pause(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        permissions.save(outState)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) permissions.onAction(NativePermissionAction.CancelQueue)
        permissions.close()
        super.onDestroy()
    }

    @Deprecated("Android permission queue delegates the platform callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        permissions.onActivityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        this.permissions.onRuntimeResult(requestCode, permissions, grantResults)
    }

    private fun acceptNavigation(intent: Intent) {
        if (intent.hasExtra("mediaImportId") && model.state.value.workspaceResolved) shareModel.advanceInbox(model::mediaTarget)
        externalNavigation.accept(intent)
    }

    private fun openUrl(url: String) {
        val parsed = android.net.Uri.parse(url)
        if (parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank() && parsed.userInfo == null) {
            try { startActivity(Intent(Intent.ACTION_VIEW, parsed)); return }
            catch (_: android.content.ActivityNotFoundException) { }
            catch (_: SecurityException) { }
        }
        android.widget.Toast.makeText(this, NativeLocale.text("无法打开此链接", "Unable to open this link"), android.widget.Toast.LENGTH_SHORT).show()
    }
}
