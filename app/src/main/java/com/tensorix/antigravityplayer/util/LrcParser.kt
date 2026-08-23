package com.tensorix.antigravityplayer.util

data class LrcLine(
    val timeMs: Long,
    val text: String
)

/**
 * Robust LRC lyrics parser supporting all standard and non-standard lyric timestamp formats:
 * - Single tag: [00:02.00] Lyrics text
 * - Multi tag per line: [00:02.00][01:05.50] Repeated lyrics text
 * - Colon/dot separators: [00:02:00], [0:02.5], [00:02.500]
 */
object LrcParser {
    fun parse(lrcContent: String): List<LrcLine> {
        val result = mutableListOf<LrcLine>()
        if (lrcContent.isBlank()) return result

        val lineRegex = Regex("\\[(\\d{1,3}):(\\d{2})[.:](\\d{1,3})\\]")

        for (line in lrcContent.lines()) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            val matches = lineRegex.findAll(trimmedLine).toList()
            if (matches.isNotEmpty()) {
                val text = lineRegex.replace(trimmedLine, "").trim()
                if (text.isNotEmpty()) {
                    for (m in matches) {
                        val min = m.groupValues[1].toLongOrNull() ?: 0L
                        val sec = m.groupValues[2].toLongOrNull() ?: 0L
                        val millisStr = m.groupValues[3]
                        val millis = when (millisStr.length) {
                            1 -> millisStr.toLong() * 100
                            2 -> millisStr.toLong() * 10
                            else -> millisStr.toLong()
                        }
                        val timeMs = min * 60 * 1000 + sec * 1000 + millis
                        result.add(LrcLine(timeMs, text))
                    }
                }
            }
        }
        return result.sortedBy { it.timeMs }
    }
}
