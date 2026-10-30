package io.droyagent.app.nativeui.richtext

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.*
import org.commonmark.node.*
import org.commonmark.parser.Parser

internal data class RichRun(val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val strike: Boolean = false, val code: Boolean = false, val link: String? = null, val image: Boolean = false)
internal sealed interface RichBlock {
    data class Paragraph(val runs: List<RichRun>, val heading: Int = 0) : RichBlock
    data class CodeBlock(val code: String, val language: String) : RichBlock
    data class Quote(val blocks: List<RichBlock>) : RichBlock
    data class Items(val start: Int?, val items: List<List<RichBlock>>) : RichBlock
    data class Table(val rows: List<List<Cell>>) : RichBlock
    data class Cell(val runs: List<RichRun>, val header: Boolean, val alignment: TableCell.Alignment?)
    data object Rule : RichBlock
}
internal data class RichDocument(val blocks: List<RichBlock>, val limited: Boolean)

/** CommonMark owns all grammar. These limits bound chat layout and AST projection, not message storage. */
internal object RichTextParser {
    const val MAX_CHARACTERS = 65_536
    private const val MAX_NODES = 2048
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .maxOpenBlockParsers(64).maxInlineNesting(64).build()
    fun parse(text: String): RichDocument {
        var end = minOf(text.length, MAX_CHARACTERS)
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate()) end--
        val mapper = Mapper(text.length > end)
        val root = parser.parse(text.substring(0, end))
        return RichDocument(mapper.blocks(root, 0), mapper.limited)
    }
    private class Mapper(var limited: Boolean) {
        private var remaining = MAX_NODES
        private var blockBudget = 256
        private fun accept(depth: Int): Boolean {
            if (remaining-- <= 0 || depth > 48) { limited = true; return false }; return true
        }
        fun blocks(parent: Node, depth: Int): List<RichBlock> {
            val result = mutableListOf<RichBlock>(); var node = parent.firstChild
            while (node != null) {
                if (!accept(depth)) break
                if (blockBudget-- <= 0) { limited = true; break }
                when (node) {
                    is Paragraph -> result += RichBlock.Paragraph(inlines(node, RichRun(""), depth + 1))
                    is Heading -> result += RichBlock.Paragraph(inlines(node, RichRun(""), depth + 1), node.level)
                    is FencedCodeBlock -> result += RichBlock.CodeBlock(node.literal, node.info.orEmpty().trim().takeWhile { !it.isWhitespace() }.take(80))
                    is IndentedCodeBlock -> result += RichBlock.CodeBlock(node.literal, "")
                    is BlockQuote -> result += RichBlock.Quote(blocks(node, depth + 1))
                    is BulletList, is OrderedList -> {
                        val items = mutableListOf<List<RichBlock>>(); var item = node.firstChild
                        while (item != null && accept(depth + 1)) { items += blocks(item, depth + 2); item = item.next }
                        result += RichBlock.Items((node as? OrderedList)?.markerStartNumber, items)
                    }
                    is ThematicBreak -> result += RichBlock.Rule
                    is HtmlBlock -> result += RichBlock.Paragraph(listOf(RichRun(node.literal)))
                    is TableBlock -> {
                        val rows = mutableListOf<List<RichBlock.Cell>>(); var group = node.firstChild
                        while (group != null && rows.size < 64) {
                            var row = group.firstChild
                            while (row != null && rows.size < 64 && accept(depth + 1)) {
                                val cells = mutableListOf<RichBlock.Cell>(); var cell = row.firstChild
                                while (cell != null && cells.size < 16 && accept(depth + 2)) {
                                    if (cell is TableCell) cells += RichBlock.Cell(inlines(cell, RichRun(""), depth + 3), cell.isHeader, cell.alignment)
                                    cell = cell.next
                                }
                                if (cell != null) limited = true
                                rows += cells; row = row.next
                            }
                            if (row != null) limited = true
                            group = group.next
                        }
                        if (group != null) limited = true
                        result += RichBlock.Table(rows)
                    }
                    else -> result += blocks(node, depth + 1)
                }
                node = node.next
            }
            return result
        }
        private fun inlines(parent: Node, style: RichRun, depth: Int): List<RichRun> {
            val result = mutableListOf<RichRun>(); var node = parent.firstChild
            while (node != null) {
                if (!accept(depth)) break
                when (node) {
                    is Text -> result += style.copy(text = node.literal)
                    is Code -> result += style.copy(text = node.literal, code = true)
                    is SoftLineBreak -> result += style.copy(text = " ")
                    is HardLineBreak -> result += style.copy(text = "\n")
                    is HtmlInline -> result += style.copy(text = node.literal)
                    is Emphasis -> result += inlines(node, style.copy(italic = true), depth + 1)
                    is StrongEmphasis -> result += inlines(node, style.copy(bold = true), depth + 1)
                    is Strikethrough -> result += inlines(node, style.copy(strike = true), depth + 1)
                    is Link -> result += inlines(node, style.copy(link = node.destination), depth + 1)
                    is Image -> {
                        val alt = inlines(node, style.copy(link = null), depth + 1).joinToString("") { it.text }
                        result += style.copy(text = alt, link = style.link ?: node.destination, image = true)
                    }
                    else -> result += inlines(node, style, depth + 1)
                }
                node = node.next
            }
            return result
        }
    }
}
