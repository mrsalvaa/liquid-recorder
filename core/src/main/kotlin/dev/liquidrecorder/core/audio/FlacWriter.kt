// SPDX-License-Identifier: GPL-3.0-or-later
package dev.liquidrecorder.core.audio

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Codificador FLAC propio, sin dependencias: Android no trae un contenedor FLAC para su codificador,
 * y esto es poco código. Usa predictores fijos (órdenes 0 a 4), Rice particionado y, en estéreo,
 * elige entre canales independientes, izquierda/lado, derecha/lado o medio/lado en cada bloque.
 *
 * El resultado es FLAC estándar (verificado con `flac -t`, que también comprueba el MD5).
 * Comprime algo menos que `flac -8` (que usa LPC), a cambio de ser liviano para grabar en vivo.
 */
class FlacWriter(
    file: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bits: Int,
    private val blockSize: Int = 4096,
) : PcmSink {
    private val raf = RandomAccessFile(file, "rw")
    private val md5 = MessageDigest.getInstance("MD5")
    private val block = Array(channels) { IntArray(blockSize) }
    private var fill = 0
    private var frameNumber = 0L
    private var totalSamples = 0L
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private val encoder = FlacFrameEncoder(sampleRate, channels, bits)
    private var md5Bytes = ByteArray(0)

    init {
        require(channels in 1..2) { "FLAC: 1 o 2 canales" }
        require(bits == 16 || bits == 24) { "FLAC: 16 o 24 bits" }
        raf.setLength(0)
        raf.write(streamInfo(0, ByteArray(16), 0, 0))
    }

    override fun write(samples: IntArray, frames: Int) {
        var f = 0
        while (f < frames) {
            val take = minOf(blockSize - fill, frames - f)
            for (i in 0 until take) {
                for (c in 0 until channels) block[c][fill + i] = samples[(f + i) * channels + c]
            }
            fill += take
            f += take
            if (fill == blockSize) flushBlock()
        }
    }

    private fun flushBlock() {
        if (fill == 0) return
        updateMd5(fill)
        val frame = encoder.encode(block, fill, frameNumber++)
        raf.write(frame)
        minFrame = minOf(minFrame, frame.size)
        maxFrame = maxOf(maxFrame, frame.size)
        totalSamples += fill
        fill = 0
    }

    private fun updateMd5(n: Int) {
        val bps = bits / 8
        val size = n * channels * bps
        if (md5Bytes.size < size) md5Bytes = ByteArray(size)
        var o = 0
        for (i in 0 until n) {
            for (c in 0 until channels) {
                val v = block[c][i]
                md5Bytes[o++] = v.toByte()
                md5Bytes[o++] = (v shr 8).toByte()
                if (bps == 3) md5Bytes[o++] = (v shr 16).toByte()
            }
        }
        md5.update(md5Bytes, 0, size)
    }

    /** Bytes hasta el último cuadro completo: si se corta la grabación, se recorta ahí. */
    val safeLength: Long get() = raf.filePointer

    /** Muestras (por canal) que ya están escritas en cuadros completos. */
    val writtenSamples: Long get() = totalSamples

    /** Deja el total de muestras al día (con MD5 en cero, "desconocido") y lo baja al disco. */
    override fun checkpoint() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(streamInfo(totalSamples, ByteArray(16), 0, 0))
        raf.seek(pos)
        raf.fd.sync()
    }

    override fun finish() {
        flushBlock()
        raf.seek(0)
        raf.write(streamInfo(totalSamples, md5.digest(), if (maxFrame == 0) 0 else minFrame, maxFrame))
        raf.close()
    }

    private fun streamInfo(total: Long, digest: ByteArray, minFrame: Int, maxFrame: Int): ByteArray {
        val w = BitWriter(64)
        w.bits('f'.code.toLong(), 8); w.bits('L'.code.toLong(), 8); w.bits('a'.code.toLong(), 8); w.bits('C'.code.toLong(), 8)
        w.bits(1, 1) // último bloque de metadatos
        w.bits(0, 7) // STREAMINFO
        w.bits(34, 24)
        w.bits(blockSize.toLong(), 16)
        w.bits(blockSize.toLong(), 16)
        w.bits(minFrame.toLong(), 24)
        w.bits(maxFrame.toLong(), 24)
        w.bits(sampleRate.toLong(), 20)
        w.bits((channels - 1).toLong(), 3)
        w.bits((bits - 1).toLong(), 5)
        w.bits(total, 36)
        digest.forEach { w.bits(it.toLong() and 0xFF, 8) }
        return w.toByteArray()
    }

    companion object {
        /** Largo del encabezado que escribe [FlacWriter] ("fLaC" + STREAMINFO). */
        const val HEADER_SIZE = 42

        /**
         * Recorta un FLAC que quedó a medias a [safeLength] (lo que se sabía completo en el último
         * punto de control). El encabezado ya tiene el total de ese momento.
         */
        fun repair(file: File, safeLength: Long) {
            RandomAccessFile(file, "rw").use { raf ->
                if (safeLength in HEADER_SIZE..raf.length()) raf.setLength(safeLength)
            }
        }
    }
}

/** Codifica un bloque de muestras en un cuadro FLAC completo (encabezado, subcuadros y CRC). */
internal class FlacFrameEncoder(private val sampleRate: Int, private val channels: Int, private val bits: Int) {
    private var side = IntArray(0)
    private var mid = IntArray(0)
    private val writer = BitWriter(1 shl 16)
    private var residual = IntArray(0)
    private var unsigned = IntArray(0)
    private var prefix = LongArray(0)

    fun encode(block: Array<IntArray>, n: Int, frameNumber: Long): ByteArray {
        val w = writer
        w.reset()
        var assignment = channels - 1
        val plans: List<Pair<IntArray, Plan>>
        if (channels == 2) {
            if (side.size < n) { side = IntArray(n); mid = IntArray(n) }
            val l = block[0]
            val r = block[1]
            for (i in 0 until n) {
                side[i] = l[i] - r[i]
                mid[i] = (l[i] + r[i]) shr 1
            }
            val pl = plan(l, n, bits)
            val pr = plan(r, n, bits)
            val ps = plan(side, n, bits + 1)
            val pm = plan(mid, n, bits)
            val options = listOf(
                Triple(1, pl.bits + pr.bits, listOf(l to pl, r to pr)),
                Triple(8, pl.bits + ps.bits, listOf(l to pl, side to ps)),
                Triple(9, ps.bits + pr.bits, listOf(side to ps, r to pr)),
                Triple(10, pm.bits + ps.bits, listOf(mid to pm, side to ps)),
            )
            val best = options.minBy { it.second }
            assignment = best.first
            plans = best.third
        } else {
            plans = listOf(block[0] to plan(block[0], n, bits))
        }

        // Encabezado del cuadro.
        w.bits(0x3FFE, 14)
        w.bits(0, 1)
        w.bits(0, 1) // tamaño de bloque fijo
        val sizeCode = blockSizeCode(n)
        w.bits(sizeCode.toLong(), 4)
        w.bits(sampleRateCode(sampleRate).toLong(), 4)
        w.bits(assignment.toLong(), 4)
        w.bits(sampleSizeCode(bits).toLong(), 3)
        w.bits(0, 1)
        utf8(w, frameNumber)
        if (sizeCode == 6) w.bits((n - 1).toLong(), 8)
        if (sizeCode == 7) w.bits((n - 1).toLong(), 16)
        w.bits(Crc.crc8(w.bytes(), 0, w.byteCount()).toLong(), 8)

        for ((samples, p) in plans) writeSubframe(w, samples, n, p)
        w.align()
        val crc = Crc.crc16(w.bytes(), 0, w.byteCount())
        w.bits(crc.toLong(), 16)
        return w.toByteArray()
    }

    /** Lo que se decidió para un subcuadro. type: 0 constante, 1 literal, 2 predictor fijo. */
    class Plan(val type: Int, val bps: Int, val order: Int, val partitionOrder: Int, val params: IntArray, val rice2: Boolean, val bits: Long)

    private fun plan(x: IntArray, n: Int, bps: Int): Plan {
        val first = x[0]
        var constant = true
        for (i in 1 until n) if (x[i] != first) { constant = false; break }
        if (constant) return Plan(0, bps, 0, 0, IntArray(0), false, 8L + bps)

        var best = Plan(1, bps, 0, 0, IntArray(0), false, 8L + n.toLong() * bps)
        if (residual.size < n) { residual = IntArray(n); unsigned = IntArray(n); prefix = LongArray(n + 1) }
        for (order in 0..minOf(4, n - 1)) {
            residuals(x, n, order)
            val p = bestPartition(n, order, bps)
            if (p != null && p.bits < best.bits) best = p
        }
        return best
    }

    private fun residuals(x: IntArray, n: Int, order: Int) {
        val r = residual
        when (order) {
            0 -> for (i in 0 until n) r[i] = x[i]
            1 -> for (i in 1 until n) r[i - 1] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) r[i - 2] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) r[i - 3] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) r[i - 4] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
        val count = n - order
        prefix[0] = 0
        for (i in 0 until count) {
            val v = r[i]
            val u = (v shl 1) xor (v shr 31)
            unsigned[i] = u
            prefix[i + 1] = prefix[i] + (u.toLong() and 0xFFFFFFFFL)
        }
    }

    private fun bestPartition(n: Int, order: Int, bps: Int): Plan? {
        var best: Plan? = null
        for (po in 0..8) {
            if (n % (1 shl po) != 0) break
            val ps = n shr po
            if (ps <= order) break
            val partitions = 1 shl po
            val params = IntArray(partitions)
            var total = 8L + order.toLong() * bps + 2 + 4
            var rice2 = false
            for (j in 0 until partitions) {
                val start = if (j == 0) 0 else j * ps - order
                val end = (j + 1) * ps - order
                val count = end - start
                val sum = prefix[end] - prefix[start]
                var k = 0
                if (count > 0 && sum > count) {
                    val mean = sum / count
                    k = 63 - java.lang.Long.numberOfLeadingZeros(mean)
                }
                // Probar k y sus vecinos con la estimación sum >> k.
                var bestK = k
                var bestBits = Long.MAX_VALUE
                for (cand in maxOf(0, k - 1)..minOf(30, k + 1)) {
                    val b = count.toLong() * (cand + 1) + (sum shr cand)
                    if (b < bestBits) { bestBits = b; bestK = cand }
                }
                params[j] = bestK
                if (bestK > 14) rice2 = true
                total += bestBits
            }
            total += partitions.toLong() * (if (rice2) 5 else 4)
            if (best == null || total < best.bits) best = Plan(2, bps, order, po, params, rice2, total)
        }
        return best
    }

    private fun writeSubframe(w: BitWriter, x: IntArray, n: Int, p: Plan) {
        w.bits(0, 1)
        when (p.type) {
            0 -> {
                w.bits(0, 6); w.bits(0, 1)
                w.signed(x[0], p.bps)
            }
            1 -> {
                w.bits(1, 6); w.bits(0, 1)
                for (i in 0 until n) w.signed(x[i], p.bps)
            }
            else -> {
                w.bits((8 + p.order).toLong(), 6); w.bits(0, 1)
                for (i in 0 until p.order) w.signed(x[i], p.bps)
                residuals(x, n, p.order)
                w.bits(if (p.rice2) 1 else 0, 2)
                w.bits(p.partitionOrder.toLong(), 4)
                val ps = n shr p.partitionOrder
                val paramBits = if (p.rice2) 5 else 4
                for (j in p.params.indices) {
                    val k = p.params[j]
                    w.bits(k.toLong(), paramBits)
                    val start = if (j == 0) 0 else j * ps - p.order
                    val end = (j + 1) * ps - p.order
                    for (i in start until end) w.rice(unsigned[i].toLong() and 0xFFFFFFFFL, k)
                }
            }
        }
    }

    private fun blockSizeCode(n: Int): Int = when (n) {
        192 -> 1
        576 -> 2
        1152 -> 3
        2304 -> 4
        4608 -> 5
        256 -> 8
        512 -> 9
        1024 -> 10
        2048 -> 11
        4096 -> 12
        8192 -> 13
        16384 -> 14
        32768 -> 15
        else -> if (n <= 256) 6 else 7
    }

    private fun sampleRateCode(rate: Int): Int = when (rate) {
        88_200 -> 1
        176_400 -> 2
        192_000 -> 3
        8_000 -> 4
        16_000 -> 5
        22_050 -> 6
        24_000 -> 7
        32_000 -> 8
        44_100 -> 9
        48_000 -> 10
        96_000 -> 11
        else -> 0 // el de STREAMINFO
    }

    private fun sampleSizeCode(bits: Int): Int = when (bits) {
        8 -> 1
        12 -> 2
        16 -> 4
        20 -> 5
        24 -> 6
        else -> 0
    }

    /** Número de cuadro con la codificación "UTF-8" extendida de FLAC. */
    private fun utf8(w: BitWriter, v: Long) {
        when {
            v < 0x80 -> w.bits(v, 8)
            v < 0x800 -> { w.bits(0xC0L or (v shr 6), 8); cont(w, v, 1) }
            v < 0x10000 -> { w.bits(0xE0L or (v shr 12), 8); cont(w, v, 2) }
            v < 0x200000 -> { w.bits(0xF0L or (v shr 18), 8); cont(w, v, 3) }
            v < 0x4000000 -> { w.bits(0xF8L or (v shr 24), 8); cont(w, v, 4) }
            v < 0x80000000L -> { w.bits(0xFCL or (v shr 30), 8); cont(w, v, 5) }
            else -> { w.bits(0xFE, 8); cont(w, v, 6) }
        }
    }

    private fun cont(w: BitWriter, v: Long, n: Int) {
        for (i in n - 1 downTo 0) w.bits(0x80L or ((v shr (6 * i)) and 0x3F), 8)
    }
}

/** Escritor de bits de mayor a menor peso. */
internal class BitWriter(initial: Int) {
    private var buf = ByteArray(initial)
    private var pos = 0
    private var acc = 0L
    private var accBits = 0

    fun reset() { pos = 0; acc = 0; accBits = 0 }

    fun bits(value: Long, n: Int) {
        if (n == 0) return
        if (n > 32) {
            bits(value ushr 32, n - 32)
            bits(value and 0xFFFFFFFFL, 32)
            return
        }
        acc = (acc shl n) or (value and ((1L shl n) - 1))
        accBits += n
        while (accBits >= 8) {
            accBits -= 8
            put((acc ushr accBits).toInt())
        }
        acc = acc and ((1L shl accBits) - 1)
    }

    fun signed(v: Int, n: Int) = bits(v.toLong(), n)

    /** Código de Rice: el cociente en unario (ceros y un 1) y luego los k bits bajos. */
    fun rice(u: Long, k: Int) {
        var q = u ushr k
        while (q >= 32) { bits(0, 32); q -= 32 }
        bits(1, q.toInt() + 1)
        bits(u, k)
    }

    fun align() { if (accBits > 0) bits(0, 8 - accBits) }

    private fun put(b: Int) {
        if (pos == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[pos++] = b.toByte()
    }

    fun bytes(): ByteArray = buf
    fun byteCount(): Int = pos
    fun toByteArray(): ByteArray = buf.copyOf(pos)
}

internal object Crc {
    private val table8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
        c and 0xFF
    }
    private val table16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
        c and 0xFFFF
    }

    fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) c = table8[(c xor b[i].toInt()) and 0xFF]
        return c
    }

    fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) c = ((c shl 8) xor table16[((c shr 8) xor b[i].toInt()) and 0xFF]) and 0xFFFF
        return c
    }
}
