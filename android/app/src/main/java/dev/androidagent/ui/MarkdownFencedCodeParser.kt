package dev.androidagent.ui

sealed interface MarkdownFencedCodeChunk {
    data class Prose(val text: String) : MarkdownFencedCodeChunk

    data class CodeBlock(
        val code: String,
        val info: String?
    ) : MarkdownFencedCodeChunk {
        val copyText: String = code
        val language: String? = info
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

object MarkdownFencedCodeParser {
    fun parse(source: String): List<MarkdownFencedCodeChunk> {
        if (source.isEmpty()) {
            return emptyList()
        }

        val chunks = mutableListOf<MarkdownFencedCodeChunk>()
        var lineStart = 0
        var proseStart = 0

        while (lineStart < source.length) {
            val line = source.lineAt(lineStart)
            val openingFence = openingFenceFor(line.content)

            if (openingFence == null) {
                lineStart = line.nextStart
                continue
            }

            addProseChunk(chunks, source.substring(proseStart, lineStart))

            val codeStart = line.nextStart
            var scanStart = codeStart
            while (scanStart < source.length) {
                val scanLine = source.lineAt(scanStart)
                if (isClosingFence(scanLine.content, openingFence)) {
                    chunks.add(MarkdownFencedCodeChunk.CodeBlock(
                        code = source.substring(codeStart, scanStart),
                        info = openingFence.info
                    ))
                    lineStart = scanLine.nextStart
                    proseStart = lineStart
                    break
                }
                scanStart = scanLine.nextStart
            }

            if (scanStart >= source.length) {
                chunks.add(MarkdownFencedCodeChunk.CodeBlock(
                    code = source.substring(codeStart),
                    info = openingFence.info
                ))
                return chunks
            }
        }

        addProseChunk(chunks, source.substring(proseStart))
        return chunks
    }

    private fun addProseChunk(
        chunks: MutableList<MarkdownFencedCodeChunk>,
        text: String
    ) {
        if (text.isNotEmpty()) {
            chunks.add(MarkdownFencedCodeChunk.Prose(text))
        }
    }

    private fun openingFenceFor(line: String): Fence? {
        val indent = leadingSpaces(line)
        if (indent > MaxFenceIndent || indent >= line.length) {
            return null
        }

        val marker = line[indent]
        if (marker != Backtick && marker != Tilde) {
            return null
        }

        val markerEnd = line.indexAfterRun(indent, marker)
        val length = markerEnd - indent
        if (length < MinFenceLength) {
            return null
        }

        val rawInfo = line.substring(markerEnd).trim()
        if (marker == Backtick && rawInfo.contains(Backtick)) {
            return null
        }

        return Fence(
            marker = marker,
            length = length,
            info = rawInfo.ifEmpty { null }
        )
    }

    private fun isClosingFence(line: String, openingFence: Fence): Boolean {
        val indent = leadingSpaces(line)
        if (indent > MaxFenceIndent || indent >= line.length) {
            return false
        }

        if (line[indent] != openingFence.marker) {
            return false
        }

        val markerEnd = line.indexAfterRun(indent, openingFence.marker)
        if (markerEnd - indent < openingFence.length) {
            return false
        }

        return line.substring(markerEnd).all { it == ' ' || it == '\t' }
    }

    private fun leadingSpaces(line: String): Int {
        var count = 0
        while (count < line.length && line[count] == ' ') {
            count += 1
        }
        return count
    }

    private fun String.indexAfterRun(start: Int, marker: Char): Int {
        var index = start
        while (index < length && this[index] == marker) {
            index += 1
        }
        return index
    }

    private fun String.lineAt(start: Int): Line {
        val newlineIndex = indexOf('\n', start).let { if (it == -1) length else it }
        val contentEnd = if (newlineIndex > start && this[newlineIndex - 1] == '\r') {
            newlineIndex - 1
        } else {
            newlineIndex
        }
        val nextStart = if (newlineIndex < length) newlineIndex + 1 else length
        return Line(
            content = substring(start, contentEnd),
            nextStart = nextStart
        )
    }

    private data class Fence(
        val marker: Char,
        val length: Int,
        val info: String?
    )

    private data class Line(
        val content: String,
        val nextStart: Int
    )

    private const val Backtick = '`'
    private const val Tilde = '~'
    private const val MaxFenceIndent = 3
    private const val MinFenceLength = 3
}
