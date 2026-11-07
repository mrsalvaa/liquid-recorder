// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core

import dev.liquidrecorder.core.edit.Edits
import dev.liquidrecorder.core.edit.MarkerPoint
import dev.liquidrecorder.core.edit.MarkerText
import dev.liquidrecorder.core.edit.Segment
import dev.liquidrecorder.core.text.Names
import dev.liquidrecorder.core.text.TimeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditsTest {
    private val markers = listOf(MarkerPoint(1, 1_000, "a"), MarkerPoint(2, 5_000, "b"), MarkerPoint(3, 9_000, "c"))

    @Test
    fun keepMovesMarkers() {
        val r = Edits.keep(10_000, Segment(4_000, 8_000), markers)
        assertEquals(4_000, r.durationMs)
        assertEquals(listOf(MarkerPoint(2, 1_000, "b")), r.markers)
    }

    @Test
    fun deleteJoinsAndShifts() {
        val r = Edits.delete(10_000, Segment(4_000, 8_000), markers)
        assertEquals(listOf(Segment(0, 4_000), Segment(8_000, 10_000)), r.segments)
        assertEquals(listOf(1_000L, 5_000L), r.markers.map { it.atMs })
        assertEquals(listOf("a", "c"), r.markers.map { it.name })
    }

    @Test
    fun deleteAtStartAndEnd() {
        assertEquals(listOf(Segment(3_000, 10_000)), Edits.delete(10_000, Segment(-5, 3_000), markers).segments)
        assertEquals(listOf(Segment(0, 7_000)), Edits.delete(10_000, Segment(7_000, 99_000), markers).segments)
    }

    @Test
    fun split() {
        val (a, b) = Edits.split(10_000, 5_000, markers)
        assertEquals(listOf("a"), a.markers.map { it.name })
        assertEquals(listOf(0L, 4_000L), b.markers.map { it.atMs })
        assertTrue(Edits.canSplit(10_000, 5_000))
        assertFalse(Edits.canSplit(10_000, 100))
        assertFalse(Edits.canSplit(10_000, 9_900))
    }

    @Test
    fun peaksFollowSegments() {
        val peaks = ByteArray(400) { (it / 4).toByte() } // 10 s a 25 ms
        val out = Edits.remapPeaks(peaks, 25, listOf(Segment(0, 1_000), Segment(9_000, 10_000)))
        assertEquals(80, out.size)
        assertEquals(90.toByte(), out[40])
    }

    @Test
    fun timeText() {
        assertEquals("0:00", TimeText.format(0))
        assertEquals("0:07.4", TimeText.format(7_450, tenths = true))
        assertEquals("12:34", TimeText.format(754_000))
        assertEquals("1:02:03", TimeText.format(3_723_000))
    }

    @Test
    fun names() {
        assertEquals("Grabación 1", Names.next("Grabación", emptyList()))
        assertEquals("Grabación 5", Names.next("Grabación", listOf("Grabación 1", "Grabación 4", "Otra 9", "Grabación x")))
        assertEquals("a_b_c.flac", Names.fileName("a/b:c", "flac"))
        assertEquals("recording.wav", Names.fileName("...", "wav"))
        assertTrue(Names.matches("cancion", listOf("Canción de cuna")))
        assertTrue(Names.matches("reunión lunes", listOf("Reunion", "lunes")))
        assertFalse(Names.matches("martes", listOf("Reunión del lunes")))
    }

    @Test
    fun markerText() {
        val t = MarkerText.export(listOf(MarkerPoint(2, 61_250, ""), MarkerPoint(1, 500, "Inicio"))) { "Marcador $it" }
        assertEquals("0:00.5\tInicio\n1:01.2\tMarcador 2", t)
    }
}
