// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.edit

/** Marcador en una grabación: posición en ms y nombre (puede quedar vacío). */
data class MarkerPoint(val id: Long, val atMs: Long, val name: String)

/** Tramo de audio [startMs, endMs). */
data class Segment(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = endMs - startMs
}

/**
 * Lo que hay que copiar del original para producir un archivo nuevo, y dónde quedan los marcadores
 * y la forma de onda. Las ediciones nunca tocan el original hasta que el resultado está escrito.
 */
data class EditResult(val segments: List<Segment>, val markers: List<MarkerPoint>) {
    val durationMs: Long get() = segments.sumOf { it.lengthMs }
}

object Edits {
    /** Se queda solo con [range]. */
    fun keep(durationMs: Long, range: Segment, markers: List<MarkerPoint>): EditResult {
        val r = clamp(range, durationMs)
        return EditResult(listOf(r), remap(listOf(r), markers))
    }

    /** Quita [range] y junta lo de antes con lo de después. */
    fun delete(durationMs: Long, range: Segment, markers: List<MarkerPoint>): EditResult {
        val r = clamp(range, durationMs)
        val segments = listOf(Segment(0, r.startMs), Segment(r.endMs, durationMs)).filter { it.lengthMs > 0 }
        return EditResult(segments, remap(segments, markers))
    }

    /** Parte en dos en [atMs]. */
    fun split(durationMs: Long, atMs: Long, markers: List<MarkerPoint>): Pair<EditResult, EditResult> {
        val at = atMs.coerceIn(0, durationMs)
        val a = listOf(Segment(0, at))
        val b = listOf(Segment(at, durationMs))
        return EditResult(a, remap(a, markers)) to EditResult(b, remap(b, markers))
    }

    /** Si el corte tiene sentido (ni vacío ni todo el archivo). */
    fun canSplit(durationMs: Long, atMs: Long, minMs: Long = 500): Boolean = atMs >= minMs && atMs <= durationMs - minMs

    private fun clamp(r: Segment, duration: Long): Segment {
        val s = r.startMs.coerceIn(0, duration)
        val e = r.endMs.coerceIn(s, duration)
        return Segment(s, e)
    }

    /** Lleva cada marcador a su posición en el resultado; los que caen en lo borrado desaparecen. */
    fun remap(segments: List<Segment>, markers: List<MarkerPoint>): List<MarkerPoint> {
        val out = ArrayList<MarkerPoint>()
        for (m in markers) {
            var offset = 0L
            for (s in segments) {
                if (m.atMs >= s.startMs && m.atMs < s.endMs) {
                    out += m.copy(atMs = offset + m.atMs - s.startMs)
                    break
                }
                offset += s.lengthMs
            }
        }
        return out.sortedBy { it.atMs }
    }

    /** Recorta los picos de la onda a los tramos del resultado. */
    fun remapPeaks(peaks: ByteArray, msPerPeak: Int, segments: List<Segment>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (s in segments) {
            val from = (s.startMs / msPerPeak).toInt().coerceIn(0, peaks.size)
            val to = (s.endMs / msPerPeak).toInt().coerceIn(from, peaks.size)
            out.write(peaks, from, to - from)
        }
        return out.toByteArray()
    }
}

/** Marcadores exportados como texto: una línea por marcador, "mm:ss.d<TAB>nombre". */
object MarkerText {
    fun export(markers: List<MarkerPoint>, fallback: (Int) -> String): String =
        markers.sortedBy { it.atMs }.mapIndexed { i, m ->
            dev.liquidrecorder.core.text.TimeText.format(m.atMs, tenths = true) + "\t" + m.name.ifBlank { fallback(i + 1) }
        }.joinToString("\n")
}
