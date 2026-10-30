package io.droyagent.app.nativeui.tools

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.droyagent.app.NativeToolsFileHost
import io.droyagent.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NativeToolsPdfPreview(preview: NativeToolPreview, modifier: Modifier) {
    val file = preview.privatePdf ?: return
    val context = LocalContext.current
    var index by remember(file) { mutableIntStateOf(0) }
    var pageCount by remember(file) { mutableIntStateOf(0) }
    Column(modifier) {
        if (preview.missingFonts.isNotEmpty()) Text(stringResource(R.string.native_tools_missing_fonts, preview.missingFonts.joinToString(", ")),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp))
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(1, 2048)
            val page by produceState<PdfPreview>(PdfPreview.Loading, file, index, width) {
                value = PdfPreview.Loading
                value = try { PdfPreview.Ready(withContext(Dispatchers.IO) { NativeToolsFileHost.renderPdfPage(context, file, index, width) }) }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { PdfPreview.Unavailable }
            }
            when (val value = page) {
                PdfPreview.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                PdfPreview.Unavailable -> Text(stringResource(R.string.native_tools_unsupported), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                is PdfPreview.Ready -> {
                    LaunchedEffect(value.page.pageCount) { pageCount = value.page.pageCount }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Image(value.page.bitmap.asImageBitmap(), stringResource(R.string.native_tools_pdf_page, index + 1, value.page.pageCount),
                            contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ index-- }, enabled = index > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_previous)) }
            OutlinedButton({ index++ }, enabled = index + 1 < pageCount, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.native_tools_next)) }
        }
    }
}

private sealed interface PdfPreview {
    data object Loading : PdfPreview
    data object Unavailable : PdfPreview
    data class Ready(val page: NativeToolsFileHost.PdfPage) : PdfPreview
}
