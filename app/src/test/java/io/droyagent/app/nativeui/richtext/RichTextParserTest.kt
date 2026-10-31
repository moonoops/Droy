package io.droyagent.app.nativeui.richtext

import org.junit.Assert.*
import org.junit.Test
import org.commonmark.ext.gfm.tables.TableCell
import java.util.concurrent.Executors

class RichTextParserTest {
    @Test fun headingsEmphasisAndCodeUseCommonMarkNodes() {
        val doc = RichTextParser.parse("# Heading\n\nHello **bold** and *italic*, ~~gone~~ and `x<y`.")
        assertEquals(1, (doc.blocks[0] as RichBlock.Paragraph).heading)
        val runs = (doc.blocks[1] as RichBlock.Paragraph).runs
        assertTrue(runs.any { it.text == "bold" && it.bold }); assertTrue(runs.any { it.text == "italic" && it.italic })
        assertTrue(runs.any { it.text == "gone" && it.strike }); assertTrue(runs.any { it.text == "x<y" && it.code })
        assertFalse(doc.limited)
    }
    @Test fun fencedCodePreservesExactCopyContentAndLanguage() {
        val code = RichTextParser.parse("```kotlin extra\nval x = \"<tag>\"\n  println(x)\n```\n").blocks.single() as RichBlock.CodeBlock
        assertEquals("kotlin", code.language); assertEquals("val x = \"<tag>\"\n  println(x)\n", code.code)
        val indented = RichTextParser.parse("    one\n    two\n").blocks.single() as RichBlock.CodeBlock
        assertEquals("one\ntwo\n", indented.code)
    }
    @Test fun orderedListsKeepStartAndNestedBlocks() {
        val list = RichTextParser.parse("3. three\n4. four\n   - nested\n").blocks.single() as RichBlock.Items
        assertEquals(3, list.start); assertEquals(2, list.items.size)
        assertTrue(list.items[1].any { it is RichBlock.Items })
        val quote = RichTextParser.parse("> quote\n>\n> **strong**").blocks.single() as RichBlock.Quote
        assertEquals(2, quote.blocks.size)
    }
    @Test fun gfmTablesPreserveCellsHeadersAndAlignment() {
        val table = RichTextParser.parse("| Left | Right |\n| :--- | ---: |\n| **one** | `two` |\n").blocks.single() as RichBlock.Table
        assertEquals(2, table.rows.size); assertTrue(table.rows[0][0].header)
        assertEquals(TableCell.Alignment.RIGHT, table.rows[1][1].alignment)
        assertTrue(table.rows[1][0].runs.single().bold); assertTrue(table.rows[1][1].runs.single().code)
    }
    @Test fun rawHtmlIsLiteralAndImagesAreOnlyExplicitLinkModels() {
        val html = RichTextParser.parse("<script>alert('fixture')</script>\n").blocks.single() as RichBlock.Paragraph
        assertTrue(html.runs.single().text.contains("<script>")); assertNull(html.runs.single().link)
        val inline = RichTextParser.parse("Text <b>raw</b> [file](./report.md) ![chart](https://example.test/chart.png)").blocks.single() as RichBlock.Paragraph
        assertTrue(inline.runs.any { it.text == "<b>" && it.link == null })
        assertEquals("./report.md", inline.runs.first { it.text == "file" }.link)
        assertEquals("https://example.test/chart.png", inline.runs.single { it.image }.link)
    }
    @Test fun commonMarkReferencesAndEscapesAreNotRegexParsed() {
        val paragraph = RichTextParser.parse("[named][id] \\*literal\\* &amp;\n\n[id]: https://example.test/a_(b)\n").blocks.single() as RichBlock.Paragraph
        assertEquals("https://example.test/a_(b)", paragraph.runs.first().link)
        assertTrue(paragraph.runs.joinToString("") { it.text }.contains("*literal* &"))
    }
    @Test fun oversizedMessagesAndPathologicalNestingAreBounded() {
        val doc = RichTextParser.parse("x".repeat(RichTextParser.MAX_CHARACTERS) + "tail")
        assertTrue(doc.limited); assertEquals(RichTextParser.MAX_CHARACTERS, (doc.blocks.single() as RichBlock.Paragraph).runs.single().text.length)
        val many = RichTextParser.parse("one\n\n".repeat(1000)); assertTrue(many.limited); assertTrue(many.blocks.size <= 256)
        val nested = RichTextParser.parse("> ".repeat(1000) + "end"); assertTrue(nested.blocks.isNotEmpty())
    }
    @Test fun sharedParserDoesNotMixStreamingMessagesAcrossThreads() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val results = (1..20).map { n -> pool.submit<String> { (RichTextParser.parse("# message-$n").blocks.single() as RichBlock.Paragraph).runs.single().text } }
            assertEquals((1..20).map { "message-$it" }, results.map { it.get() })
        } finally { pool.shutdownNow() }
    }
}
