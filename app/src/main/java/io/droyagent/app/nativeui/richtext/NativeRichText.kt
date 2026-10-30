package io.droyagent.app.nativeui.richtext

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.droyagent.app.R
import io.droyagent.app.nativeui.ui.DroyIconAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.commonmark.ext.gfm.tables.TableCell

private data class ParsedText(val source: String, val document: RichDocument)

/** Links are callbacks only. The session owner decides authorization and navigation; this renderer has no network or URI handler. */
@Composable
fun NativeRichText(text: String, onCopy: (String) -> Unit, onOpenLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val parsed by produceState<ParsedText?>(null, text) {
        val document = withContext(Dispatchers.Default) { RichTextParser.parse(text) }
        coroutineContext.ensureActive()
        value = ParsedText(text, document)
    }
    // Keep the last parsed tree until the next revision is ready. Falling back to raw
    // Markdown on every streamed delta alternated layouts and visibly flashed.
    val document = parsed?.document
    val limit = document?.limited ?: (text.length > RichTextParser.MAX_CHARACTERS)
    SelectionContainer(modifier.testTag("native-rich-text")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (document == null) Text(text.take(RichTextParser.MAX_CHARACTERS), style = MaterialTheme.typography.bodyLarge)
            else RichBlocks(document.blocks, onCopy, onOpenLink)
            if (limit) {
                DisableSelection {
                    Text(stringResource(R.string.native_rich_text_limited), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onCopy(text) }, modifier = Modifier.testTag("native-rich-text-copy-full")) { Text(stringResource(R.string.native_rich_text_copy_full)) }
                }
            }
        }
    }
}

@Composable private fun RichBlocks(blocks: List<RichBlock>, onCopy: (String) -> Unit, onOpenLink: (String) -> Unit) {
    blocks.forEachIndexed { index, block ->
        key(index) {
            when (block) {
                is RichBlock.Paragraph -> {
                    val style = when (block.heading) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.headlineSmall
                        3 -> MaterialTheme.typography.titleLarge
                        4 -> MaterialTheme.typography.titleMedium
                        5, 6 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.bodyLarge
                    }
                    val headingLabel = if (block.heading > 0) stringResource(R.string.native_rich_text_heading, block.heading) else ""
                    val link = block.runs.singleOrNull()?.takeIf { !it.image && it.link?.let(::isWebLink) == true }
                    if (block.heading == 0 && link != null) WebLinkCard(link, onOpenLink)
                    else InlineText(block.runs, onOpenLink, style,
                        modifier = if (block.heading > 0) Modifier.padding(top = 8.dp).semantics { heading(); stateDescription = headingLabel } else Modifier)
                }
                is RichBlock.CodeBlock -> {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            DisableSelection {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(block.language.ifEmpty { stringResource(R.string.native_rich_text_code) }, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                                    DroyIconAction(R.drawable.native_ui_content_copy, stringResource(R.string.native_rich_text_copy_code), { onCopy(block.code) },
                                        modifier = Modifier.testTag("native-rich-text-copy-code"))
                                }
                            }
                            Text(block.code, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), softWrap = false,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("native-rich-text-code"))
                        }
                    }
                }
                is RichBlock.Quote -> Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.width(3.dp).heightIn(min = 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { RichBlocks(block.blocks, onCopy, onOpenLink) }
                }
                is RichBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    block.items.forEachIndexed { number, item ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(block.start?.let { "${it.toLong() + number}." } ?: "•", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(min = 24.dp).padding(end = 8.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { RichBlocks(item, onCopy, onOpenLink) }
                        }
                    }
                }
                is RichBlock.Table -> {
                    val tableLabel = stringResource(R.string.native_rich_text_table)
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth().testTag("native-rich-text-table").semantics { stateDescription = tableLabel }) {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            block.rows.forEach { row ->
                                Row {
                                    row.forEach { cell ->
                                        InlineText(cell.runs, onOpenLink,
                                            if (cell.header) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.bodyMedium,
                                            alignment = when (cell.alignment) { TableCell.Alignment.RIGHT -> TextAlign.End; TableCell.Alignment.CENTER -> TextAlign.Center; else -> TextAlign.Start },
                                            modifier = Modifier.width(180.dp).background(if (cell.header) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp))
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
                RichBlock.Rule -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable private fun InlineText(runs: List<RichRun>, onOpenLink: (String) -> Unit, style: TextStyle,
    modifier: Modifier = Modifier, alignment: TextAlign = TextAlign.Start) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val imageLabel = stringResource(R.string.native_rich_text_image)
    val openLabel = stringResource(R.string.native_rich_text_open_link)
    val currentOpen by rememberUpdatedState(onOpenLink)
    val annotated = buildAnnotatedString {
        runs.forEachIndexed { index, run ->
            val decoration = buildList { if (run.strike) add(TextDecoration.LineThrough); if (run.link != null) add(TextDecoration.Underline) }
            val span = SpanStyle(fontWeight = if (run.bold) FontWeight.Bold else null, fontStyle = if (run.italic) FontStyle.Italic else null,
                fontFamily = if (run.code) FontFamily.Monospace else null, background = if (run.code) codeColor else androidx.compose.ui.graphics.Color.Unspecified,
                color = if (run.link != null) linkColor else androidx.compose.ui.graphics.Color.Unspecified,
                textDecoration = if (decoration.isEmpty()) null else TextDecoration.combine(decoration))
            withStyle(span) {
                val label = if (run.image) "$imageLabel${if (run.text.isBlank()) "" else ": ${run.text}"}" else run.text
                if (run.link == null) append(label)
                else withLink(LinkAnnotation.Clickable("rich-link-$index", TextLinkStyles(SpanStyle(color = linkColor))) { currentOpen(run.link) }) { append(label) }
            }
        }
    }
    val links = runs.filter { it.link != null }.distinctBy { it.link }
    Text(annotated, style = style, textAlign = alignment, modifier = modifier.semantics {
        if (links.isNotEmpty()) customActions = links.map { run ->
            CustomAccessibilityAction("$openLabel: ${run.text.ifBlank { imageLabel }.take(80)}") { currentOpen(requireNotNull(run.link)); true }
        }
    })
}

private fun isWebLink(value: String): Boolean = try {
    val uri = java.net.URI(value)
    uri.scheme in listOf("http", "https") && !uri.host.isNullOrEmpty()
} catch (_: java.net.URISyntaxException) { false }

@Composable private fun WebLinkCard(run: RichRun, onOpen: (String) -> Unit) {
    val url = run.link ?: return
    val host = java.net.URI(url).host
    DisableSelection {
        OutlinedCard(onClick = { onOpen(url) }, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                io.droyagent.app.nativeui.ui.DroyIcon(R.drawable.native_ui_description, null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(run.text.ifBlank { host }, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                io.droyagent.app.nativeui.ui.DroyIcon(R.drawable.native_companion_chevron_right, stringResource(R.string.native_rich_text_open_link))
            }
        }
    }
}
