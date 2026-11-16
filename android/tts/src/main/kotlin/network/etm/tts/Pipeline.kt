// The model's three ONNX graphs: text (encoder and duration), step (one jump of the acoustic model) and vocoder.
package network.etm.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

/** A batch's text encoding (kept open for the jumps), padding mask and each piece's planned length in frames. */
internal class Plan(val pieces: List<Piece>, val text: OrtSession.Result, val textMask: FloatArray, val width: Int,
                    val frames: List<Int>) : AutoCloseable {
    override fun close() = text.close()
}

internal class Pipeline(dir: File, val config: ModelConfig, threads: Int, sessionConfig: Map<String, String>) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val text: OrtSession
    private val step: OrtSession
    private val vocoder: OrtSession
    val pieces = PieceBuilder(config)

    init {
        val o = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // spinning threads would keep cores busy between the short graph calls
            addConfigEntry("session.intra_op.allow_spinning", "0")
            for ((k, v) in sessionConfig) addConfigEntry(k, v)
        }
        text = env.createSession(File(dir, "text.onnx").path, o)
        step = env.createSession(File(dir, "step.onnx").path, o)
        vocoder = env.createSession(File(dir, "vocoder.onnx").path, o)
    }

    private fun longs(values: LongArray, vararg shape: Long) = OnnxTensor.createTensor(env, LongBuffer.wrap(values), shape)
    private fun floats(values: FloatArray, vararg shape: Long) = OnnxTensor.createTensor(env, FloatBuffer.wrap(values), shape)

    /**
     * Encodes a batch (ids zero-padded to the longest) and plans each length: the predicted duration, kept within the
     * language's pace, over speed. [frames] overrides the planned lengths.
     */
    fun plan(group: List<Piece>, speed: Double, frames: List<Int>? = null): Plan {
        val b = group.size
        val width = group.maxOf { it.ids.size }
        val ids = LongArray(b * width)
        group.forEachIndexed { i, p -> p.ids.copyInto(ids, i * width) }
        val feeds = mapOf(
            "text_ids" to longs(ids, b.toLong(), width.toLong()),
            "speaker_id" to longs(LongArray(b) { group[it].speaker.toLong() }, b.toLong()),
            "language_id" to longs(LongArray(b) { group[it].language.toLong() }, b.toLong()),
            "dur_noise" to floats(FloatArray(b), b.toLong()))
        val result = try { text.run(feeds) } finally { feeds.values.forEach { it.close() } }
        val durations = (result.get("duration").get() as OnnxTensor).floatBuffer
        val fps = config.framesPerSecond
        val planned = frames ?: group.mapIndexed { i, p ->
            val (lo, hi) = config.pace(p.languageCode).first
            val n = p.ids.size
            val d = min(max(durations.get(i).toDouble(), n * lo * fps), n * hi * fps)
            // float32 division and round-half-even, as the reference runtime
            Math.rint((d.toFloat() / speed.toFloat()).toDouble()).toInt().coerceIn(1, config.maxFrames)
        }
        val textMask = FloatArray(b * width) { if (ids[it] != 0L) 1f else 0f }
        return Plan(group, result, textMask, width, planned)
    }

    /**
     * `steps` jumps from noise to each piece's latent frames (x += dt * u at t = k / steps), on the batch padded to
     * its longest piece. [noise]: per piece [frames * latentDim] values (default: Gaussian from the piece's seed).
     */
    fun think(plan: Plan, steps: Int, noise: List<FloatArray>? = null): List<FloatArray> {
        val b = plan.pieces.size
        val dim = config.latentDim
        val longest = plan.frames.max()
        val x = FloatArray(b * longest * dim)
        val latentMask = FloatArray(b * longest)
        for ((i, p) in plan.pieces.withIndex()) {
            val f = plan.frames[i]
            val z = noise?.get(i) ?: java.util.Random(p.seed xor 0x5EEDL).let { r -> FloatArray(f * dim) { r.nextGaussian().toFloat() } }
            require(z.size == f * dim) { "noise has ${z.size} values, need ${f * dim}" }
            z.copyInto(x, i * longest * dim)
            latentMask.fill(1f, i * longest, i * longest + f)
        }
        val k = plan.text.get("text_k").get() as OnnxTensor
        val v = plan.text.get("text_v").get() as OnnxTensor
        val dt = (1.0 / steps).toFloat()
        val fixed = mapOf(
            "text_mask" to floats(plan.textMask, b.toLong(), plan.width.toLong()),
            "latent_mask" to floats(latentMask, b.toLong(), longest.toLong()),
            "speaker_id" to longs(LongArray(b) { plan.pieces[it].speaker.toLong() }, b.toLong()),
            "language_id" to longs(LongArray(b) { plan.pieces[it].language.toLong() }, b.toLong()),
            "dt" to floats(FloatArray(b) { dt }, b.toLong()))
        try {
            for (s in 0 until steps) {
                val xT = floats(x, b.toLong(), longest.toLong(), dim.toLong())
                val tT = floats(FloatArray(b) { (s.toDouble() / steps).toFloat() }, b.toLong())
                try {
                    step.run(fixed + mapOf("x" to xT, "t" to tT, "text_k" to k, "text_v" to v)).use { r ->
                        val u = (r.get("u").get() as OnnxTensor).floatBuffer
                        for (i in x.indices) x[i] = x[i] + dt * u.get(i)
                    }
                } finally {
                    xT.close(); tT.close()
                }
            }
        } finally {
            fixed.values.forEach { it.close() }
        }
        return List(b) { i -> x.copyOfRange(i * longest * dim, (i * longest + plan.frames[i]) * dim) }
    }

    /** Latent frames to audio (frame_samples per frame), the batch zero-padded to its longest piece. */
    fun decode(latents: List<FloatArray>, frames: List<Int>): List<FloatArray> {
        val b = latents.size
        val dim = config.latentDim
        val longest = frames.max()
        val lat = FloatArray(b * longest * dim)
        latents.forEachIndexed { i, l -> l.copyInto(lat, i * longest * dim) }
        floats(lat, b.toLong(), longest.toLong(), dim.toLong()).use { t ->
            vocoder.run(mapOf("latent" to t)).use { r ->
                val audio = (r.get("audio").get() as OnnxTensor).floatBuffer
                val stride = longest * config.frameSamples
                return List(b) { i -> FloatArray(frames[i] * config.frameSamples) { audio.get(i * stride + it) } }
            }
        }
    }

    /** A batch of pieces end to end, in order. */
    fun render(group: List<Piece>, steps: Int, speed: Double): List<FloatArray> =
        plan(group, speed).use { p -> decode(think(p, steps), p.frames) }

    override fun close() {
        text.close(); step.close(); vocoder.close()
    }
}
