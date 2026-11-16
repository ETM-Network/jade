// String helpers with the semantics of the reference front end (written in Python): Unicode-aware regexes, code point
// iteration, and Python's case and whitespace rules, so the Kotlin front end produces identical tokens.
package network.etm.tts

import java.text.Normalizer
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern

internal object Rx {
    private val cache = HashMap<String, Pattern>()
    const val I = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    // Desktop Java needs UNICODE_CHARACTER_CLASS for Unicode \w \d \s \b; Android's ICU regex is Unicode already and
    // rejects the flag.
    private val UCC: Int = try {
        Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS); Pattern.UNICODE_CHARACTER_CLASS
    } catch (e: IllegalArgumentException) {
        0
    }

    fun p(re: String, flags: Int = 0): Pattern = synchronized(cache) {
        cache.getOrPut("$flags/$re") { Pattern.compile(re, UCC or flags) }
    }

    /** Replaces every match with fn(match, input). */
    fun sub(re: String, text: String, flags: Int = 0, fn: (Matcher, String) -> String): String {
        val m = p(re, flags).matcher(text)
        val sb = StringBuilder()
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(fn(m, text)))
        m.appendTail(sb)
        return sb.toString()
    }

    /** Replaces every match with a template that may use $1-style group references. */
    fun subT(re: String, text: String, template: String, flags: Int = 0): String =
        p(re, flags).matcher(text).replaceAll(template)

    /** Replaces every match with a literal string. */
    fun subL(re: String, text: String, literal: String, flags: Int = 0): String =
        p(re, flags).matcher(text).replaceAll(Matcher.quoteReplacement(literal))

    fun search(re: String, text: String, flags: Int = 0) = p(re, flags).matcher(text).find()
}

internal fun String.translate(table: Map<String, String>): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        val ch = String(Character.toChars(cp))
        sb.append(table[ch] ?: ch)
        i += Character.charCount(cp)
    }
    return sb.toString()
}

/** Drops unpaired surrogates, as a UTF-8 round trip with errors ignored does. */
internal fun String.dropLoneSurrogates(): String {
    val sb = StringBuilder(length)
    var i = 0
    while (i < length) {
        val c = this[i]
        if (Character.isHighSurrogate(c) && i + 1 < length && Character.isLowSurrogate(this[i + 1])) {
            sb.append(c).append(this[i + 1]); i += 2; continue
        }
        if (!Character.isSurrogate(c)) sb.append(c)
        i++
    }
    return sb.toString()
}

internal fun String.codePointStrings(): List<String> {
    val out = ArrayList<String>(length)
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        out.add(String(Character.toChars(cp)))
        i += Character.charCount(cp)
    }
    return out
}

internal fun pyLower(s: String) = s.lowercase(Locale.ROOT)
internal fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)
internal fun nfkd(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKD)
internal fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

/** A combining mark (exact for the Latin, Greek and Cyrillic marks NFKD produces). */
internal fun isCombining(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
    Character.NON_SPACING_MARK, Character.ENCLOSING_MARK -> true
    else -> false
}

/** At least one cased character and no lowercase or titlecase one. */
internal fun pyIsUpper(s: String): Boolean {
    var cased = false
    for (ch in s.codePointStrings()) {
        val cp = ch.codePointAt(0)
        if (Character.isLowerCase(cp) || Character.isTitleCase(cp)) return false
        if (Character.isUpperCase(cp)) cased = true
    }
    return cased
}

/** Whitespace in the Unicode sense, including no-break spaces and the information separators. */
internal fun pyIsSpace(cp: Int): Boolean =
    Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp in 0x1c..0x1f || cp == 0x85

internal fun partition(s: String, sep: String): Triple<String, String, String> {
    val i = s.indexOf(sep)
    return if (i < 0) Triple(s, "", "") else Triple(s.substring(0, i), sep, s.substring(i + sep.length))
}

/** Parses a string of (possibly non-ASCII) decimal digits. */
internal fun pyInt(s: String): Long = java.math.BigInteger(s.codePointStrings().joinToString("") {
    Character.digit(it.codePointAt(0), 10).let { d -> if (d < 0) error("not a digit in $s") else d.toString() }
}).toLong()

internal fun collapse(s: String) = Rx.subL("\\s+", s, " ").trim()
