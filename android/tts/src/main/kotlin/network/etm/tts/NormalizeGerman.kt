// German text normalization: numbers, money, times, dates, ordinals, abbreviations, units and symbols to words.
package network.etm.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

internal object NormalizeGerman {
    private val T = Tables
    private val BIG = listOf(Triple(1_000_000_000_000L, "Billion", "Billionen"), Triple(1_000_000_000L, "Milliarde", "Milliarden"),
                             Triple(1_000_000L, "Million", "Millionen"))
    private const val NB = "(?<![\\d.,])"
    private const val NA = "(?![\\d])"

    private fun under1000(n: Long, final: Boolean = true): String {
        val hundreds = n / 100
        val rest = n % 100
        var out = ""
        if (hundreds != 0L) out = (if (hundreds == 1L) "" else T.DE_ONES[hundreds.toInt()]) + "hundert"
        if (rest == 1L) out += if (final) "eins" else "ein"
        else if (rest != 0L && rest < 20) out += T.DE_ONES[rest.toInt()]
        else if (rest != 0L) {
            val tens = rest / 10
            val ones = rest % 10
            out += (if (ones != 0L) (if (ones == 1L) "ein" else T.DE_ONES[ones.toInt()]) + "und" else "") + T.DE_TENS[tens.toInt()]
        }
        return out
    }

    fun cardinal(n0: Long): String {
        if (n0 < 0) return "minus " + cardinal(-n0)
        if (n0 == 0L) return "null"
        var n = n0
        val parts = ArrayList<String>()
        for ((size, one, many) in BIG) {
            val k = n / size
            n %= size
            if (k == 1L) parts.add("eine $one")
            else if (k != 0L) {
                var words = cardinal(k)
                if (words.endsWith("eins")) words = words.dropLast(1) + "e"
                parts.add("$words $many")
            }
        }
        val thousands = n / 1000
        val rest = n % 1000
        var word = ""
        if (thousands != 0L) word = (if (thousands == 1L) "" else under1000(thousands, final = false)) + "tausend"
        if (rest != 0L) word += under1000(rest)
        if (word.isNotEmpty()) parts.add(word)
        return parts.joinToString(" ")
    }

    fun ordinal(n: Long, ending: String = ""): String {
        if (n <= 0) return cardinal(n) + "te" + ending
        val r = n % 100
        if (r != 0L && r < 20 && n < 1_000_000) {
            val head = if (n >= 100) cardinal(n - r) else ""
            return head + (T.DE_ORD_SMALL[r.toString()] ?: (T.DE_ONES[r.toInt()] + "te")) + ending
        }
        return cardinal(n).replace(" ", "") + "ste" + ending
    }

    fun year(n: Long): String {
        if (n in 1100..1999) {
            val hi = n / 100
            val lo = n % 100
            return T.DE_ONES[hi.toInt()] + "hundert" + if (lo != 0L) under1000(lo) else ""
        }
        return cardinal(n)
    }

    fun digits(s: String) = s.codePointStrings().filter { Character.isDigit(it.codePointAt(0)) }
        .joinToString(" ") { T.DE_ONES[Character.digit(it.codePointAt(0), 10)] }

    fun number(s: String, beforeNoun: Boolean = false): String {
        val (w, _, frac) = partition(s, ",")
        val whole = w.replace(".", "").ifEmpty { "0" }
        var spoken: String
        if (whole.codePointCount(0, whole.length) > 15 || (whole.length > 1 && whole[0] == '0')) spoken = digits(whole)
        else {
            spoken = cardinal(pyInt(whole))
            if (beforeNoun && frac.isEmpty() && spoken.endsWith("eins")) spoken = spoken.dropLast(1)
        }
        return spoken + if (frac.isNotEmpty()) " Komma " + digits(frac) else ""
    }

    private fun scaled(amount: String, scale: Long): String {
        val (_, one, many) = BIG.first { it.first == scale }
        if (amount == "1" || amount == "1,0") return "eine $one"
        return "${number(amount)} $many"
    }

    private fun money(amount: String, unit: String): String {
        val name = T.DE_CURRENCY_UNIT.getValue(pyLower(unit))
        val sub = T.DE_CURRENCY_SUB.getValue(pyLower(unit))
        val (whole, _, frac) = partition(amount, ",")
        val cents = if (frac.isNotEmpty() && sub.isNotEmpty() && frac != "-") pyInt(frac.take(2).padEnd(2, '0')) else 0L
        if ((whole == "" || whole == "0") && cents != 0L) return " ${cardinal(cents)} $sub "
        val out = "${number(whole.ifEmpty { "0" }, beforeNoun = true)} $name"
        return if (cents != 0L) " $out ${cardinal(cents)} " else " $out "
    }

    private fun moneyScaled(m: Matcher): String {
        val amount = m.group("a")
        val scale = T.DE_SCALE_WORDS.getValue(pyLower(m.group("s"))).toLong()
        val unit = m.group("u") ?: m.group("p")
        return " ${scaled(amount, scale)}" + if (unit != null) " ${T.DE_CURRENCY_UNIT.getValue(pyLower(unit))} " else " "
    }

    private fun time(hs: String, ms: String): String {
        val h = pyInt(hs)
        val m = pyInt(ms)
        if (h > 24 || m > 59) return " ${cardinal(h)} ${digits(ms)} "
        val hour = if (h == 1L) "ein" else cardinal(h)
        return " $hour Uhr" + if (m != 0L) " ${cardinal(m)} " else " "
    }

    private fun date(day: String, month: String, yr: String?, ending: String = "n"): String? {
        val d = pyInt(day)
        val mo = pyInt(month)
        if (!(d in 1..31 && mo in 1..12)) return null
        var out = " ${ordinal(d, ending)} ${T.DE_MONTHS[(mo - 1).toInt()]}"
        if (!yr.isNullOrEmpty()) {
            val y = pyInt(yr)
            out += " " + if (yr.length == 4) year(y) else cardinal(y)
        }
        return "$out "
    }

    private fun ending(before: String): String {
        val tail = if (before.length > 40) before.substring(before.length - 40) else before
        val words = ArrayList<String>()
        val m = Rx.p("[^\\W\\d_]+").matcher(tail)
        while (m.find()) words.add(m.group())
        if (words.isNotEmpty() && pyLower(words.last()) in listOf("der", "die", "das")) {
            if (words.size < 2 || pyLower(words[words.size - 2]) !in T.DE_PREPOSITIONS) return ""
        }
        return "n"
    }

    private fun capitals(word: String): String {
        if (word in T.DE_LETTER_WORDS || !Rx.search("[AEIOUY\u00c4\u00d6\u00dcaeiouy\u00e4\u00f6\u00fc]", word))
            return " " + pyLower(word).codePointStrings().joinToString(" ") + " "
        return word
    }

    fun normalize(input: String): String {
        val num = T.DE_NUM
        var t = Rx.subL(T.UNSAFE, input.dropLoneSurrogates(), " ").translate(T.DE_TYPOGRAPHY)
        t = t.translate(T.TYPOGRAPHY)
        t = nfkc(t).replace("\u00ba", "\u00b0").replace("\u1e9e", "\u00df")
        t = Rx.subT("(\\d)\\s*[\u2013\u2014]\\s*(\\d)", t, "$1 bis $2")
        t = Rx.subL("\\s*[\u2013\u2014]\\s*", t, " - ")
        t = Rx.subL("(?<![\\w.,])-(?=\\d)", t, "minus ")
        for ((short, long) in T.DE_SENTENCE_FINAL) {
            t = Rx.subL("(?<![\\w.])$short(?=\\s*$|\\s+[A-Z\u00c4\u00d6\u00dc])", t, "$long.")
            t = Rx.subL("(?<![\\w.])$short", t, long)
        }
        for ((short, long) in T.DE_ABBREVIATIONS) t = Rx.subL("(?<![\\w.])$short", t, long)
        t = Rx.subL("(?<=[a-z\u00e4\u00f6\u00fc\u00df])str\\.", t, "stra\u00dfe")
        t = Rx.sub("(?<![\\w.])(?:[A-Za-z]\\.){2,}", t) { m, _ -> " " + pyLower(m.group(0).replace(".", "")).codePointStrings().joinToString(" ") + " " }
        val scale = "(?<s>Mio\\.|Mrd\\.|Millionen|Million|Milliarden|Milliarde|Billionen|Billion)"
        val cur = "\u20ac|\\$|\u00a3|\u00a5|EUR|USD|CHF|Euro|Dollar"
        t = Rx.sub("(?:(?<p>[\u20ac\$\u00a3\u00a5])\\s?)?$NB(?<a>$num)\\s?$scale(?:\\s?(?<u>$cur)(?![\\w]))?", t) { m, _ -> moneyScaled(m) }
        t = Rx.sub("([\u20ac\$\u00a3\u00a5])\\s?$NB($num)(?:,-)?", t) { m, _ -> money(m.group(2), m.group(1)) }
        t = Rx.sub("$NB($num)(?:,-)?\\s?(\u20ac|\\$|\u00a3|\u00a5|EUR|USD|CHF|Euro|Dollar)(?![\\w])", t) { m, _ -> money(m.group(1), m.group(2)) }
        t = Rx.sub("$NB($num)\\s?(%|Prozent)", t) { m, _ -> " ${number(m.group(1))} Prozent " }
        t = Rx.sub("$NB($num)\\s?\u2030", t) { m, _ -> " ${number(m.group(1))} Promille " }
        t = Rx.sub("$NB(\\d{1,2})[:.](\\d{2})\\s?(?:Uhr|h)\\b", t) { m, _ -> time(m.group(1), m.group(2)) }
        t = Rx.sub("$NB(\\d{1,2}):(\\d{2})(?![\\d:])", t) { m, _ -> time(m.group(1), m.group(2)) }
        t = Rx.sub("$NB(\\d{1,2})\\s?Uhr\\b", t) { m, _ -> time(m.group(1), "00") }
        t = Rx.sub("$NB(\\d+):(\\d+)$NA", t) { m, _ -> " ${number(m.group(1))} zu ${number(m.group(2))} " }
        t = Rx.sub("$NB(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4}|\\d{2}(?!\\d))?$NA", t) { m, s ->
            date(m.group(1), m.group(2), m.group(3), ending(s.substring(0, m.start()))) ?: m.group(0)
        }
        val months = T.DE_MONTHS.joinToString("|")
        t = Rx.sub("$NB(\\d{1,2})\\.\\s?($months)\\b", t) { m, s ->
            " ${ordinal(pyInt(m.group(1)), ending(s.substring(0, m.start())))} ${m.group(2)}"
        }
        val art = "\\b([Dd]er|[Dd]ie|[Dd]as|[Dd]en|[Dd]em|[Dd]es|[Aa]m|[Ii]m|[Zz]um|[Zz]ur|[Vv]om|[Bb]eim)\\s+"
        t = Rx.sub("$art$NB(\\d+)\\.(?=\\s)", t) { m, s ->
            "${m.group(1)} ${ordinal(pyInt(m.group(2)), ending(s.substring(0, m.start(2))))}"
        }
        t = Rx.sub("$NB(\\d{2}|\\d{4})er(?=\\b|[a-z\u00e4\u00f6\u00fc])", t) { m, _ ->
            (if (m.group(1).length == 4) year(pyInt(m.group(1))) else cardinal(pyInt(m.group(1)))) + "er"
        }
        t = Rx.sub("$NB(1[1-9]\\d\\d)(?![\\d]|,\\d|\\.\\d)", t) { m, _ -> " ${year(pyInt(m.group(1)))} " }
        val units = T.DE_UNITS_BY_LENGTH.joinToString("|") { Pattern.quote(it) }
        t = Rx.sub("$NB($num)\\s?($units)(?![\\w/\u00b2\u00b3])", t) { m, _ ->
            " ${number(m.group(1), beforeNoun = true)} ${T.DE_UNITS.getValue(m.group(2))} "
        }
        t = Rx.subT("(\\d)\\s*/\\s*(\\d)", t, "$1 durch $2")
        t = Rx.subT("(\\d)-(\\d)", t, "$1 bis $2")
        t = Rx.sub("$NB(\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?)$NA", t) { m, _ -> " ${number(m.group(1))} " }
        t = Rx.sub("$NB(\\d+)\\.(\\d+)$NA", t) { m, _ -> " ${number(m.group(1))} Punkt ${digits(m.group(2))} " }
        t = Rx.sub("$NB($num)$NA", t) { m, _ -> " ${number(m.group(1))} " }
        t = Rx.sub("\\d", t) { m, _ -> " ${T.DE_ONES[Character.digit(m.group(0).codePointAt(0), 10)]} " }
        t = Rx.subL("\\b(?:OK|Ok|ok)\\b", t, "okay")
        t = Rx.sub("\\b[A-Z\u00c4\u00d6\u00dc][A-Z\u00c4\u00d6\u00dca-z]{1,4}\\b", t) { m, _ ->
            val w = m.group(0)
            if (pyIsUpper(w.substring(1)) || w in T.DE_LETTER_WORDS) capitals(w) else w
        }
        t = Rx.subL("(?<=\\w)/(?=\\w)", t, " ")
        for ((symbol, spoken) in T.DE_SYMBOLS) t = t.replace(symbol, spoken)
        t = Rx.subT("\\s+([,.;:!?])", t, "$1")
        return collapse(t)
    }
}
