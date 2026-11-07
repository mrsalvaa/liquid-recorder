// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.text

import java.text.Normalizer
import java.util.Locale

/** Duraciones como texto: "0:07", "12:34", "1:02:03", con décimas opcionales ("0:07.4"). */
object TimeText {
    fun format(ms: Long, tenths: Boolean = false): String {
        val total = ms.coerceAtLeast(0)
        val s = total / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val base = if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) else String.format(Locale.ROOT, "%d:%02d", m, sec)
        return if (tenths) base + "." + (total % 1000) / 100 else base
    }

    /** Tamaño legible: "820 KB", "12,4 MB". */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(locale, "%d KB", bytes / 1024)
        bytes < 1024L * 1024 * 1024 -> String.format(locale, "%.1f MB", bytes / 1048576.0)
        else -> String.format(locale, "%.2f GB", bytes / 1073741824.0)
    }
}

object Names {
    /**
     * Siguiente nombre libre con la forma "[base] N": si ya existen "Grabación 1" y "Grabación 4",
     * devuelve "Grabación 5".
     */
    fun next(base: String, existing: Collection<String>): String {
        val prefix = "$base "
        val max = existing.mapNotNull { t -> if (t.startsWith(prefix)) t.removePrefix(prefix).trim().toIntOrNull() else null }.maxOrNull() ?: 0
        return "$prefix${max + 1}"
    }

    /** Nombre de archivo seguro para exportar: sin caracteres prohibidos ni saltos de línea. */
    fun fileName(title: String, extension: String): String {
        val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trim('.').take(100)
        return (clean.ifEmpty { "recording" }) + "." + extension
    }

    /** Texto para buscar: minúsculas y sin tildes ("Canción" coincide con "cancion"). */
    fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Si [query] (todas sus palabras) aparece en alguno de los [fields]. */
    fun matches(query: String, fields: List<String>): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        val hay = fields.joinToString(" ") { fold(it) }
        return words.all { hay.contains(it) }
    }
}
