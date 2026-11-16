package network.etm.tts.sample

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.etm.tts.Speech
import network.etm.tts.Tts
import java.io.File

/** One screen: load a model folder, then speak or save text. */
class MainActivity : ComponentActivity() {
    private var tts: Tts? = null
    private var job: Job? = null
    private val status = mutableStateOf("Load a model folder to start.")
    private val loaded = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val defaultDir = File(getExternalFilesDir(null), "models/q8").path
        setContent { MaterialTheme { Screen(defaultDir) } }
    }

    override fun onDestroy() {
        job?.cancel()
        tts?.close()
        super.onDestroy()
    }

    private fun load(dir: String) {
        job?.cancel()
        status.value = "Loading $dir ..."
        job = lifecycleScope.launch {
            try {
                val model = withContext(Dispatchers.Default) { Tts.load(File(dir)) }
                tts?.close()
                tts = model
                loaded.value = true
                status.value = "Loaded. ${model.voices.size} voices, ${model.languages.size} languages."
            } catch (e: Exception) {
                status.value = "Could not load: ${e.message}"
            }
        }
    }

    private fun speak(text: String, voice: String, lang: String, steps: Int) {
        val model = tts ?: return
        job?.cancel()
        status.value = "Speaking ..."
        job = lifecycleScope.launch {
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(model.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(model.sampleRate * 4)
                .build()
            try {
                track.play()
                model.stream(text, voice, lang, steps).collect { chunk ->
                    withContext(Dispatchers.IO) { track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) }
                }
                status.value = "Done."
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            } finally {
                track.stop()
                track.release()
            }
        }
    }

    private fun save(text: String, voice: String, lang: String, steps: Int) {
        val model = tts ?: return
        job?.cancel()
        status.value = "Rendering ..."
        job = lifecycleScope.launch {
            try {
                val speech: Speech = withContext(Dispatchers.Default) { model.speak(text, voice, lang, steps) }
                val file = File(getExternalFilesDir(null), "speech_${System.currentTimeMillis()}.wav")
                withContext(Dispatchers.IO) { speech.saveWav(file) }
                status.value = "Saved %.1f s to ${file.path}".format(speech.durationSeconds)
            } catch (e: Exception) {
                status.value = "Error: ${e.message}"
            }
        }
    }

    @Composable
    private fun Screen(defaultDir: String) {
        var dir by remember { mutableStateOf(defaultDir) }
        var text by remember { mutableStateOf("Hello! This sentence was spoken on this phone, without a network connection.") }
        var voice by remember { mutableStateOf("Juliette") }
        var lang by remember { mutableStateOf("en") }
        var steps by remember { mutableStateOf(12) }
        val model = if (loaded.value) tts else null

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("etm-tts sample", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(dir, { dir = it }, label = { Text("Model folder") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { load(dir) }) { Text("Load model") }
            OutlinedTextField(text, { text = it }, label = { Text("Text") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Voice", voice, model?.voices ?: listOf(voice)) { voice = it }
                Picker("Language", lang, model?.languages ?: listOf(lang)) { lang = it }
                Picker("Steps", steps.toString(), (2..16).map { it.toString() }) { steps = it.toInt() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { speak(text, voice, lang, steps) }, enabled = model != null) { Text("Speak") }
                OutlinedButton(onClick = { save(text, voice, lang, steps) }, enabled = model != null) { Text("Save WAV") }
                OutlinedButton(onClick = { job?.cancel(); status.value = "Stopped." }) { Text("Stop") }
            }
            Text(status.value, style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun Picker(label: String, value: String, options: List<String>, onPick: (String) -> Unit) {
        var open by remember { mutableStateOf(false) }
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { open = true }) { Text(value) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(text = { Text(option) }, onClick = { onPick(option); open = false })
                }
            }
        }
    }
}
