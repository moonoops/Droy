package io.droyagent.app.nativeui.companion.ui

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.companion.data.NativeCompanionMarketPack
import io.droyagent.app.nativeui.companion.data.NativeCompanionStickerMarketState
import io.droyagent.app.nativeui.ui.DroyIcon

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CompanionStickerMarketPage(companionName: String, state: NativeCompanionStickerMarketState,
    ready: Boolean, onQuery: (String) -> Unit, onSearch: () -> Unit,
    onInstall: (NativeCompanionMarketPack) -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    fun back() { if (imeVisible) keyboard?.hide() else onClose() }
    BackHandler { back() }
    val enabled = ready && !state.loading && state.installingId == null
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
        CompanionTopBar(stringResource(R.string.native_companion_market_title), ::back)
    }) { padding ->
        Column(Modifier.fillMaxSize().testTag("native-companion-market-page").padding(padding)
            .consumeWindowInsets(padding).navigationBarsPadding().imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.native_companion_market_scope, companionName), style = MaterialTheme.typography.bodyMedium)
            CompanionFilledField(state.query, onQuery, Modifier.fillMaxWidth().testTag("native-companion-market-query"),
                enabled = enabled, singleLine = true,
                label = { Text(stringResource(R.string.native_companion_market_search_label)) },
                leadingIcon = { DroyIcon(R.drawable.native_settings_search, null) })
            FilledTonalButton(onClick = { keyboard?.hide(); onSearch() }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-companion-market-search")) {
                Text(stringResource(R.string.native_companion_market_search))
            }
            if (!ready) Text(stringResource(R.string.native_companion_market_runtime_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            if (state.loading) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.native_companion_market_loading))
            }
            state.error?.let { code ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("native-companion-market-error")) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(marketErrorResource(code)), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("native-companion-market-retry")) { Text(stringResource(R.string.native_companion_market_retry)) }
                    }
                }
            }
            state.lastReceipt?.let { receipt ->
                Text(if (receipt.skippedAnimated > 0) stringResource(R.string.native_companion_market_installed_static,
                    receipt.pack.name, receipt.pack.count, receipt.skippedAnimated)
                    else stringResource(R.string.native_companion_market_installed, receipt.pack.name, receipt.pack.count),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("native-companion-market-success"))
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("native-companion-market-results"),
                contentPadding = PaddingValues(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.loaded && !state.loading && state.packs.isEmpty()) item("empty") {
                    Text(stringResource(R.string.native_companion_market_empty), Modifier.padding(vertical = 16.dp))
                }
                items(state.packs, key = { it.id }) { pack ->
                    StickerMarketPack(pack, state.installingId == pack.id, pack.id in state.receipts, enabled,
                        onInstall = { onInstall(pack) })
                }
            }
        }
    }
}

@Composable
private fun StickerMarketPack(pack: NativeCompanionMarketPack, installing: Boolean, installed: Boolean,
    enabled: Boolean, onInstall: () -> Unit) {
    val context = LocalContext.current
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().testTag("native-companion-market-pack:${pack.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(pack.name, style = MaterialTheme.typography.titleMedium)
            if (pack.description.isNotBlank()) Text(pack.description, style = MaterialTheme.typography.bodyMedium)
            Text(if (pack.count == 0) stringResource(R.string.native_companion_market_pack_unknown_count)
                else if (pack.bytes > 0) stringResource(R.string.native_companion_market_pack_size, pack.count,
                    Formatter.formatShortFileSize(context, pack.bytes)) else stringResource(R.string.native_companion_market_pack_count, pack.count),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.native_companion_market_license,
                pack.license.ifBlank { stringResource(R.string.native_companion_market_license_unknown) }),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = onInstall, enabled = enabled && !installed,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-companion-market-install:${pack.id}")) {
                if (installing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(when {
                    installing -> R.string.native_companion_market_installing
                    installed -> R.string.native_companion_market_downloaded
                    else -> R.string.native_companion_market_install
                }))
            }
        }
    }
}

private fun marketErrorResource(code: String): Int = when (code) {
    "COMPANION_STICKER_ONLINE_NETWORK", "network_error", "service_temporarily_unavailable" -> R.string.native_companion_market_error_network
    "COMPANION_STICKER_ONLINE_TIMEOUT" -> R.string.native_companion_market_error_timeout
    "COMPANION_STICKER_ONLINE_NOT_FOUND" -> R.string.native_companion_market_error_not_found
    "COMPANION_STICKER_ONLINE_CHECKSUM" -> R.string.native_companion_market_error_checksum
    "COMPANION_STICKER_PACK_TOO_LARGE", "response_too_large" -> R.string.native_companion_market_error_large
    "COMPANION_STICKER_PACK_UNSUPPORTED" -> R.string.native_companion_market_error_unsupported
    "COMPANION_STICKER_ONLINE_INVALID", "COMPANION_STICKER_PACK_INVALID", "malformed_response" -> R.string.native_companion_market_error_invalid
    "runtime_unavailable", "COMPANION_MAINTENANCE_BUSY", "COMPANION_MEMORY_MANAGEMENT_IN_PROGRESS" -> R.string.native_companion_market_runtime_unavailable
    else -> R.string.native_companion_market_error_generic
}
