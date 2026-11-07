// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.data

import android.content.Context
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.edit.MarkerPoint
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Marker(val id: Long, val atMs: Long, val name: String = "") {
    fun point() = MarkerPoint(id, atMs, name)

    companion object {
        fun of(p: MarkerPoint) = Marker(p.id, p.atMs, p.name)
    }
}

@Serializable
data class Recording(
    val id: Long,
    /** Nombre del archivo dentro de files/recordings. */
    val file: String,
    val title: String,
    val createdAt: Long,
    val durationMs: Long = 0,
    val format: String = "flac",
    val sampleRate: Int = 48_000,
    val channels: Int = 1,
    val bitsPerSample: Int = 24,
    val bitrate: Int = 0,
    val sizeBytes: Long = 0,
    /** Carpeta (id), 0 = sin carpeta. */
    val folder: Long = 0,
    val tags: List<String> = emptyList(),
    val markers: List<Marker> = emptyList(),
    val starred: Boolean = false,
    /** Momento en que se mandó a la papelera, 0 = no está en la papelera. */
    val trashedAt: Long = 0,
    /** Se está grabando (o se cortó la grabación y hay que recuperarla). */
    val inProgress: Boolean = false,
    /** Origen, si se importó de otro archivo (nombre visible). */
    val importedFrom: String = "",
) {
    val audioFormat: AudioFormat get() = AudioFormat.of(format)
}

@Serializable
data class Folder(val id: Long, val name: String)

@Serializable
data class LibraryState(
    val recordings: List<Recording> = emptyList(),
    val folders: List<Folder> = emptyList(),
    /** Etiquetas creadas, en el orden en que se muestran (aunque ninguna grabación las use). */
    val tags: List<String> = emptyList(),
) {
    val active: List<Recording> get() = recordings.filter { it.trashedAt == 0L && !it.inProgress }
    val trashed: List<Recording> get() = recordings.filter { it.trashedAt != 0L }
}

/** Grabaciones y su organización. Todo vive en la carpeta privada de la app. */
object Library {
    lateinit var store: JsonStore<LibraryState>
    lateinit var dir: File
    lateinit var peaksDir: File

    const val TRASH_DAYS = 30

    fun init(context: Context) {
        dir = File(context.filesDir, "recordings").apply { mkdirs() }
        peaksDir = File(context.filesDir, "peaks").apply { mkdirs() }
        store = JsonStore(File(context.filesDir, "library.json"), LibraryState.serializer(), LibraryState())
        purgeTrash()
    }

    val state get() = store.state

    fun get(id: Long): Recording? = store.value.recordings.firstOrNull { it.id == id }

    fun fileOf(r: Recording) = File(dir, r.file)
    fun peaksOf(id: Long) = File(peaksDir, "$id.peaks")

    fun newId(): Long {
        val now = System.currentTimeMillis()
        val max = store.value.recordings.maxOfOrNull { it.id } ?: 0
        return maxOf(now, max + 1)
    }

    fun add(r: Recording) = store.update { it.copy(recordings = it.recordings + r) }

    fun update(id: Long, f: (Recording) -> Recording) = store.update { s ->
        s.copy(recordings = s.recordings.map { if (it.id == id) f(it) else it })
    }

    fun rename(id: Long, title: String) = update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    fun toggleStar(id: Long) = update(id) { it.copy(starred = !it.starred) }

    fun setStarred(ids: Collection<Long>, starred: Boolean) =
        store.update { s -> s.copy(recordings = s.recordings.map { if (it.id in ids) it.copy(starred = starred) else it }) }

    /** Pone [tag] en todas o la saca de todas. */
    fun setTagFor(ids: Collection<Long>, tag: String, on: Boolean) = store.update { s ->
        s.copy(recordings = s.recordings.map { r -> if (r.id !in ids) r else r.copy(tags = if (on) (r.tags + tag).distinct() else r.tags - tag) })
    }

    fun setMarkers(id: Long, markers: List<Marker>) = update(id) { it.copy(markers = markers.sortedBy { m -> m.atMs }) }

    fun trash(ids: Collection<Long>) {
        val now = System.currentTimeMillis()
        store.update { s -> s.copy(recordings = s.recordings.map { if (it.id in ids) it.copy(trashedAt = now) else it }) }
    }

    fun restore(ids: Collection<Long>) =
        store.update { s -> s.copy(recordings = s.recordings.map { if (it.id in ids) it.copy(trashedAt = 0) else it }) }

    fun deleteForever(ids: Collection<Long>) {
        val gone = store.value.recordings.filter { it.id in ids }
        gone.forEach { fileOf(it).delete(); peaksOf(it.id).delete() }
        store.update { s -> s.copy(recordings = s.recordings.filter { it.id !in ids }) }
    }

    /** Lo que está en la papelera hace más de [TRASH_DAYS] días se borra del todo. */
    fun purgeTrash() {
        val limit = System.currentTimeMillis() - TRASH_DAYS * 86_400_000L
        val old = store.value.recordings.filter { it.trashedAt in 1 until limit }.map { it.id }
        if (old.isNotEmpty()) deleteForever(old)
    }

    // --- Carpetas ---

    fun addFolder(name: String): Long {
        val id = (store.value.folders.maxOfOrNull { it.id } ?: 0) + 1
        store.update { it.copy(folders = it.folders + Folder(id, name.trim())) }
        return id
    }

    fun renameFolder(id: Long, name: String) =
        store.update { s -> s.copy(folders = s.folders.map { if (it.id == id) it.copy(name = name.trim()) else it }) }

    /** Borra la carpeta; sus grabaciones quedan sin carpeta (no se borran). */
    fun deleteFolder(id: Long) = store.update { s ->
        s.copy(
            folders = s.folders.filter { it.id != id },
            recordings = s.recordings.map { if (it.folder == id) it.copy(folder = 0) else it },
        )
    }

    fun move(ids: Collection<Long>, folder: Long) =
        store.update { s -> s.copy(recordings = s.recordings.map { if (it.id in ids) it.copy(folder = folder) else it }) }

    // --- Etiquetas ---

    fun addTag(name: String) {
        val t = name.trim()
        if (t.isEmpty()) return
        store.update { s -> if (s.tags.any { it.equals(t, ignoreCase = true) }) s else s.copy(tags = s.tags + t) }
    }

    fun setTags(id: Long, tags: List<String>) = update(id) { it.copy(tags = tags.distinct()) }

    fun renameTag(old: String, new: String) {
        val n = new.trim()
        if (n.isEmpty()) return
        store.update { s ->
            s.copy(
                tags = s.tags.map { if (it == old) n else it }.distinct(),
                recordings = s.recordings.map { r -> if (old in r.tags) r.copy(tags = r.tags.map { if (it == old) n else it }.distinct()) else r },
            )
        }
    }

    fun deleteTag(tag: String) = store.update { s ->
        s.copy(tags = s.tags - tag, recordings = s.recordings.map { r -> if (tag in r.tags) r.copy(tags = r.tags - tag) else r })
    }

    /** Espacio que ocupan las grabaciones (incluida la papelera). */
    fun usedBytes(): Long = store.value.recordings.sumOf { fileOf(it).length() }
}
