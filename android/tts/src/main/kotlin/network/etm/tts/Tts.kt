package network.etm.tts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/** Which matrix kernels ONNX Runtime uses for the model's 4-bit layers. */
enum class Kernels {
    /** ACCURATE for 4-bit and 3-bit models, FAST otherwise. */
    AUTO,

    /** The arithmetic the quantized models were validated with. About 20 % slower on ARM. */
    ACCURATE,

    /** ONNX Runtime's fastest ARM kernels (KleidiAI), with coarser rounding of activations. */
    FAST,
}

/**
 * Text to speech with a downloaded model folder (an `onnx/raw` or `onnx/quants/<variant>` folder of the model repo).
 * Thread-safe; close it to free the model.
 */
class Tts private constructor(private val pipeline: Pipeline) : AutoCloseable {
    private val config = pipeline.config

    /** The model's voices, by name. */
    val voices: List<String> get() = config.voices

    /** The model's languages, as ISO 639-1 codes. */
    val languages: List<String> get() = config.languages

    /** Samples per second of the audio. */
    val sampleRate: Int get() = config.sampleRate

    /** The model's recommended number of steps. */
    val defaultSteps: Int get() = config.stepsDefault

    /** The voice used when none is given. */
    val defaultVoice: String get() = if (DEFAULT_VOICE in voices) DEFAULT_VOICE else voices.first()

    /**
     * Speaks [text] and returns the whole recording.
     *
     * @param voice one of [voices]
     * @param lang one of [languages]; the text should be in that language
     * @param steps default from the model (12); 2 to 16 are useful; more steps are slower and steadier
     * @param speed speaking rate, 1 is normal
     * @param seed the same seed gives the same audio
     */
    fun speak(text: String, voice: String = defaultVoice, lang: String = "en", steps: Int = defaultSteps,
              speed: Float = 1f, seed: Long = 0): Speech {
        val chunks = ArrayList<FloatArray>()
        forEachChunk(prepare(text, voice, lang, speed, seed), steps, speed) { chunks.add(it) }
        val samples = FloatArray(chunks.sumOf { it.size })
        var at = 0
        for (c in chunks) {
            c.copyInto(samples, at)
            at += c.size
        }
        return Speech(samples, sampleRate, voice, lang, Label(voice, lang, steps, config.variantPath))
    }

    /**
     * Speaks [text] piece by piece (up to about 20 s each), so playback can start after the first piece. The chunks are mono float samples at [sampleRate]; concatenated they equal [speak]'s samples.
     */
    fun stream(text: String, voice: String = defaultVoice, lang: String = "en", steps: Int = defaultSteps,
               speed: Float = 1f, seed: Long = 0): Flow<FloatArray> = flow {
        forEachChunk(prepare(text, voice, lang, speed, seed), steps, speed) { emit(it) }
    }.flowOn(Dispatchers.Default)

    private fun prepare(text: String, voice: String, lang: String, speed: Float, seed: Long): List<Piece> {
        val speaker = voices.indexOf(voice)
        require(speaker >= 0) { "unknown voice '$voice'; this model has $voices" }
        require(lang in languages) { "unsupported language '$lang'; this model speaks $languages" }
        require(speed > 0f) { "speed must be positive" }
        return pipeline.pieces.build(text, speaker, lang, speed.toDouble(), seed)
    }

    /**
     * Renders pieces in batches of 1, 2, 4, then 8, so the first audio is ready quickly and each batch is done before
     * the audio before it has played. Each chunk is a piece's audio, watermarked at its position in the stream, then
     * its pause.
     */
    private inline fun forEachChunk(pieces: List<Piece>, steps: Int, speed: Float, action: (FloatArray) -> Unit) {
        require(steps >= 1) { "steps must be at least 1" }
        var offset = 0L
        var start = 0
        var size = 1
        while (start < pieces.size) {
            val group = pieces.subList(start, minOf(start + size, pieces.size))
            val audio = synchronized(pipeline) { pipeline.render(group, steps, speed.toDouble()) }
            for ((piece, samples) in group.zip(audio)) {
                val marked = Watermark.apply(samples, offset)
                val chunk = marked.copyOf(marked.size + Math.round(piece.pause * sampleRate).toInt())
                offset += chunk.size
                action(chunk)
            }
            start += size
            size = minOf(2 * size, MAX_BATCH)
        }
    }

    override fun close() = synchronized(pipeline) { pipeline.close() }

    companion object {
        private const val DEFAULT_VOICE = "Juliette"
        private const val MAX_BATCH = 8

        /**
         * Loads a model folder (config.json, text.onnx, step.onnx, vocoder.onnx). Takes a few seconds; call it off
         * the main thread.
         *
         * @param threads CPU threads for inference; 4 suits most phones
         */
        @JvmStatic
        @JvmOverloads
        fun load(modelDir: File, threads: Int = 4, kernels: Kernels = Kernels.AUTO): Tts {
            val config = ModelConfig.read(modelDir)
            val accurate = when (kernels) {
                Kernels.ACCURATE -> true
                Kernels.FAST -> false
                Kernels.AUTO -> config.variant in FOUR_BIT_VARIANTS
            }
            // KleidiAI only changes the arithmetic of 4-bit (and 3-bit in 4-bit containers) matrix products.
            val session = if (accurate) mapOf("mlas.disable_kleidiai" to "1") else emptyMap()
            return Tts(Pipeline(modelDir, config, threads, session))
        }

        private val FOUR_BIT_VARIANTS = setOf("q4", "q3")
    }
}
