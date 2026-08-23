package com.tensorix.antigravityplayer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `parses standard millisecond tags`() {
        val lines = LrcParser.parse("[00:02.00]Hello world")
        assertEquals(1, lines.size)
        assertEquals(2000L, lines[0].timeMs)
        assertEquals("Hello world", lines[0].text)
    }

    @Test
    fun `parses colon-separated and short formats`() {
        val lines = LrcParser.parse(
            """
            [00:02:00]colon style
            [0:05.5]short minute half-second
            """.trimIndent()
        )
        assertEquals(2, lines.size)
        assertEquals(2000L, lines[0].timeMs)
        assertEquals(5500L, lines[1].timeMs)
    }

    @Test
    fun `multi-tag line repeats text for each timestamp`() {
        val lines = LrcParser.parse("[00:02.00][01:05.50]Repeated lyrics")
        assertEquals(2, lines.size)
        assertEquals(2000L, lines[0].timeMs)
        assertEquals(65500L, lines[1].timeMs)
        assertEquals("Repeated lyrics", lines[0].text)
    }

    @Test
    fun `output is sorted by time`() {
        val lines = LrcParser.parse(
            "[01:00.00]later\n[00:10.00]earlier"
        )
        assertEquals("earlier", lines[0].text)
        assertEquals("later", lines[1].text)
    }

    @Test
    fun `blank content and tagless lines yield empty result`() {
        assertTrue(LrcParser.parse("").isEmpty())
        assertTrue(LrcParser.parse("no timestamps here").isEmpty())
        assertTrue(LrcParser.parse("[ar:Artist]\n[ti:Title]").isEmpty())
    }
}
