package io.droyagent.app.nativeui.petstudio

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.droyagent.app.NativePetStudioHost
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private data class GenerationRequest(val draft: PetDraft? = null, val generateId: String? = null)

@Composable
fun NativePetStudioScreen(host: NativePetStudioHost, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val client = remember { PetStudioClient() }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var catalog by remember { mutableStateOf<PetCatalog?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Int?>(null) }
    var unknown by rememberSaveable { mutableStateOf(false) }
    var reviewedSnapshot by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var appearance by rememberSaveable { mutableStateOf("") }
    var personality by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var previewId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmation by remember { mutableStateOf<GenerationRequest?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    suspend fun read() {
        catalog = client.list()
        selected = withContext(Dispatchers.IO) { host.selectedId() }
        reviewedSnapshot = true; failure = null
    }
    LaunchedEffect(client, lifecycle, refreshKey) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                do {
                    mutex.withLock { read() }; loading = false
                    if (catalog?.pets?.none { it.generating } != false) break
                    delay(2500)
                } while (isActive)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = R.string.npss_load_error }
            finally { loading = false }
        }
    }
    fun apply(id: String) {
        if (busy) return
        busy = true; failure = null
        scope.launch {
            try { withContext(Dispatchers.IO) { host.select(id) }; selected = id }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failure = R.string.npss_error }
            finally { busy = false }
        }
    }
    fun generate(request: GenerationRequest) {
        if (busy || loading || unknown || catalog?.available != true || catalog?.pets?.any { it.generating } == true) return
        busy = true; failure = null; reviewedSnapshot = false
        scope.launch {
            var acknowledged = false
            try {
                mutex.withLock {
                    val result = if (request.generateId != null) client.generate(request.generateId)
                        else if (request.draft?.id != null) client.retry(requireNotNull(request.draft)) else client.create(requireNotNull(request.draft))
                    acknowledged = true
                    expandedId = result.id
                    if (request.draft != null) { editingId = null; name = ""; appearance = ""; personality = "" }
                    confirmation = null
                    read()
                }
                refreshKey++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (acknowledged) failure = R.string.npss_load_error
                else { unknown = true; failure = R.string.npss_unknown }
                confirmation = null
            }
            finally { busy = false }
        }
    }
    val generating = catalog?.pets?.any { it.generating } == true
    val enabled = !busy && !generating && !unknown
    val generationAvailable = enabled && !loading && catalog?.available == true
    BackHandler { if (previewId != null) previewId = null else onBack() }
    Scaffold(modifier.fillMaxSize().testTag("native-pet-studio"), contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        Surface {
            Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                DroyIconAction(R.drawable.native_settings_arrow_back, stringResource(R.string.npss_back), onBack, Modifier.testTag("native-pet-studio-back"))
                Text(stringResource(R.string.npss_title), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleLarge)
                DroyIconAction(R.drawable.native_ui_refresh, stringResource(R.string.npss_refresh), { loading = true; refreshKey++ }, enabled = !busy, modifier = Modifier.testTag("native-pet-studio-refresh"))
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag(
            if (catalog != null && !loading && !busy && failure == null) "native-pet-studio-body" else "native-pet-studio-pending")) {
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding().imePadding().testTag("native-pet-studio-list"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading || busy) item { LinearProgressIndicator(Modifier.fillMaxWidth().testTag("native-pet-studio-loading")) }
            failure?.let { message -> item { Text(stringResource(message), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("native-pet-studio-error")) } }
            if (unknown) item {
                Text(stringResource(R.string.npss_unknown))
                OutlinedButton(onClick = { unknown = false; failure = null }, enabled = reviewedSnapshot && !busy, modifier = Modifier.testTag("native-pet-studio-reviewed")) { Text(stringResource(R.string.npss_reviewed)) }
            }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.npss_name)) }, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("native-pet-studio-name"))
                        OutlinedTextField(appearance, { appearance = it }, label = { Text(stringResource(R.string.npss_appearance)) }, minLines = 3, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("native-pet-studio-appearance"))
                        OutlinedTextField(personality, { personality = it }, label = { Text(stringResource(R.string.npss_personality)) }, minLines = 2, enabled = !busy, modifier = Modifier.fillMaxWidth().testTag("native-pet-studio-personality"))
                        val unavailable = stringResource(R.string.npss_unavailable)
                        Text(stringResource(R.string.npss_text_model, catalog?.textProvider ?: unavailable, catalog?.textModel ?: unavailable), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.npss_image_model, catalog?.provider ?: unavailable, catalog?.imageModel ?: unavailable), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.npss_cost), style = MaterialTheme.typography.bodySmall)
                        if (catalog != null && catalog?.available != true) Text(stringResource(R.string.npss_setup), modifier = Modifier.testTag("native-pet-studio-unavailable"))
                        if (editingId != null) Text(stringResource(R.string.npss_edit_warning))
                        Button(onClick = {
                            val draft = PetDraft(editingId, name, appearance, personality)
                            try { draft.body(); confirmation = GenerationRequest(draft = draft) }
                            catch (_: IllegalArgumentException) { failure = R.string.npss_invalid }
                        }, enabled = generationAvailable, modifier = Modifier.testTag("native-pet-studio-generate")) {
                            Text(stringResource(if (editingId == null) R.string.npss_create else R.string.npss_retry))
                        }
                        if (editingId != null) TextButton(onClick = { editingId = null; name = ""; appearance = ""; personality = "" }, enabled = !busy) { Text(stringResource(R.string.npss_cancel_edit)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.npss_default), style = MaterialTheme.typography.titleMedium)
                if (selected == "default") Text(stringResource(R.string.npss_selected))
                else TextButton(onClick = { apply("default") }, enabled = !busy, modifier = Modifier.testTag("native-pet-studio-apply:default")) { Text(stringResource(R.string.npss_apply)) }
                TextButton(onClick = { previewId = if (previewId == "default") null else "default" }, enabled = !busy) { Text(stringResource(R.string.npss_preview)) }
                if (previewId == "default") PetPreview(host, "default")
                Text(stringResource(R.string.npss_list), style = MaterialTheme.typography.titleMedium)
                if (catalog?.pets?.isEmpty() == true) Text(stringResource(R.string.npss_empty))
            }
            items(catalog?.pets ?: emptyList(), key = { it.id }) { pet ->
                OutlinedCard(Modifier.fillMaxWidth().testTag("native-pet-studio-candidate:${pet.id}")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pet.name, style = MaterialTheme.typography.titleMedium)
                        Text(pet.description)
                        Text(stringResource(stageLabel(pet.stage)))
                        Text(stringResource(R.string.npss_progress, pet.completed, pet.total))
                        if (pet.generating) LinearProgressIndicator(progress = { pet.completed.toFloat() / pet.total }, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { expandedId = if (expandedId == pet.id) null else pet.id }) { Text(stringResource(R.string.npss_preview)) }
                        if (expandedId == pet.id) {
                            if (pet.reference) PetStill(host, pet.id, "reference.png", pet.updatedAt, R.string.npss_image_alt)
                            if (pet.reference && !pet.referenceCurrent) Text(stringResource(R.string.npss_old_reference))
                            if (pet.logo) PetStill(host, pet.id, "brand-logo.png", pet.updatedAt, R.string.npss_logo_alt)
                            if (pet.persona.isNotBlank()) Text(pet.persona)
                            pet.voice.forEach { Text(it.replace("{name}", pet.name)) }
                        }
                        if (pet.stage == PetStage.Ready) {
                            if (selected == pet.id) Text(stringResource(R.string.npss_selected))
                            else Button(onClick = { apply(pet.id) }, enabled = !busy, modifier = Modifier.testTag("native-pet-studio-apply:${pet.id}")) { Text(stringResource(R.string.npss_apply)) }
                            TextButton(onClick = { previewId = if (previewId == pet.id) null else pet.id }, modifier = Modifier.testTag("native-pet-studio-preview:${pet.id}")) { Text(stringResource(if (previewId == pet.id) R.string.npss_close_preview else R.string.npss_preview)) }
                            if (previewId == pet.id) PetPreview(host, pet.id)
                        }
                        if (!pet.generating) TextButton(onClick = {
                            editingId = if (pet.stage == PetStage.Ready) null else pet.id
                            name = pet.name; appearance = pet.description; personality = pet.personality
                            scope.launch { listState.animateScrollToItem(0) }
                        }, enabled = !busy, modifier = Modifier.testTag("native-pet-studio-edit:${pet.id}")) { Text(stringResource(if (pet.stage == PetStage.Ready) R.string.npss_copy else R.string.npss_edit)) }
                        if (!pet.generating && pet.stage != PetStage.Ready && pet.reference && pet.referenceCurrent) Button(onClick = { confirmation = GenerationRequest(generateId = pet.id) }, enabled = generationAvailable, modifier = Modifier.testTag("native-pet-studio-continue:${pet.id}")) {
                            Text(stringResource(if (pet.stage == PetStage.Error) R.string.npss_continue else R.string.npss_generate))
                        }
                    }
                }
            }
        }
        }
    }
    confirmation?.let { request ->
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null }, title = { Text(stringResource(R.string.npss_confirm_title)) },
            text = { Text(stringResource(R.string.npss_cost)) },
            confirmButton = { TextButton(onClick = { generate(request) }, enabled = generationAvailable, modifier = Modifier.testTag("native-pet-studio-confirm")) { Text(stringResource(R.string.npss_confirm)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }, enabled = !busy) { Text(stringResource(R.string.npss_cancel)) } })
    }
}

private fun stageLabel(stage: PetStage): Int = when (stage) {
    PetStage.Draft -> R.string.npss_draft; PetStage.Persona -> R.string.npss_persona; PetStage.Reference -> R.string.npss_reference
    PetStage.Actions -> R.string.npss_actions; PetStage.ReferenceReady -> R.string.npss_reference_ready; PetStage.Ready -> R.string.npss_ready; PetStage.Error -> R.string.npss_failed
}

@Composable private fun PetStill(host: NativePetStudioHost, id: String, file: String, revision: Long, description: Int) {
    var image by remember(id, file, revision) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(id, file, revision) { mutableStateOf(false) }
    var retry by remember(id, file, revision) { mutableIntStateOf(0) }
    LaunchedEffect(host, id, file, revision, retry) {
        failed = false
        var loaded: Bitmap? = null
        try {
            runInterruptible(Dispatchers.IO) { loaded = host.loadStill(id, file) }
            image = loaded; awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { image = null; loaded?.recycle() }
    }
    image?.let { Image(it.asImageBitmap(), stringResource(description), Modifier.fillMaxWidth().heightIn(max = 260.dp)) }
    if (image == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) {
        Text(stringResource(R.string.npss_asset_error))
        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.npss_refresh)) }
    }
}

@Composable private fun PetPreview(host: NativePetStudioHost, id: String) {
    var preview by remember(id) { mutableStateOf<NativePetStudioHost.Preview?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    var state by remember(id) { mutableStateOf("idle") }
    var left by remember(id) { mutableStateOf(true) }
    var sequence by remember { mutableIntStateOf(0) }
    var retry by remember(id) { mutableIntStateOf(0) }
    LaunchedEffect(host, id, retry) {
        failed = false
        var loaded: NativePetStudioHost.Preview? = null
        try {
            runInterruptible(Dispatchers.IO) { loaded = host.loadPreview(id) }
            preview = loaded; awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { preview = null; loaded?.close() }
    }
    if (preview == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (failed) {
        Text(stringResource(R.string.npss_asset_error))
        TextButton(onClick = { retry++ }) { Text(stringResource(R.string.npss_refresh)) }
    }
    preview?.let { assets ->
        val description = stringResource(R.string.npss_preview_alt)
        key(id, sequence) { AndroidView(factory = { context -> assets.createView(context).apply { contentDescription = description } }, update = { assets.play(it, state, left) }, modifier = Modifier.fillMaxWidth().height(220.dp)) }
        var menu by remember { mutableStateOf(false) }
        val states = listOf("idle" to R.string.npss_idle, "working" to R.string.npss_working, "speaking" to R.string.npss_speaking,
            "waiting" to R.string.npss_waiting, "tap" to R.string.npss_tap, "success" to R.string.npss_success, "peekLeft" to R.string.npss_peek_left, "peekRight" to R.string.npss_peek_right)
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(stringResource(states.first { it.first == state }.second)) }
            DropdownMenu(menu, { menu = false }) { states.forEach { (value, label) -> DropdownMenuItem(text = { Text(stringResource(label)) }, onClick = { state = value; menu = false; sequence++ }) } }
        }
        Row {
            TextButton(onClick = { left = !left; sequence++ }) { Text(stringResource(if (left) R.string.npss_right else R.string.npss_left)) }
            TextButton(onClick = { sequence++ }, modifier = Modifier.testTag("native-pet-studio-play")) { Text(stringResource(R.string.npss_play)) }
        }
    }
}
