// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AF
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import dev.liquidrecorder.R
import dev.liquidrecorder.core.audio.AudioFormat
import dev.liquidrecorder.core.audio.EncoderConfig
import dev.liquidrecorder.core.audio.FlacWriter
import dev.liquidrecorder.core.audio.PcmConverter
import dev.liquidrecorder.core.audio.PcmSink
import dev.liquidrecorder.core.audio.WavWriter
import dev.liquidrecorder.core.audio.downmix
import dev.liquidrecorder.core.dsp.Levels
import dev.liquidrecorder.core.dsp.PeakCollector
import dev.liquidrecorder.core.dsp.Spectrogram
import dev.liquidrecorder.core.text.Names
import dev.liquidrecorder.data.AppSettings
import dev.liquidrecorder.data.Library
import dev.liquidrecorder.data.Marker
import dev.liquidrecorder.data.Recording
import dev.liquidrecorder.util.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class RecStatus { IDLE, RECORDING, PAUSED, SAVING }

data class RecState(
    val status: RecStatus = RecStatus.IDLE,
    val recordingId: Long = 0,
    val title: String = "",
    val elapsedMs: Long = 0,
    val markers: List<Marker> = emptyList(),
    val config: EncoderConfig? = null,
    val deviceName: String = "",
    /** Otra app (una llamada) tomó el micrófono: se está grabando silencio. */
    val silenced: Boolean = false,
    /** La última grabación terminada, para ofrecer renombrarla. */
    val lastSaved: Long = 0,
    val error: String? = null,
) {
    val active: Boolean get() = status == RecStatus.RECORDING || status == RecStatus.PAUSED
}

/**
 * Datos en vivo para dibujar a 60–120 Hz sin pasar por el estado de Compose: el hilo de audio los
 * escribe y la pantalla los lee en cada cuadro cuando [version] cambió.
 */
class LiveMeter(val rows: Int, val columns: Int) {
    val version = AtomicLong()
    @Volatile var peakDb = Levels.SILENCE_DB
    @Volatile var rmsDb = Levels.SILENCE_DB
    @Volatile var clipUntil = 0L
    /** Columnas del espectrograma en anillo; [head] es la cantidad total escrita. */
    val spectrum = Array(columns) { FloatArray(rows) }
    @Volatile var head = 0L
    @Volatile var peaks: PeakCollector? = null
    /** Milisegundos de audio escritos y cuándo (System.nanoTime), para interpolar entre bloques. */
    @Volatile var audioMs = 0L
    @Volatile var stampNanos = 0L
    /** Momento (ms de audio) de la última columna del espectrograma. */
    @Volatile var lastColumnMs = 0L
    @Volatile var running = false

    /** Posición estimada ahora mismo: avanza suave entre bloque y bloque (como mucho 60 ms). */
    fun nowMs(): Long {
        if (!running) return audioMs
        val ahead = ((System.nanoTime() - stampNanos) / 1_000_000L).coerceIn(0, 60)
        return audioMs + ahead
    }

    fun reset() {
        peakDb = Levels.SILENCE_DB
        rmsDb = Levels.SILENCE_DB
        clipUntil = 0
        head = 0
        spectrum.forEach { it.fill(0f) }
        peaks = null
        audioMs = 0
        lastColumnMs = 0
        running = false
        version.incrementAndGet()
    }
}

/**
 * La grabación en curso. Vive en el proceso y la maneja [dev.liquidrecorder.service.RecordingService],
 * que la mantiene viva con la pantalla apagada. Lee el micrófono en coma flotante, aplica la
 * ganancia, escribe al archivo y alimenta el medidor, la onda y el espectrograma.
 */
object Recorder {
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state.asStateFlow()
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    /** Solo el tiempo, para que la pantalla no se recomponga entera 10 veces por segundo. */
    val elapsed: StateFlow<Long> = kotlinx.coroutines.flow.MutableStateFlow(0L).also { flow ->
        scope.launch { _state.collect { flow.value = it.elapsedMs } }
    }
    /** 240 columnas cada 30 ms: el espectrograma muestra unos 7 s. */
    val live = LiveMeter(rows = 96, columns = 240)
    const val COLUMN_MS = 30L

    @Volatile private var thread: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var paused = false
    private var nextMarkerId = 1L
    private var listener: ((RecState) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    /** Para que el servicio actualice la notificación (se llama en el hilo principal). */
    fun setListener(l: ((RecState) -> Unit)?) {
        listener = l
    }

    private fun set(f: (RecState) -> RecState) {
        _state.update(f)
        val s = _state.value
        main.post { listener?.invoke(s) }
    }

    /** Fuentes de Android que se ofrecen, en el orden de los ajustes. */
    fun source(index: Int): Int = when (index) {
        1 -> MediaRecorder.AudioSource.UNPROCESSED
        2 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        3 -> MediaRecorder.AudioSource.CAMCORDER
        else -> MediaRecorder.AudioSource.MIC
    }

    fun unprocessedSupported(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java).getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    /** Micrófonos conectados que se pueden elegir. */
    fun inputs(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter {
                it.type in setOf(
                    AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                )
            }
            .distinctBy { it.type to it.productName.toString() }

    fun deviceLabel(context: Context, d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> context.getString(R.string.mic_builtin)
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> context.getString(R.string.mic_wired)
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> context.getString(R.string.mic_bluetooth, d.productName)
        else -> context.getString(R.string.mic_usb, d.productName)
    }

    /** Espacio libre en la carpeta de grabaciones. */
    fun freeBytes(): Long = Library.dir.usableSpace

    const val MIN_FREE_BYTES = 50L * 1024 * 1024
    private const val STOP_FREE_BYTES = 20L * 1024 * 1024

    /** Empieza a grabar. Lo llama el servicio, ya en primer plano. Devuelve un error o null. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context, settings: AppSettings): String? {
        if (thread != null) return null
        if (freeBytes() < MIN_FREE_BYTES) return context.getString(R.string.error_no_space)
        val cfg = settings.encoder
        val am = context.getSystemService(AudioManager::class.java)
        val channelMask = if (cfg.channels == 2) AF.CHANNEL_IN_STEREO else AF.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(cfg.sampleRate, channelMask, AF.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) return context.getString(R.string.error_mic)

        val device = inputs(context).firstOrNull { it.id == settings.inputDevice }
        val bluetooth = device?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device?.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        if (bluetooth) runCatching { am.setCommunicationDevice(device!!) }
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(if (bluetooth) MediaRecorder.AudioSource.VOICE_COMMUNICATION else source(settings.audioSource))
                .setAudioFormat(AF.Builder().setEncoding(AF.ENCODING_PCM_FLOAT).setSampleRate(cfg.sampleRate).setChannelMask(channelMask).build())
                .setBufferSizeInBytes(maxOf(minBuf * 4, cfg.sampleRate * cfg.channels * 4 / 2))
                .build()
        } catch (e: Exception) {
            if (bluetooth) am.clearCommunicationDevice()
            return context.getString(R.string.error_mic)
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            if (bluetooth) am.clearCommunicationDevice()
            return context.getString(R.string.error_mic)
        }
        if (device != null) record.preferredDevice = device

        val id = Library.newId()
        val title = Names.next(context.getString(R.string.recording_base), Library.store.value.recordings.map { it.title })
        val file = File(Library.dir, "$id.${cfg.format.extension}")
        val sink: PcmSink = try {
            openSink(file, cfg)
        } catch (e: Exception) {
            record.release()
            return context.getString(R.string.error_mic)
        }
        Library.add(
            Recording(
                id = id, file = file.name, title = title, createdAt = System.currentTimeMillis(),
                format = cfg.format.id, sampleRate = cfg.sampleRate, channels = cfg.channels,
                bitsPerSample = cfg.bitsPerSample, bitrate = cfg.bitrate, inProgress = true,
            ),
        )
        nextMarkerId = 1
        stopRequested = false
        paused = false
        live.reset()
        val deviceName = device?.let { deviceLabel(context, it) } ?: context.getString(R.string.mic_builtin)
        set { RecState(RecStatus.RECORDING, id, title, 0, emptyList(), cfg, deviceName, lastSaved = it.lastSaved) }

        // Si otra app (una llamada) toma el micrófono, Android nos manda silencio: se avisa.
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId } ?: return
                if (mine.isClientSilenced != _state.value.silenced) set { it.copy(silenced = mine.isClientSilenced) }
            }
        }
        am.registerAudioRecordingCallback(callback, main)

        val gain = PcmConverter.dbToGain(settings.gainDb.toFloat())
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                loop(record, sink, cfg, gain, id, file)
            } finally {
                record.release()
                am.unregisterAudioRecordingCallback(callback)
                if (bluetooth) am.clearCommunicationDevice()
            }
        }, "recorder").apply { start() }
        return null
    }

    private fun loop(
        record: AudioRecord,
        sink: PcmSink,
        cfg: EncoderConfig,
        gain: Float,
        id: Long,
        file: File,
    ) {
        val chunkFrames = cfg.sampleRate / 50 // 20 ms
        val buf = FloatArray(chunkFrames * cfg.channels)
        val ints = IntArray(buf.size)
        val mono = FloatArray(chunkFrames)
        val converter = PcmConverter(cfg.pcmBits)
        val spectrogram = Spectrogram(cfg.sampleRate, rows = live.rows)
        val peaks = PeakCollector(cfg.sampleRate)
        live.peaks = peaks
        var frames = 0L
        var lastUi = 0L
        var lastCheckpoint = SystemClock.elapsedRealtime()
        var failed: String? = null
        record.startRecording()
        var recording = true
        while (!stopRequested) {
            if (paused) {
                live.running = false
                if (recording) { record.stop(); recording = false }
                live.peakDb = Levels.SILENCE_DB
                live.rmsDb = Levels.SILENCE_DB
                live.version.incrementAndGet()
                Thread.sleep(40)
                continue
            }
            if (!recording) { record.startRecording(); recording = true }
            val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
            if (n < 0) {
                failed = str(R.string.error_mic_lost)
                break
            }
            if (n == 0) continue
            // Se graba tal cual (solo con la ganancia): la limpieza de ruido se hace después, sobre
            // una copia, y el original queda intacto.
            val f = n / cfg.channels
            val data = buf
            if (gain != 1f) for (i in 0 until n) buf[i] *= gain
            converter.convert(data, f * cfg.channels, 1f, ints)
            try {
                sink.write(ints, f)
            } catch (e: Exception) {
                failed = str(R.string.error_write)
                break
            }
            frames += f
            // Medidor, onda y espectrograma sobre lo que se graba (ya procesado).
            downmix(data, f, cfg.channels, mono)
            val level = Levels.measure(mono, f)
            live.peakDb = level.peakDb
            live.rmsDb = level.rmsDb
            if (converter.takeClipped() > 0) live.clipUntil = SystemClock.uptimeMillis() + 1500
            synchronized(peaks) { peaks.push(mono, f) }
            val nowMs = frames * 1000 / cfg.sampleRate
            // Una columna cada 30 ms: lo bastante lento para leerlo.
            if (spectrogram.push(mono, f) && nowMs - live.lastColumnMs >= COLUMN_MS) {
                val col = live.spectrum[(live.head % live.columns).toInt()]
                spectrogram.column(col)
                live.lastColumnMs = nowMs
                live.head = live.head + 1
            }
            live.audioMs = nowMs
            live.stampNanos = System.nanoTime()
            live.running = true
            live.version.incrementAndGet()

            val now = SystemClock.elapsedRealtime()
            val elapsed = frames * 1000 / cfg.sampleRate
            if (now - lastUi >= 100) {
                lastUi = now
                _state.update { it.copy(elapsedMs = elapsed) }
            }
            if (now - lastCheckpoint >= 5_000) {
                lastCheckpoint = now
                checkpoint(sink, id, elapsed, peaks)
                if (freeBytes() < STOP_FREE_BYTES) {
                    failed = str(R.string.error_space_stopped)
                    break
                }
            }
        }
        live.running = false
        live.peakDb = Levels.SILENCE_DB
        live.rmsDb = Levels.SILENCE_DB
        live.version.incrementAndGet()
        if (recording) runCatching { record.stop() }
        set { it.copy(status = RecStatus.SAVING) }
        val ok = runCatching { sink.finish() }.isSuccess
        val duration = frames * 1000 / cfg.sampleRate
        val peaksBytes = synchronized(peaks) { peaks.toByteArray() }
        runCatching { Library.peaksOf(id).writeBytes(peaksBytes) }
        File(Library.dir, "$id.state").delete()
        if (!ok || duration < 300) {
            // Nada útil (o se rompió el archivo): no se guarda.
            Library.deleteForever(listOf(id))
        } else {
            Library.update(id) { it.copy(inProgress = false, durationMs = duration, sizeBytes = file.length(), markers = _state.value.markers) }
        }
        thread = null
        set {
            it.copy(
                status = RecStatus.IDLE, elapsedMs = 0, silenced = false,
                lastSaved = if (ok && duration >= 300) id else it.lastSaved,
                error = failed ?: if (!ok) str(R.string.error_write) else null,
            )
        }
    }

    /** Punto de recuperación: si el proceso muere, al abrir la app se rescata hasta acá. */
    private fun checkpoint(sink: PcmSink, id: Long, elapsed: Long, peaks: PeakCollector) {
        runCatching {
            sink.checkpoint()
            val safe = (sink as? FlacWriter)?.safeLength ?: -1
            File(Library.dir, "$id.state").writeText("$safe")
            val bytes = synchronized(peaks) { peaks.toByteArray() }
            Library.peaksOf(id).writeBytes(bytes)
            Library.update(id) { it.copy(durationMs = elapsed, markers = _state.value.markers) }
        }
    }

    fun pause() {
        if (_state.value.status != RecStatus.RECORDING) return
        paused = true
        set { it.copy(status = RecStatus.PAUSED) }
    }

    fun resume() {
        if (_state.value.status != RecStatus.PAUSED) return
        paused = false
        set { it.copy(status = RecStatus.RECORDING) }
    }

    fun stop() {
        if (thread == null) return
        paused = false
        stopRequested = true
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearLastSaved() = _state.update { it.copy(lastSaved = 0) }

    /** Marcador en el momento actual. Devuelve el marcador para poder ponerle nombre enseguida. */
    fun addMarker(): Marker? {
        val s = _state.value
        if (!s.active) return null
        val m = Marker(nextMarkerId++, s.elapsedMs, "")
        set { it.copy(markers = (it.markers + m).sortedBy { x -> x.atMs }) }
        return m
    }

    fun renameMarker(id: Long, name: String) = set { s -> s.copy(markers = s.markers.map { if (it.id == id) it.copy(name = name.trim()) else it }) }

    fun deleteMarker(id: Long) = set { s -> s.copy(markers = s.markers.filter { it.id != id }) }

    fun renameCurrent(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        val id = _state.value.recordingId
        set { it.copy(title = t) }
        Library.rename(id, t)
    }

    /**
     * Al abrir la app: grabaciones que quedaron a medias porque el proceso murió (sin batería, por
     * ejemplo). WAV y FLAC se rescatan hasta el último punto de control; AAC y Opus no se pueden
     * (su contenedor se escribe al final) y se descartan. Devuelve cuántas se recuperaron.
     */
    fun recover(): Pair<Int, Int> {
        if (thread != null) return 0 to 0
        var ok = 0
        var lost = 0
        for (r in Library.store.value.recordings.filter { it.inProgress }) {
            val file = Library.fileOf(r)
            val stateFile = File(Library.dir, "${r.id}.state")
            val rescued = runCatching {
                when (r.audioFormat) {
                    AudioFormat.WAV -> { WavWriter.repair(file, r.sampleRate, r.channels, r.bitsPerSample); true }
                    AudioFormat.FLAC -> {
                        val safe = stateFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1
                        if (safe > 0) { FlacWriter.repair(file, safe); true } else false
                    }
                    else -> false
                }
            }.getOrDefault(false) && file.exists() && r.durationMs > 0
            stateFile.delete()
            if (rescued) {
                val wavMs = if (r.audioFormat == AudioFormat.WAV) {
                    (file.length() - WavWriter.HEADER_SIZE) * 1000 / (r.sampleRate.toLong() * r.channels * (r.bitsPerSample / 8))
                } else r.durationMs
                Library.update(r.id) { it.copy(inProgress = false, sizeBytes = file.length(), durationMs = wavMs) }
                ok++
            } else {
                Library.deleteForever(listOf(r.id))
                lost++
            }
        }
        return ok to lost
    }
}
