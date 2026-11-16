// Text to model tokens: normalization per language, the model's alphabet, and chunking into pieces of at most ~20 s.
package network.etm.tts

internal class TextPiece(val tokens: List<String>, val pause: Double)

internal class Frontend(vocab: List<String>) {
    private val T = Tables
    private val letters = T.LETTERS.codePointStrings().toSet()
    private val german = T.GERMAN_LETTERS.toSet()
    private val lettersDe = german.all { it in vocab }

    // Single-character tokens beyond the base alphabet: the letters and marks of the other languages' scripts.
    private val mlSet: Set<String> =
        vocab.filter { it.codePointCount(0, it.length) == 1 && it !in letters && it !in german }.toSet() +
            (if (lettersDe) german else emptySet())

    private fun alphabet(text: String, lang: String): String {
        val out = StringBuilder()
        if (lang != "en") {
            require(lang == "de") { "no alphabet for language $lang" }
            for (ch0 in pyLower(nfc(text)).replace("ẞ", "ß").codePointStrings()) {
                val ch = if (ch0 !in german) nfkd(ch0).codePointStrings().filter { !isCombining(it.codePointAt(0)) }.joinToString("") else ch0
                out.append(if (ch.isNotEmpty() && ch.codePointStrings().all { it in letters || it in german }) ch else " ")
            }
            return Rx.subL("\\s+", out.toString(), " ")
        }
        for (ch0 in pyLower(text).codePointStrings()) {
            val ch = nfkd(ch0).codePointStrings().filter { !isCombining(it.codePointAt(0)) }.joinToString("")
            out.append(if (ch.isNotEmpty() && ch.codePointStrings().all { it in letters }) ch else " ")
        }
        return Rx.subL("\\s+", out.toString(), " ")
    }

    private fun alphabetMl(text: String, lang: String): String {
        var s = pyLower(nfkd(text.translate(T.ML_PRE))).translate(T.ML_PUNCT)
        if (lang == "el") s = s.replace(";", "?")
        s = Rx.subL("\\s+", s.codePointStrings().joinToString("") { if (it in letters || it in mlSet) it else " " }, " ")
        if (lang in T.NO_SPACE_LANGS) s = Rx.subT("([.!?,;:]+[\"')]*)(?=[^\\s.!?,;:\"')])", s, "$1 ")
        return s
    }

    private fun normalize(text: String, lang: String): String = when {
        lang == "en" -> NormalizeEnglish.normalize(text)
        lang == "de" -> NormalizeGerman.normalize(text)
        lang in T.ML_LANGS -> collapse(Rx.subL(T.UNSAFE, text.dropLoneSurrogates().replace("‌", "").replace("‍", ""), " "))
        else -> error("no normalizer for language $lang")
    }

    private fun letters(value: String, lang: String): String {
        if (lang in T.ML_LANGS) return alphabetMl(normalize(value, lang), lang).trim()
        var out = alphabet(normalize(value, lang), lang).trim()
        if (lang != "en" && !lettersDe) out = out.codePointStrings().joinToString("") { T.GERMAN_FOLD[it] ?: it }
        return out
    }

    /**
     * Splits out markup the model has no tokens for: [..] and <..> tags are dropped, as are empty parentheses;
     * other parentheses are ordinary text.
     */
    private fun segments(text: String): List<String> {
        val out = ArrayList<String>()
        var pos = 0
        val m = Rx.p(T.TAG).matcher(text)
        while (m.find()) {
            val ev = m.group("event")
            if (ev != null && Rx.subL("\\s+", pyLower(ev.trim()), " ").isNotEmpty()) continue
            out.add(text.substring(pos, m.start()))
            pos = m.end()
        }
        out.add(text.substring(pos))
        return out
    }

    /** The utterance's tokens: one per character of the model's alphabet, single spaces between words. */
    fun tokens(text: String, lang: String): List<String> {
        val toks = ArrayList<String>()
        fun add(t: String) {
            if (t == " " && (toks.isEmpty() || toks.last() == " ")) return
            toks.add(t)
        }
        for (value in segments(text)) {
            if (value.isNotEmpty() && pyIsSpace(value.codePointAt(0))) add(" ")
            for (ch in letters(value, lang).codePointStrings()) add(ch)
            if (value.isNotEmpty() && pyIsSpace(value.codePointBefore(value.length))) add(" ")
        }
        while (toks.isNotEmpty() && toks.last() == " ") toks.removeAt(toks.size - 1)
        return toks
    }

    private fun strip(t: List<String>): List<String> {
        var a = 0
        var b = t.size
        while (a < b && t[a] == " ") a++
        while (b > a && t[b - 1] == " ") b--
        return t.subList(a, b)
    }

    /** Ends a piece with a full stop unless it already ends a sentence, so every piece is read as complete. */
    private fun finish(piece: List<String>): List<String> {
        val core = piece.toMutableList()
        while (core.isNotEmpty() && core.last() in listOf("\"", "'", ")")) core.removeAt(core.size - 1)
        if (core.isEmpty() || core.last() in listOf(".", "!", "?") || core.last().codePointCount(0, core.last().length) > 1) return piece
        var p = piece
        while (p.isNotEmpty() && p.last() in listOf(",", ";", ":", "-", " ")) p = p.subList(0, p.size - 1)
        if (p.isEmpty()) return p
        return p + "."
    }

    /**
     * Cuts tokens into pieces short enough for one pass of the model, preferring sentence ends, then clause
     * boundaries, then word boundaries; each piece carries the pause that follows it.
     */
    fun chunk(tokens: List<String>, speed: Double = 1.0, lettersPerSecond: Double = T.LETTERS_PER_SECOND): List<TextPiece> {
        val limit = minOf(T.MAX_LETTERS.toDouble(), lettersPerSecond * T.MAX_SECONDS * speed).toInt()
        val cuts = listOf("[.!?]+[\"')]*(?= )" to T.SENTENCE_PAUSE, "[,;:](?= )" to T.CLAUSE_PAUSE, "\\S(?= )" to T.CLAUSE_PAUSE)
        val pieces = ArrayList<TextPiece>()
        var rest = strip(tokens)
        while (rest.isNotEmpty()) {
            var cut = rest.size
            var pause = 0.0
            if (rest.size > limit) {
                cut = limit
                val flat = rest.joinToString("")  // one token is one code point
                val endUnits = flat.offsetByCodePoints(0, minOf(limit + 1, rest.size))
                for ((re, gap) in cuts) {
                    val m = Rx.p(re).matcher(flat)
                    m.region(0, endUnits)
                    var last = -1
                    while (m.find()) last = m.end()
                    if (last >= 0) { cut = flat.codePointCount(0, last); pause = gap; break }
                }
            }
            val piece = strip(rest.subList(0, cut))
            rest = strip(rest.subList(cut, rest.size))
            if (piece.any { (it.codePointCount(0, it.length) == 1 && Character.isLetter(it.codePointAt(0))) || it.codePointCount(0, it.length) > 1 })
                pieces.add(TextPiece(finish(piece), pause))
        }
        if (pieces.isNotEmpty()) pieces[pieces.size - 1] = TextPiece(pieces.last().tokens, 0.0)
        return pieces
    }
}
