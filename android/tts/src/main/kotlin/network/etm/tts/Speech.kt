package network.etm.tts

import java.io.File

/** A finished recording: mono float samples in [-1, 1] at [sampleRate]. */
class Speech internal constructor(
    val samples: FloatArray,
    val sampleRate: Int,
    val voice: String,
    val language: String,
    internal val label: Label,
) {
    /** Length in seconds. */
    val durationSeconds: Double get() = samples.size.toDouble() / sampleRate

    /** The samples as 16-bit PCM. */
    fun toPcm16(): ShortArray = Wav.pcm16(samples)

    /** Writes a 16-bit mono WAV file labelled as AI-generated speech (model, settings, voice, language). */
    fun saveWav(file: File) = Wav.write(file, samples, sampleRate, label)
}
