package dev.androidagent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownFencedCodeParserTest {
    @Test
    fun parsesSingleBlockWithSurroundingProse() {
        val chunks = MarkdownFencedCodeParser.parse("Before\n```kotlin\nval answer = 42\n```\nAfter")

        assertEquals(3, chunks.size)
        assertEquals("Before\n", (chunks[0] as MarkdownFencedCodeChunk.Prose).text)

        val codeBlock = chunks[1] as MarkdownFencedCodeChunk.CodeBlock
        assertEquals("val answer = 42\n", codeBlock.code)
        assertEquals("val answer = 42\n", codeBlock.copyText)
        assertEquals("kotlin", codeBlock.info)
        assertEquals("kotlin", codeBlock.language)

        assertEquals("After", (chunks[2] as MarkdownFencedCodeChunk.Prose).text)
    }

    @Test
    fun parsesMultipleBlocksInOrder() {
        val chunks = MarkdownFencedCodeParser.parse(
            "Intro\n```text\nfirst\n```\nMiddle\n```text\nsecond\n```\nOutro"
        )

        assertEquals(5, chunks.size)
        assertEquals("Intro\n", (chunks[0] as MarkdownFencedCodeChunk.Prose).text)
        assertEquals("first\n", (chunks[1] as MarkdownFencedCodeChunk.CodeBlock).code)
        assertEquals("Middle\n", (chunks[2] as MarkdownFencedCodeChunk.Prose).text)
        assertEquals("second\n", (chunks[3] as MarkdownFencedCodeChunk.CodeBlock).copyText)
        assertEquals("Outro", (chunks[4] as MarkdownFencedCodeChunk.Prose).text)
    }

    @Test
    fun keepsFullInfoStringAndExtractsLanguageTag() {
        val chunks = MarkdownFencedCodeParser.parse("```kotlin runnable\nprintln(\"hi\")\n```")

        val codeBlock = chunks.single() as MarkdownFencedCodeChunk.CodeBlock
        assertEquals("kotlin runnable", codeBlock.info)
        assertEquals("kotlin", codeBlock.language)
        assertEquals("println(\"hi\")\n", codeBlock.copyText)
    }

    @Test
    fun supportsTildeAndBacktickFences() {
        val chunks = MarkdownFencedCodeParser.parse(
            "~~~~js\nconsole.log(1)\n~~~~\n```text\nok\n```"
        )

        assertEquals(2, chunks.size)
        assertEquals("console.log(1)\n", (chunks[0] as MarkdownFencedCodeChunk.CodeBlock).copyText)
        assertEquals("js", (chunks[0] as MarkdownFencedCodeChunk.CodeBlock).language)
        assertEquals("ok\n", (chunks[1] as MarkdownFencedCodeChunk.CodeBlock).copyText)
        assertEquals("text", (chunks[1] as MarkdownFencedCodeChunk.CodeBlock).language)
    }

    @Test
    fun parsesUnterminatedFenceThroughEndOfSource() {
        val chunks = MarkdownFencedCodeParser.parse("Before\n```json\n{\"ok\": true}")

        assertEquals(2, chunks.size)
        assertEquals("Before\n", (chunks[0] as MarkdownFencedCodeChunk.Prose).text)
        val codeBlock = chunks[1] as MarkdownFencedCodeChunk.CodeBlock
        assertEquals("json", codeBlock.language)
        assertEquals("{\"ok\": true}", codeBlock.copyText)
    }

    @Test
    fun returnsPlainProseWhenNoFenceExists() {
        val chunks = MarkdownFencedCodeParser.parse("No code here\njust prose.")

        assertEquals(1, chunks.size)
        assertTrue(chunks.single() is MarkdownFencedCodeChunk.Prose)
        assertEquals("No code here\njust prose.", (chunks.single() as MarkdownFencedCodeChunk.Prose).text)
    }
}
