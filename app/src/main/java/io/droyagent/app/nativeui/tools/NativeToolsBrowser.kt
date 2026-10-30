package io.droyagent.app.nativeui.tools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.droyagent.app.NativeBrowserSurfaceHost
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NativeToolsBrowser(state: NativeToolsState, model: NativeToolsViewModel, modifier: Modifier, focusedContent: Boolean = false) {
    val activity = LocalContext.current.activity() ?: return
    val owner = LocalLifecycleOwner.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val host = remember(activity) { NativeBrowserSurfaceHost(activity) }
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var address by rememberSaveable(state.selectedBrowserId) { mutableStateOf(state.browserDisplayUrl) }
    var editing by remember { mutableStateOf(false) }
    DisposableEffect(host, owner) {
        host.setListener(object : NativeBrowserSurfaceHost.Listener {
            override fun onState(value: JSONObject) { model.browserState(value) }
            override fun onError(code: String) { model.report(code) }
        })
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; host.resume() }
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) { resumed = false; host.pause() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); host.close() }
    }
    LaunchedEffect(host, state.selectedBrowserId) { state.selectedBrowserId?.let(host::selectTab) ?: host.clearTab() }
    LaunchedEffect(host, resumed, state.pendingBrowserNavigation?.requestId) {
        if (resumed) state.pendingBrowserNavigation?.requestId?.let(model::takeBrowserNavigation)?.let { navigation ->
            host.selectTab(navigation.tabId)
            host.request("navigate", navigation.tabId, navigation.url)
        }
    }
    LaunchedEffect(state.browserDisplayUrl, editing) { if (!editing) address = state.browserDisplayUrl }
    LaunchedEffect(host, resumed, state.selectedBrowserId, state.browser?.id) {
        if (!resumed) return@LaunchedEffect
        while (isActive) {
            host.request("list", "", null)
            // ATTACH/navigate create a source tab. A premature state read could report it closed before creation.
            state.selectedBrowserId?.takeIf { it == state.browser?.id }?.let { host.request("state", it, null) }
            delay(2000)
        }
    }
    fun navigate() {
        val id = state.selectedBrowserId ?: return
        focus.clearFocus(); keyboard?.hide(); editing = false
        host.request("navigate", id, address.trim())
    }
    Column(modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        if (focusedContent) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(state.browserDisplayUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("native-tools-browser-source"))
                }
                DroyIconAction(R.drawable.native_ui_refresh, stringResource(R.string.native_tools_reload), {
                    val id = state.selectedBrowserId ?: return@DroyIconAction
                    model.dismissError()
                    if (state.browser?.url.isNullOrBlank() && state.browserSourceUrl != null)
                        host.request("navigate", id, state.browserSourceUrl)
                    else host.request("reload", id, null)
                }, enabled = state.selectedBrowserId != null, modifier = Modifier.testTag("native-tools-browser-reload"))
            }
            if (state.browser?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.browser?.failed == true && state.error == null) ToolsNotice(stringResource(R.string.native_tools_error_browser))
            if (state.selectedBrowserId == null) Text(stringResource(R.string.native_tools_empty_browser), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
        LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.browserTabs, key = NativeBrowserTab::id) { tab ->
                FilterChip(selected = tab.id == state.selectedBrowserId, onClick = { model.selectBrowser(tab.id) },
                    label = { Text(tab.title.ifBlank { tab.url.ifBlank { stringResource(R.string.native_tools_untitled_tab) } }, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.widthIn(max = 240.dp).heightIn(min = 48.dp))
            }
        }
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(model::newBrowserTab, modifier = Modifier.heightIn(min = 48.dp).testTag("native-tools-new-browser-tab")) { Text(stringResource(R.string.native_tools_new_tab)) }
            if (state.selectedBrowserId != null) TextButton({
                host.request("close", state.selectedBrowserId, null); model.browserClosed(state.selectedBrowserId)
                host.request("list", "", null)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_close_tab)) }
        }
        if (state.selectedBrowserId != null) {
            OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged { editing = it.isFocused }
                .testTag("native-tools-browser-address"), label = { Text(stringResource(R.string.native_tools_address)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { navigate() }))
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(::navigate, enabled = address.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_go)) }
                TextButton({ host.request("back", state.selectedBrowserId, null) }, enabled = state.browser?.canGoBack == true, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_browser_back)) }
                TextButton({ host.request("forward", state.selectedBrowserId, null) }, enabled = state.browser?.canGoForward == true, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_browser_forward)) }
                TextButton({ host.request("reload", state.selectedBrowserId, null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_reload)) }
            }
            if (state.browser?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.browser?.failed == true) ToolsNotice(stringResource(R.string.native_tools_error_browser))
        } else Text(stringResource(R.string.native_tools_empty_browser), modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AndroidView(factory = { host }, modifier = Modifier.fillMaxWidth().weight(1f).testTag("native-tools-browser-surface"))
    }
}

/** Local HTML has no network, filesystem access, JavaScript, or app bridge. */
@Composable
internal fun NativeRestrictedHtml(html: String, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(context) { WebView(context).apply {
        settings.javaScriptEnabled = false; settings.javaScriptCanOpenWindowsAutomatically = false
        settings.allowFileAccess = false; settings.allowContentAccess = false
        settings.domStorageEnabled = false; settings.databaseEnabled = false
        settings.blockNetworkLoads = true; settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        setDownloadListener { _, _, _, _, _ -> }
    } }
    LaunchedEffect(view, html) {
        val policy = "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src data:; style-src 'unsafe-inline'; font-src data:; object-src 'none'; base-uri 'none'; form-action 'none'\">"
        view.loadDataWithBaseURL("https://droy-preview.invalid/", policy + html, "text/html", "utf-8", null)
    }
    DisposableEffect(view) { onDispose { view.stopLoading(); view.destroy() } }
    AndroidView(factory = { view }, modifier = modifier.testTag("native-tools-html-preview"))
}

private fun Context.activity(): Activity? {
    var current = this
    while (current is ContextWrapper) { if (current is Activity) return current; current = current.baseContext }
    return current as? Activity
}
