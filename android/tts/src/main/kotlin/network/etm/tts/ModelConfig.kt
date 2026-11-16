package network.etm.tts

import org.json.JSONObject
import java.io.File

/** The fields of a model folder's config.json the runtime uses. */
internal class ModelConfig(json: JSONObject) {
    val sampleRate = json.getInt("sample_rate")
    val frameSamples = json.getInt("frame_samples")
    val latentDim = json.getInt("latent_dim")
    val maxFrames = json.getInt("max_frames")
    val stepsDefault = json.optInt("steps_default", 12)
    val vocab: List<String> = json.getJSONArray("vocab").let { a -> List(a.length()) { a.getString(it) } }
    val voices: List<String> = json.getJSONArray("voices").let { a -> List(a.length()) { a.getString(it) } }
    val languages: List<String> = json.getJSONArray("languages").let { a -> List(a.length()) { a.getString(it) } }
    val variant: String = json.optJSONObject("model")?.optString("variant", "raw") ?: "raw"
    private val format = json.optString("format", "etm-tts/onnx").removePrefix("etm-tts/")

    /** The variant's folder in the model repo, e.g. "onnx/quants/q4"; named in saved files' labels. */
    val variantPath get() = if (variant == "raw") "$format/raw" else "$format/quants/$variant"
    private val pace: JSONObject? = json.optJSONObject("pace")
    private val guardPace = json.getJSONArray("guard_pace").let { it.getDouble(0) to it.getDouble(1) }

    val framesPerSecond get() = sampleRate.toDouble() / frameSamples

    /**
     * The length guard (seconds per token) and the chunker's letters per second for a language. English and German
     * use the defaults; other languages scale the guard by their speaking rate relative to English.
     */
    fun pace(lang: String): Pair<Pair<Double, Double>, Double?> {
        val p = pace?.optJSONObject(lang)
        if (lang == "en" || lang == "de" || p == null) return guardPace to null
        val s = pace.getJSONObject("en").getJSONArray("rate").getDouble(0) / p.getJSONArray("rate").getDouble(0)
        return (guardPace.first * s to guardPace.second * s) to p.getJSONArray("rate").getDouble(1)
    }

    companion object {
        fun read(dir: File) = ModelConfig(JSONObject(File(dir, "config.json").readText(Charsets.UTF_8)))
    }
}
