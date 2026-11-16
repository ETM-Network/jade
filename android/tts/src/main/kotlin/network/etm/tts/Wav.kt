package network.etm.tts

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono WAV files; the label goes where other tools look for it: LIST/INFO/ICMT and an XMP _PMX chunk. */
internal object Wav {
    /** Clipped to [-1, 1], scaled by 32767, rounded half to even. */
    fun pcm16(samples: FloatArray): ShortArray =
        ShortArray(samples.size) { i -> Math.rint((samples[i].coerceIn(-1f, 1f) * 32767f).toDouble()).toInt().toShort() }

    fun write(file: File, samples: FloatArray, sampleRate: Int, label: Label?) {
        val body = ByteArrayOutputStream()
        body.write("WAVE".toByteArray())
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(sampleRate).putInt(2 * sampleRate).putShort(2).putShort(16).array()
        body.write(chunk("fmt ", fmt))
        if (label != null) {
            body.write(chunk("LIST", "INFO".toByteArray() + chunk("ICMT", label.comment().toByteArray() + 0.toByte())))
            body.write(chunk("_PMX", label.xmp().toByteArray()))
        }
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm16(samples)) pcm.putShort(s)
        body.write(chunk("data", pcm.array()))
        val riff = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(body.size()).array()
        file.writeBytes(riff + body.toByteArray())
    }

    /** A RIFF chunk: name, little-endian size, payload, and a pad byte when the size is odd. */
    private fun chunk(name: String, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + payload.size + payload.size % 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put(name.toByteArray(Charsets.US_ASCII)).putInt(payload.size).put(payload)
        return b.array()
    }
}
