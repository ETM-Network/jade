package network.etm.tts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The pipeline against reference renders of the Python implementation (-PreferenceDir=<folder of variant/case
 * folders>, each with meta.json, the noise and the audio, and for single pieces the latents); skipped without them.
 * Cases marked "batches" are rendered in the same growing batches (1, 2, 4, 8) as Tts does, so batch composition
 * matches on both sides; quantized matrix kernels are not batch-invariant.
 */
class ReferenceTest {
    private val root = System.getProperty("etm.tts.referenceDir")?.let(::File)

    private fun floats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    private fun relError(got: FloatArray, want: FloatArray) =
        sqrt(got.indices.sumOf { ((got[it] - want[it]).toDouble()).let { d -> d * d } } / want.sumOf { (it * it).toDouble() })

    private fun snr(got: FloatArray, want: FloatArray): Double {
        val err = got.indices.sumOf { ((got[it] - want[it]).toDouble()).let { d -> d * d } }
        return 10 * log10(want.sumOf { (it * it).toDouble() } / maxOf(err, 1e-30))
    }

    @Test
    fun matchesReferenceRenders() {
        assumeTrue("no -PreferenceDir", root != null)
        val cases = root!!.listFiles()!!.filter { it.isDirectory }.flatMap { v -> v.listFiles()!!.filter { it.isDirectory } }
        assertTrue("no cases in $root", cases.isNotEmpty())
        for (case in cases.sortedBy { it.path }) {
            val meta = JSONObject(File(case, "meta.json").readText())
            val dir = File(meta.getString("model"))
            val config = ModelConfig.read(dir)
            val accurate = config.variant in setOf("q4", "q3")
            val name = "${case.parentFile.name}/${case.name}"
            Pipeline(dir, config, 4, if (accurate) mapOf("mlas.disable_kleidiai" to "1") else emptyMap()).use { p ->
                val pieces = p.pieces.build(meta.getString("text"), config.voices.indexOf(meta.getString("voice")),
                                            meta.getString("lang"), 1.0)
                val want = meta.getJSONArray("pieces")
                assertEquals("$name: pieces", want.length(), pieces.size)
                for ((i, piece) in pieces.withIndex()) {
                    val ids = want.getJSONObject(i).getJSONArray("ids").let { a -> List(a.length()) { a.getLong(it) } }
                    assertEquals("$name: ids of piece $i", ids, piece.ids.toList())
                }
                val sizes = if (meta.optBoolean("batches")) generateSequence(1) { minOf(2 * it, 8) } else generateSequence { 1 }
                var start = 0
                for (size in sizes) {
                    if (start >= pieces.size) break
                    val idx = start until minOf(start + size, pieces.size)
                    p.plan(idx.map { pieces[it] }, 1.0).use { plan ->
                        assertEquals("$name: frames", idx.map { want.getJSONObject(it).getInt("frames") }, plan.frames)
                        val lat = p.think(plan, meta.getInt("steps"), idx.map { floats(File(case, "noise_p$it.bin")) })
                        val audio = p.decode(lat, plan.frames)
                        for ((j, i) in idx.withIndex()) {
                            val s = snr(audio[j], floats(File(case, "audio_p$i.bin")))
                            val latFile = File(case, "lat_p$i.bin")
                            val rel = if (latFile.exists()) relError(lat[j], floats(latFile)) else null
                            println("$name piece $i (batch of ${idx.count()}): audio SNR %.1f dB%s".format(
                                s, rel?.let { ", latent rel %.2e".format(it) } ?: ""))
                            assertTrue("$name piece $i: audio SNR $s", s > 60)
                            if (rel != null) assertTrue("$name piece $i: latent rel $rel", rel < 1e-4)
                        }
                    }
                    start += size
                }
            }
        }
    }
}
