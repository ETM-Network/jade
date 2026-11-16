package network.etm.tts

import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An inaudible mark in every output, so the audio can be identified as machine-made (EU AI Act, Article 50).
 *
 * Spectral magnitudes between 1 and 8 kHz are scaled by 1 ± 7 % following a pseudo-random pattern derived from a public
 * key. Each pattern value lasts two STFT frames and the pattern repeats every 32 of these tiles. The pattern follows the
 * absolute sample position, so audio marked piece by piece carries one continuous mark. Same algorithm as the Python
 * package's `watermark.apply`, which also provides the detector.
 */
internal object Watermark {
    const val SCHEME = "etm-dsp v1"
    private const val MODEL = "Jade 1.1"
    private const val KEY = "ETM-Network / $MODEL"
    private const val SAMPLE_RATE = 44100
    private const val NFFT = 2048
    private const val HOP = 1024
    private const val TILE = 2
    private const val PERIOD = 32
    private const val STRENGTH = 0.07

    private val window = DoubleArray(NFFT) { 0.5 - 0.5 * cos(2 * PI * it / NFFT) }
    private val overlap = DoubleArray(HOP) { window[it] * window[it] + window[it + HOP] * window[it + HOP] }
    private val band = (0..NFFT / 2).filter { (it * SAMPLE_RATE.toDouble() / NFFT) in 1000.0..8000.0 }
    private val pattern: Array<DoubleArray> = pattern(KEY)
    private val fft = Fft(NFFT)

    /** How the mark is named in file metadata. */
    fun label() = "$SCHEME, key $MODEL"

    /** Marks mono audio at 44.1 kHz. [offset]: the position of its first sample in the stream it belongs to. */
    fun apply(audio: FloatArray, offset: Long): FloatArray {
        val n = audio.size
        if (n < NFFT) return audio.copyOf()
        val lead = (offset % HOP).toInt()
        val padded = DoubleArray(lead + HOP + n + NFFT)
        for (i in 0 until n) padded[lead + HOP + i] = audio[i].toDouble()
        val frames = (lead + n) / HOP + 2
        val first = (offset - lead) / HOP
        val added = DoubleArray((frames + 1) * HOP)
        val re = DoubleArray(NFFT)
        val im = DoubleArray(NFFT)
        for (j in 0 until frames) {
            for (t in 0 until NFFT) {
                re[t] = padded[j * HOP + t] * window[t]
                im[t] = 0.0
            }
            fft.transform(re, im, inverse = false)
            val gains = pattern[(((first + j) / TILE) % PERIOD).toInt()]
            // Δ = X · strength · pattern on the band bins, zero elsewhere; irfft keeps the bins' Hermitian mirror
            val dRe = DoubleArray(NFFT)
            val dIm = DoubleArray(NFFT)
            for ((b, k) in band.withIndex()) {
                val g = STRENGTH * gains[b]
                dRe[k] = re[k] * g
                dIm[k] = im[k] * g
                dRe[NFFT - k] = dRe[k]
                dIm[NFFT - k] = -dIm[k]
            }
            fft.transform(dRe, dIm, inverse = true)
            for (t in 0 until NFFT) added[j * HOP + t] += dRe[t] / NFFT * window[t]
        }
        return FloatArray(n) { i -> (audio[i].toDouble() + added[HOP + lead + i] / overlap[i % HOP]).toFloat() }
    }

    /** ±1 per tile and band bin: the bits of SHA-256("<key>/0"), SHA-256("<key>/1"), ... read most significant first. */
    private fun pattern(key: String): Array<DoubleArray> {
        val count = PERIOD * band.size
        val sha = MessageDigest.getInstance("SHA-256")
        val bytes = (0 until (count + 255) / 256).flatMap { sha.digest("$key/$it".toByteArray()).toList() }
        return Array(PERIOD) { p ->
            DoubleArray(band.size) { b ->
                val bit = p * band.size + b
                if ((bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1 == 1) 1.0 else -1.0
            }
        }
    }
}

/** In-place iterative radix-2 complex FFT of a fixed power-of-two size (no 1/n scaling on the inverse). */
internal class Fft(private val n: Int) {
    private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2 * PI * it / n) }
    private val bits = Integer.numberOfTrailingZeros(n)

    init {
        require(n > 1 && n and (n - 1) == 0) { "size must be a power of two" }
    }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = Integer.reverse(i) ushr (32 - bits)
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            for (start in 0 until n step size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = sign * sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
            }
            size *= 2
        }
    }
}
