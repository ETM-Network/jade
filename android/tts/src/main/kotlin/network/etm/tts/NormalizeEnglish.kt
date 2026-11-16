// English text normalization: numbers, money, times, dates, abbreviations, units and symbols to words.
package network.etm.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

internal object NormalizeEnglish {
    private val T = Tables

    fun cardinal(n: Long): String {
        if (n < 0) return "minus " + cardinal(-n)
        if (n < 20) return T.EN_ONES[n.toInt()]
        if (n < 100) return T.EN_TENS[(n / 10).toInt()] + if (n % 10 == 0L) "" else " " + T.EN_ONES[(n % 10).toInt()]
        if (n < 1000) return T.EN_ONES[(n / 100).toInt()] + " hundred" + if (n % 100 == 0L) "" else " " + cardinal(n % 100)
        val parts = ArrayList<String>()
        var m = n
        var scale = 0
        while (m != 0L) {
            val chunk = m % 1000
            m /= 1000
            if (chunk != 0L) parts.add(cardinal(chunk) + if (T.EN_SCALES[scale].isNotEmpty()) " " + T.EN_SCALES[scale] else "")
            scale++
        }
        return parts.reversed().joinToString(" ")
    }

    fun ordinal(n: Long): String {
        val words = cardinal(n).split(" ").toMutableList()
        val last = words.last()
        words[words.size - 1] = T.EN_ORDINAL[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return words.joinToString(" ")
    }

    fun year(n: Long): String {
        if (n in 2000..2009 || n % 1000 == 0L) return cardinal(n)
        val hi = n / 100
        val lo = n % 100
        if (lo == 0L) return cardinal(hi) + " hundred"
        return cardinal(hi) + if (lo < 10) " oh " + T.EN_ONES[lo.toInt()] else " " + cardinal(lo)
    }

    fun digits(s: String) = s.codePointStrings().filter { Character.isDigit(it.codePointAt(0)) }
        .joinToString(" ") { T.EN_ONES[Character.digit(it.codePointAt(0), 10)] }

    fun number(s: String): String {
        var (whole, _, frac) = partition(s.replace(",", ""), ".")
        if (whole.isEmpty()) whole = "0"
        val spoken = if (whole.codePointCount(0, whole.length) > 15 || (whole.length > 1 && whole[0] == '0')) digits(whole)
        else cardinal(pyInt(whole))
        return spoken + if (frac.isNotEmpty()) " point " + digits(frac) else ""
    }

    fun plural(word: String, amount: String): String {
        if (amount.replace(",", "") in listOf("1", "1.0") || word.isEmpty()) return word
        val (head, _, tail) = partition(word, " ")
        return (T.EN_IRREGULAR[head] ?: (head + "s")) + if (tail.isNotEmpty()) " $tail" else ""
    }

    private fun money(m: Matcher): String {
        val symbol = m.group(1)
        val amount = m.group(2)
        val scale = pyLower(m.group(3) ?: "")
        val unit = T.EN_CURRENCY_UNIT.getValue(symbol)
        val sub = T.EN_CURRENCY_SUB.getValue(symbol)
        if (scale.isNotEmpty()) return " ${number(amount)} $scale ${plural(unit, "2")} "
        val (whole, _, frac) = partition(amount.replace(",", ""), ".")
        val cents = if (frac.isNotEmpty() && sub.isNotEmpty()) pyInt(frac.take(2).padEnd(2, '0')) else 0L
        val parts = ArrayList<String>()
        if (!((whole == "" || whole == "0") && cents != 0L)) parts.add("${number(whole.ifEmpty { "0" })} ${plural(unit, whole.ifEmpty { "0" })}")
        if (cents != 0L) parts.add("${cardinal(cents)} ${plural(sub, cents.toString())}")
        return " " + parts.joinToString(" ") + " "
    }

    private fun unit(m: Matcher): String {
        val amount = m.group(1)
        val word = T.EN_UNITS.getValue(pyLower(m.group(2)))
        val (head, _, tail) = partition(word, " ")
        if (head == "degree") return " ${number(amount)} ${plural("degree", amount)} $tail "
        return " ${number(amount)} ${plural(word, amount)} "
    }

    private fun time(m: Matcher): String {
        val h = pyInt(m.group(1))
        val mins = pyInt(m.group(2))
        val ampm = pyLower((m.group(3) ?: "").replace(".", ""))
        if (h > 24 || mins > 59) return " ${cardinal(h)} ${digits(m.group(2))} "
        val spoken = if (mins == 0L) cardinal(h) + if (ampm.isNotEmpty()) "" else " o'clock"
        else cardinal(h) + if (mins < 10) " oh " + T.EN_ONES[mins.toInt()] else " " + cardinal(mins)
        return " $spoken${if (ampm.isNotEmpty()) " " + ampm.codePointStrings().joinToString(" ") else ""} "
    }

    private fun yearSub(m: Matcher): String {
        var spoken = year(pyInt(m.group(1)))
        if (m.group(2) != null) spoken = if (spoken.endsWith("y")) spoken.dropLast(1) + "ies" else spoken + "s"
        return " $spoken "
    }

    private fun capitals(m: Matcher): String {
        val word = m.group(0)
        if (word == "OK") return "okay"
        if (word in T.EN_LETTER_WORDS || (word !in T.EN_SPOKEN_CAPS && !Rx.search("[AEIOUY]", word)))
            return " " + pyLower(word).codePointStrings().joinToString(" ") + " "
        return word
    }

    fun normalize(input: String): String {
        val num = T.EN_NUM
        var t = Rx.subL(T.UNSAFE, input.dropLoneSurrogates(), " ").translate(T.TYPOGRAPHY)
        t = nfkc(t).replace("\u00ba", "\u00b0")
        t = Rx.subT("(\\d)\\s*[\u2013\u2014]\\s*(\\d)", t, "$1 to $2")
        t = Rx.subL("\\s*[\u2013\u2014]\\s*", t, " - ")
        t = Rx.subL("(?<![\\w.])-(?=\\d)", t, "minus ")
        for ((short, long) in T.EN_ABBREVIATIONS) t = Rx.subL("(?<![\\w.])" + Pattern.quote(short), t, long)
        t = Rx.subL("\\bSt\\.(?=\\s+[A-Z])", t, "saint")
        t = Rx.subL("\\bSt\\.", t, "street")
        t = Rx.subL("\\bNo\\.\\s*(?=\\d)", t, "number ")
        t = Rx.sub("\\b(?:[A-Za-z]\\.){2,}", t) { m, _ -> " " + pyLower(m.group(0).replace(".", "")).codePointStrings().joinToString(" ") + " " }
        t = Rx.sub("([\$\u00a3\u20ac\u00a5])\\s?($num)(?:\\s?(thousand|million|billion|trillion)\\b)?", t, Rx.I) { m, _ -> money(m) }
        t = Rx.sub("($num)\\s?%", t) { m, _ -> " ${number(m.group(1))} percent " }
        t = Rx.sub("\\b(\\d{1,2}):(\\d{2})\\s?([ap]\\.?m\\.?)?(?![\\w:])", t, Rx.I) { m, _ -> time(m) }
        t = Rx.sub("\\b(\\d+(?:,\\d{3})*)(st|nd|rd|th)\\b", t, Rx.I) { m, _ -> " ${ordinal(pyInt(m.group(1).replace(",", "")))} " }
        t = Rx.sub("\\b(1[1-9]\\d\\d|20\\d\\d)(s)?\\b(?![.,]\\d)", t) { m, _ -> yearSub(m) }
        val units = T.EN_UNITS_BY_LENGTH.joinToString("|") { Pattern.quote(it) }
        t = Rx.sub("($num)\\s?($units)(?![\\w/])", t, Rx.I) { m, _ -> unit(m) }
        t = Rx.subT("(\\d)\\s*/\\s*(\\d)", t, "$1 slash $2")
        t = Rx.subT("(\\d)-(\\d)", t, "$1 to $2")
        t = Rx.sub(num, t) { m, _ -> " ${number(m.group(0))} " }
        t = Rx.sub("\\b[A-Z]{2,5}\\b", t) { m, _ -> capitals(m) }
        t = Rx.subL("\\b(?:ok|Ok)\\b", t, "okay")
        t = Rx.subL("(?<=\\w)/(?=\\w)", t, " ")
        for ((symbol, spoken) in T.EN_SYMBOLS) t = t.replace(symbol, spoken)
        t = Rx.subT("\\s+([,.;:!?])", t, "$1")
        return collapse(t)
    }
}
