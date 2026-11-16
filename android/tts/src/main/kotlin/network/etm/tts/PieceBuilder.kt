package network.etm.tts

/** One piece of text ready for the model: token ids, speaker and language ids, and the pause after it. */
internal class Piece(val ids: LongArray, val pause: Double, val seed: Long, val speaker: Int, val language: Int,
                     val languageCode: String)

/** Text to pieces: front end, chunking at the language's pace, and token ids. */
internal class PieceBuilder(private val config: ModelConfig) {
    private val frontend = Frontend(config.vocab)
    private val ids = HashMap<String, Long>().apply { config.vocab.forEachIndexed { i, t -> put(t, i.toLong()) } }

    /** The pieces of [text]; piece i gets a noise seed derived from ([seed], i). Unknown tokens map to id 1. */
    fun build(text: String, speaker: Int, lang: String, speed: Double, seed: Long = 0): List<Piece> {
        return tokens(text, lang, speed).mapIndexed { i, p ->
            Piece(LongArray(p.tokens.size) { ids[p.tokens[it]] ?: 1L }, p.pause,
                  (seed * 1_000_003L + i) and Long.MAX_VALUE, speaker, config.languages.indexOf(lang), lang)
        }
    }

    /** The token strings of each piece and the pause after it. */
    fun tokens(text: String, lang: String, speed: Double = 1.0): List<TextPiece> {
        val lettersPerSecond = config.pace(lang).second
        val tokens = frontend.tokens(text, lang)
        return if (lettersPerSecond == null) frontend.chunk(tokens, speed) else frontend.chunk(tokens, speed, lettersPerSecond)
    }
}
