// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.liquidrecorder.R
import dev.liquidrecorder.audio.AudioDecoder
import dev.liquidrecorder.audio.NoAudioException
import dev.liquidrecorder.audio.Playback
import dev.liquidrecorder.audio.Transcoder
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.audio.EncoderConfig
import dev.liquidrecorder.core.audio.PcmConverter
import dev.liquidrecorder.audio.openSink
import dev.liquidrecorder.core.edit.EditResult
import dev.liquidrecorder.core.edit.Edits
import dev.liquidrecorder.core.edit.MarkerText
import dev.liquidrecorder.core.edit.Segment
import dev.liquidrecorder.core.text.Names
import dev.liquidrecorder.util.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Una tarea larga en curso (exportar, importar, editar, limpiar), con su avance de 0 a 1. */
data class Job(val label: String, val progress: Float = 0f, val startedAt: Long = android.os.SystemClock.elapsedRealtime()) {
    /** Milisegundos que faltan, estimados por lo que tardó hasta ahora (null al principio). */
    val remainingMs: Long?
        get() {
            if (progress < 0.03f) return null
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
            return (elapsed * (1 - progress) / progress).toLong()
        }
}

object Jobs {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _current = MutableStateFlow<Job?>(null)
    val current = _current.asStateFlow()
    @Volatile private var cancelled = false
    /** Mensajes al terminar (éxito o error), para mostrarlos como aviso. */
    val messages = MutableStateFlow<String?>(null)

    fun cancel() { cancelled = true }

    private var context: Context? = null

    fun init(app: Context) { context = app.applicationContext }

    /** Corre [block] en segundo plano; una sola tarea a la vez. */
    fun run(label: String, block: suspend (progress: (Float) -> Boolean) -> String?) {
        if (_current.value != null) {
            messages.value = str(R.string.job_busy)
            return
        }
        cancelled = false
        val job = Job(label)
        _current.value = job
        // Notificación con el avance: se ve fuera de la app y la mantiene viva hasta terminar.
        context?.let { dev.liquidrecorder.service.JobService.start(it) }
        scope.launch {
            val msg = try {
                block { p -> _current.value = job.copy(progress = p); !cancelled }
            } catch (e: NoAudioException) {
                str(R.string.error_no_audio)
            } catch (e: Exception) {
                str(R.string.error_job, e.javaClass.simpleName)
            }
            _current.value = null
            messages.value = if (cancelled) str(R.string.cancelled) else msg
        }
    }
}

object Actions {
    private fun authority(context: Context) = "${context.packageName}.files"

    fun uriOf(context: Context, r: Recording): Uri = FileProvider.getUriForFile(context, authority(context), Library.fileOf(r))

    /** Comparte el archivo original (con permiso temporal de lectura, solo para la app elegida). */
    fun share(context: Context, recordings: List<Recording>) {
        if (recordings.isEmpty()) return
        val uris = recordings.map { r ->
            // Se comparte con su nombre visible: copia temporal con el título.
            val dir = File(context.cacheDir, "export").apply { mkdirs() }
            val named = File(dir, Names.fileName(r.title, r.audioFormat.extension))
            Library.fileOf(r).copyTo(named, overwrite = true)
            FileProvider.getUriForFile(context, authority(context), named)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0]).setType(recordings[0].audioFormat.mime)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).setType("audio/*")
        }
        intent.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, str(R.string.share)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Comparte los marcadores como texto ("0:12.3  Nombre"). */
    fun shareMarkers(context: Context, r: Recording) {
        val text = r.title + "\n\n" + MarkerText.export(r.markers.map { it.point() }) { str(R.string.marker_n, it) }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(intent, str(R.string.share_markers)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Borra las copias temporales compartidas. */
    fun cleanShared(context: Context) {
        File(context.cacheDir, "export").listFiles()?.forEach { it.delete() }
    }

    fun configFor(r: Recording, format: AudioFormat, settings: AppSettings): EncoderConfig = EncoderConfig(
        format = format,
        sampleRate = if (format == AudioFormat.OPUS) 48_000 else r.sampleRate,
        channels = r.channels.coerceIn(1, 2),
        bitsPerSample = if (r.audioFormat.lossless) r.bitsPerSample else settings.bitsPerSample,
        bitrate = when (format) {
            AudioFormat.AAC -> settings.aacBitrate
            AudioFormat.OPUS -> settings.opusBitrate
            else -> 0
        },
    )

    /** Guarda una copia en el lugar que eligió el usuario (selector de archivos del sistema). */
    fun exportTo(context: Context, r: Recording, format: AudioFormat, target: Uri) {
        Jobs.run(str(R.string.exporting)) { progress ->
            val out = context.contentResolver.openOutputStream(target, "wt") ?: return@run str(R.string.error_export)
            if (format == r.audioFormat) {
                out.use { o -> Library.fileOf(r).inputStream().use { it.copyTo(o) } }
            } else {
                val tmp = File(context.cacheDir, "export-${r.id}.${format.extension}")
                val done = AudioDecoder.open(Library.fileOf(r)).use { d ->
                    Transcoder.render(d, null, configFor(r, format, Stores.settings.value), tmp, progress)
                }
                if (done == null) { out.close(); return@run null }
                out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                tmp.delete()
            }
            str(R.string.exported)
        }
    }

    /**
     * Exporta varias grabaciones a una carpeta elegida con el selector del sistema (permiso solo
     * sobre esa carpeta). Las que ya están en [format] se copian tal cual; las demás se convierten.
     */
    fun exportAll(context: Context, recordings: List<Recording>, format: AudioFormat, tree: Uri) {
        Jobs.run(str(R.string.exporting)) { progress ->
            val resolver = context.contentResolver
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
            recordings.forEachIndexed { i, r ->
                val target = android.provider.DocumentsContract.createDocument(resolver, parent, format.mime, Names.fileName(r.title, format.extension))
                    ?: return@run str(R.string.error_export)
                val step: (Float) -> Boolean = { p -> progress((i + p) / recordings.size) }
                val out = resolver.openOutputStream(target, "wt") ?: return@run str(R.string.error_export)
                if (format == r.audioFormat) {
                    out.use { o -> Library.fileOf(r).inputStream().use { it.copyTo(o) } }
                    if (!step(1f)) return@run null
                } else {
                    val tmp = File(context.cacheDir, "export-${r.id}.${format.extension}")
                    val done = AudioDecoder.open(Library.fileOf(r)).use { d -> Transcoder.render(d, null, configFor(r, format, Stores.settings.value), tmp, step) }
                    if (done == null) { out.close(); return@run null }
                    out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
                    tmp.delete()
                }
            }
            dev.liquidrecorder.util.plural(R.plurals.exported_count, recordings.size)
        }
    }

    fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "audio"

    /**
     * Copia el audio de un archivo de afuera (también de un video) a la biblioteca, en el formato
     * de los ajustes. El original no se toca.
     */
    fun import(context: Context, uri: Uri, onDone: (Long) -> Unit = {}) {
        val name = displayName(context, uri)
        Jobs.run(str(R.string.importing)) { progress ->
            val s = Stores.settings.value
            AudioDecoder.open(context, uri).use { d ->
                val format = AudioFormat.of(s.format)
                val cfg = EncoderConfig(
                    format = format,
                    sampleRate = if (format == AudioFormat.OPUS) 48_000 else d.sampleRate.coerceIn(8_000, 192_000),
                    channels = d.channels.coerceIn(1, 2),
                    bitsPerSample = s.bitsPerSample,
                    bitrate = if (format == AudioFormat.AAC) s.aacBitrate else s.opusBitrate,
                )
                val id = Library.newId()
                val file = File(Library.dir, "$id.${format.extension}")
                val res = Transcoder.render(d, null, cfg, file, progress) ?: return@run null
                Library.peaksOf(id).writeBytes(res.peaks)
                Library.add(
                    Recording(
                        id = id, file = file.name, title = name.substringBeforeLast('.').ifBlank { name },
                        createdAt = System.currentTimeMillis(), durationMs = res.durationMs, format = format.id,
                        sampleRate = cfg.sampleRate, channels = cfg.channels, bitsPerSample = cfg.bitsPerSample,
                        bitrate = if (format.lossless) 0 else cfg.bitrate, sizeBytes = file.length(), importedFrom = name,
                    ),
                )
                kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(id) }
            }
            str(R.string.imported)
        }
    }

    /** Escribe el resultado de una edición como grabación nueva, en el mismo formato. */
    private fun write(context: Context, r: Recording, edit: EditResult, title: String, progress: (Float) -> Boolean): Recording? {
        val id = Library.newId()
        val file = File(Library.dir, "$id.${r.audioFormat.extension}")
        val cfg = EncoderConfig(r.audioFormat, r.sampleRate, r.channels, r.bitsPerSample, if (r.bitrate > 0) r.bitrate else 256_000)
        val res = AudioDecoder.open(Library.fileOf(r)).use { d -> Transcoder.render(d, edit.segments, cfg, file, progress) } ?: return null
        // La onda nueva sale del audio escrito; los marcadores se corren con los cortes.
        Library.peaksOf(id).writeBytes(res.peaks)
        val rec = r.copy(
            id = id, file = file.name, title = title, createdAt = System.currentTimeMillis(),
            durationMs = res.durationMs, sizeBytes = file.length(), markers = edit.markers.map { Marker.of(it) },
            trashedAt = 0, inProgress = false,
        )
        Library.add(rec)
        return rec
    }

    enum class EditKind { KEEP, DELETE }

    /**
     * Recorta (se queda con la selección) o borra la selección. Con [replace], el original va a la
     * papelera (se puede recuperar 30 días); si no, queda una copia nueva.
     */
    fun edit(context: Context, r: Recording, kind: EditKind, range: Segment, replace: Boolean, onDone: (Long) -> Unit) {
        Jobs.run(str(R.string.saving_edit)) { progress ->
            val markers = r.markers.map { it.point() }
            val plan = if (kind == EditKind.KEEP) Edits.keep(r.durationMs, range, markers) else Edits.delete(r.durationMs, range, markers)
            val title = if (replace) r.title else str(R.string.edited_title, r.title)
            val rec = write(context, r, plan, title, progress) ?: return@run null
            if (replace) {
                Library.update(rec.id) { it.copy(createdAt = r.createdAt) }
                // ExoPlayer solo se puede usar desde el hilo principal.
                kotlinx.coroutines.withContext(Dispatchers.Main) { Playback.closeIf(r.id) }
                Library.trash(listOf(r.id))
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(rec.id) }
            str(R.string.edit_saved)
        }
    }

    /**
     * Copia limpia de una o varias grabaciones (core/dsp/CleanPipeline): pasa-altos, perfil de ruido
     * automático, sustracción espectral en dos pasadas, medias no locales opcionales y volumen EBU
     * R128. Sin IA y sin red. El original no se toca: el resultado es una grabación nueva
     * "(limpia)", con los mismos marcadores en el mismo lugar.
     */
    fun clean(context: Context, recordings: List<Recording>, options: dev.liquidrecorder.core.dsp.CleanOptions, onDone: (Long?) -> Unit = {}) {
        if (recordings.isEmpty()) return
        val label = if (recordings.size == 1) str(R.string.cleaning_one, recordings[0].title) else str(R.string.cleaning_many, recordings.size)
        Jobs.run(label) { progress ->
            var last: Long? = null
            recordings.forEachIndexed { i, r ->
                val step: (Float) -> Boolean = { p -> progress((i + p) / recordings.size) }
                last = cleanOne(context, r, options, step) ?: return@run null
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone(last) }
            if (recordings.size == 1) str(R.string.cleaned) else dev.liquidrecorder.util.plural(R.plurals.cleaned_count, recordings.size)
        }
    }

    private fun cleanOne(context: Context, r: Recording, options: dev.liquidrecorder.core.dsp.CleanOptions, progress: (Float) -> Boolean): Long? {
        val ch = r.channels.coerceIn(1, 2)
        val source = Library.fileOf(r)
        val totalFrames = r.durationMs * r.sampleRate / 1000
        /** Una pasada por un archivo, siempre con [ch] canales. */
        fun passOf(file: File) = dev.liquidrecorder.core.dsp.AudioPass { onChunk ->
            var ok = true
            AudioDecoder.open(file).use { d ->
                d.decode { chunk, n ->
                    val data = dev.liquidrecorder.core.audio.remapChannels(chunk, n, d.channels, ch)
                    ok = onChunk(if (data === chunk) chunk.copyOf(n * ch) else data, n)
                    ok
                }
            }
            ok
        }
        // Temporal en FLAC de 24 bits: la mitad de espacio que en coma flotante y sin pérdida.
        val tmp = File(context.cacheDir, "clean-${r.id}.flac")
        val tempWriter = dev.liquidrecorder.core.audio.FlacWriter(tmp, r.sampleRate, ch, 24)
        val tempConv = PcmConverter(24)
        var tempInts = IntArray(0)

        val id = Library.newId()
        val file = File(Library.dir, "$id.${r.audioFormat.extension}")
        val cfg = EncoderConfig(r.audioFormat, r.sampleRate, ch, if (r.audioFormat.lossless) r.bitsPerSample else 24, if (r.bitrate > 0) r.bitrate else 256_000)
        val sink = openSink(file, cfg)
        val conv = PcmConverter(cfg.pcmBits)
        var ints = IntArray(0)
        val peaks = dev.liquidrecorder.core.dsp.PeakCollector(r.sampleRate)
        var mono = FloatArray(0)
        var frames = 0L

        val report = try {
            dev.liquidrecorder.core.dsp.CleanPipeline(ch, r.sampleRate, options).run(
                original = passOf(source),
                temp = passOf(tmp),
                tempOut = { d, n ->
                    if (tempInts.size < n * ch) tempInts = IntArray(n * ch)
                    tempConv.convert(d, n * ch, 1f, tempInts)
                    tempWriter.write(tempInts, n)
                },
                tempDone = { tempWriter.finish() },
                out = { d, n ->
                    if (ints.size < n * ch) ints = IntArray(n * ch)
                    conv.convert(d, n * ch, 1f, ints)
                    sink.write(ints, n)
                    if (mono.size < n) mono = FloatArray(n)
                    dev.liquidrecorder.core.audio.downmix(d, n, ch, mono)
                    peaks.push(mono, n)
                    frames += n
                },
                totalFrames = totalFrames,
                progress = progress,
            )
        } finally {
            runCatching { sink.finish() }
            tmp.delete()
        }
        if (report == null) {
            file.delete()
            return null
        }
        Library.peaksOf(id).writeBytes(peaks.toByteArray())
        Library.add(
            r.copy(
                id = id, file = file.name, title = str(R.string.cleaned_title, r.title), createdAt = System.currentTimeMillis(),
                durationMs = frames * 1000 / r.sampleRate, sizeBytes = file.length(), channels = ch,
                bitsPerSample = cfg.bitsPerSample, trashedAt = 0, inProgress = false, starred = false,
            ),
        )
        return id
    }

    /** Divide en dos grabaciones en [atMs]. Con [replace], el original va a la papelera. */
    fun split(context: Context, r: Recording, atMs: Long, replace: Boolean, onDone: () -> Unit) {
        Jobs.run(str(R.string.splitting)) { progress ->
            val (a, b) = Edits.split(r.durationMs, atMs, r.markers.map { it.point() })
            val share = a.durationMs.toFloat() / r.durationMs.coerceAtLeast(1)
            write(context, r, a, str(R.string.part_title, r.title, 1)) { progress(it * share) } ?: return@run null
            write(context, r, b, str(R.string.part_title, r.title, 2)) { progress(share + it * (1 - share)) } ?: return@run null
            if (replace) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { Playback.closeIf(r.id) }
                Library.trash(listOf(r.id))
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) { onDone() }
            str(R.string.split_done)
        }
    }

    /** Onda de una grabación (guardada) o, si falta, calculada y guardada. */
    fun peaks(r: Recording): ByteArray {
        val f = Library.peaksOf(r.id)
        if (f.exists() && f.length() > 0) return f.readBytes()
        val bytes = runCatching { AudioDecoder.open(Library.fileOf(r)).use { d -> Transcoder.peaks(d) { true } } }.getOrDefault(ByteArray(0))
        runCatching { f.writeBytes(bytes) }
        return bytes
    }
}
