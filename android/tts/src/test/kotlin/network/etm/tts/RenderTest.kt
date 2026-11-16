package network.etm.tts

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Speaks through the public API with a real model folder (-PmodelDir=...); skipped without one. */
class RenderTest {
    private val modelDir = System.getProperty("etm.tts.modelDir")?.let(::File)

    @Test
    fun speaksEnglishAndGerman() = runTest {
        assumeTrue("no -PmodelDir", modelDir != null)
        Tts.load(modelDir!!).use { tts ->
            val out = File("build/test-output").apply { mkdirs() }
            for ((lang, text) in listOf(
                "en" to "Hello! This is a test of the speech library, with 2 numbers and a question? The river had " +
                    "not stopped rising for three days, and by morning it reached the old stone bridge.",
                "de" to "Hallo! Das ist ein Test der Sprachbibliothek, am 3. Mai um 7:30 Uhr. Der Fluss stieg seit drei " +
                    "Tagen, und am Morgen erreichte er die alte Steinbrücke am Rand des Dorfes.",
            )) {
                val started = System.nanoTime()
                val speech = tts.speak(text, lang = lang)
                val seconds = (System.nanoTime() - started) / 1e9
                assertTrue("non-finite samples", speech.samples.all { it.isFinite() })
                assertTrue("implausible length ${speech.durationSeconds}", speech.durationSeconds in 4.0..30.0)
                val streamed = tts.stream(text, lang = lang).toList()
                assertArrayEquals(speech.samples, streamed.reduce { a, b -> a + b }, 0f)
                val wav = File(out, "speak_$lang.wav")
                speech.saveWav(wav)
                assertEquals("RIFF", wav.readBytes().copyOfRange(0, 4).decodeToString())
                println("$lang: %.2f s of audio in %.2f s -> %s".format(speech.durationSeconds, seconds, wav.absolutePath))
            }
        }
    }
}
