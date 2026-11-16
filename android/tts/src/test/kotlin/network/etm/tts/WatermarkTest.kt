package network.etm.tts

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * The watermark and the WAV label against the Python package's outputs (-PwatermarkReference=<folder with input.bin,
 * out_<offset>.bin, labelled.wav, cases.json>); skipped without them.
 */
class WatermarkTest {
    private val root = System.getProperty("etm.tts.watermarkReference")?.let(::File)

    private fun floats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    @Test
    fun applyMatchesPython() {
        assumeTrue("no -PwatermarkReference", root != null)
        val cases = JSONObject(File(root, "cases.json").readText())
        val input = floats(File(root, "input.bin"))
        val offsets = cases.getJSONArray("offsets")
        for (i in 0 until offsets.length()) {
            val offset = offsets.getLong(i)
            val want = floats(File(root, "out_$offset.bin"))
            val got = Watermark.apply(input, offset)
            var maxDiff = 0f
            var exact = 0
            for (k in want.indices) {
                val d = abs(got[k] - want[k])
                if (d == 0f) exact++
                maxDiff = maxOf(maxDiff, d)
            }
            println("offset $offset: max |diff| %.3g, %.4f %% of samples bit-equal".format(maxDiff, 100.0 * exact / want.size))
            assertTrue("offset $offset: max diff $maxDiff", maxDiff <= 1e-6f)
        }
        val short = input.copyOf(1000)
        assertArrayEquals(short, Watermark.apply(short, 5), 0f)
    }

    @Test
    fun labelledWavMatchesPython() {
        assumeTrue("no -PwatermarkReference", root != null)
        val cases = JSONObject(File(root, "cases.json").readText())
        val l = cases.getJSONObject("label")
        val label = Label(l.getString("voice"), l.getString("language"), l.getInt("steps"), l.getString("variant"))
        assertEquals(cases.getString("comment"), label.comment())
        assertEquals(cases.getString("xmp"), label.xmp())
        val out = File("build/test-output").apply { mkdirs() }
        val file = File(out, "labelled.wav")
        Wav.write(file, floats(File(root, "input.bin")).copyOf(44100), 44100, label)
        assertArrayEquals(File(root, "labelled.wav").readBytes(), file.readBytes())
    }
}
