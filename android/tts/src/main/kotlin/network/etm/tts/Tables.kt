// Generated from the reference front end's tables (normalizers, alphabet, chunker). Do not edit by hand.
package network.etm.tts

@Suppress("MaxLineLength")
internal object Tables {
    const val UNSAFE = "[\u0000-\u0008\u000b-\u001f\u007f-\u009f\u061c\u200b-\u200f\u202a-\u202e\u2066-\u2069]"
    val TYPOGRAPHY: Map<String, String> = linkedMapOf(
        "\u2019" to "'",
        "\u2018" to "'",
        "\u02bc" to "'",
        "\u00b4" to "'",
        "`" to "'",
        "\u201c" to "\"",
        "\u201d" to "\"",
        "\u201e" to "\"",
        "\u00ab" to "\"",
        "\u00bb" to "\"",
        "\u2212" to "-",
        "\u2026" to "...",
        "\u00a0" to " "
    )

    val EN_ONES: List<String> = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")

    val EN_TENS: List<String> = listOf("_", "_", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    val EN_SCALES: List<String> = listOf("", "thousand", "million", "billion", "trillion")

    val EN_ORDINAL: Map<String, String> = linkedMapOf(
        "one" to "first",
        "two" to "second",
        "three" to "third",
        "five" to "fifth",
        "eight" to "eighth",
        "nine" to "ninth",
        "twelve" to "twelfth"
    )

    val EN_ABBREVIATIONS: Map<String, String> = linkedMapOf(
        "Mr." to "mister",
        "Mrs." to "missus",
        "Ms." to "miz",
        "Dr." to "doctor",
        "Prof." to "professor",
        "Jr." to "junior",
        "Sr." to "senior",
        "Capt." to "captain",
        "Gen." to "general",
        "Lt." to "lieutenant",
        "Sgt." to "sergeant",
        "Col." to "colonel",
        "Gov." to "governor",
        "Rev." to "reverend",
        "Mt." to "mount",
        "Ave." to "avenue",
        "Rd." to "road",
        "Blvd." to "boulevard",
        "vs." to "versus",
        "etc." to "et cetera",
        "e.g." to "for example",
        "i.e." to "that is",
        "approx." to "approximately",
        "Inc." to "incorporated",
        "Ltd." to "limited",
        "Co." to "company",
        "Corp." to "corporation",
        "Dept." to "department",
        "Jan." to "January",
        "Feb." to "February",
        "Aug." to "August",
        "Sept." to "September",
        "Oct." to "October",
        "Nov." to "November",
        "Dec." to "December"
    )

    val EN_LETTER_WORDS: List<String> = listOf("AI", "AKA", "API", "ASAP", "ATM", "BBQ", "CEO", "CFO", "CIA", "CPU", "DIY", "DJ", "ETA", "EU", "FAQ", "FBI", "GPU", "ID", "IOU", "IQ", "LA", "NYC", "PA", "PE", "RSVP", "UFO", "UK", "UN", "USA", "USB", "VIP")

    val EN_SPOKEN_CAPS: List<String> = listOf("BRR", "GRR", "HM", "HMM", "HMPH", "MM", "MMM", "PFFT", "PSST", "SHH", "SHHH", "TSK", "ZZZ")

    val EN_UNITS: Map<String, String> = linkedMapOf(
        "km" to "kilometer",
        "m" to "meter",
        "cm" to "centimeter",
        "mm" to "millimeter",
        "kg" to "kilogram",
        "g" to "gram",
        "mg" to "milligram",
        "lb" to "pound",
        "lbs" to "pound",
        "oz" to "ounce",
        "ft" to "foot",
        "mi" to "mile",
        "mph" to "mile per hour",
        "km/h" to "kilometer per hour",
        "kph" to "kilometer per hour",
        "ml" to "milliliter",
        "sec" to "second",
        "min" to "minute",
        "hr" to "hour",
        "hrs" to "hour",
        "ms" to "millisecond",
        "kb" to "kilobyte",
        "mb" to "megabyte",
        "gb" to "gigabyte",
        "tb" to "terabyte",
        "hz" to "hertz",
        "khz" to "kilohertz",
        "\u00b0c" to "degree celsius",
        "\u00b0f" to "degree fahrenheit",
        "\u00b0" to "degree"
    )

    val EN_UNITS_BY_LENGTH: List<String> = listOf("km/h", "lbs", "mph", "kph", "sec", "min", "hrs", "khz", "km", "cm", "mm", "kg", "mg", "lb", "oz", "ft", "mi", "ml", "hr", "ms", "kb", "mb", "gb", "tb", "hz", "\u00b0c", "\u00b0f", "m", "g", "\u00b0")

    val EN_CURRENCY_UNIT: Map<String, String> = linkedMapOf(
        "\$" to "dollar",
        "\u00a3" to "pound",
        "\u20ac" to "euro",
        "\u00a5" to "yen"
    )

    val EN_CURRENCY_SUB: Map<String, String> = linkedMapOf(
        "\$" to "cent",
        "\u00a3" to "penny",
        "\u20ac" to "cent",
        "\u00a5" to ""
    )

    val EN_IRREGULAR: Map<String, String> = linkedMapOf(
        "foot" to "feet",
        "penny" to "pence",
        "yen" to "yen",
        "hertz" to "hertz",
        "kilohertz" to "kilohertz"
    )

    val EN_SYMBOLS: Map<String, String> = linkedMapOf(
        "&" to " and ",
        "@" to " at ",
        "+" to " plus ",
        "=" to " equals ",
        "#" to " number ",
        "%" to " percent ",
        "\u00b0" to " degrees ",
        "~" to " about ",
        "*" to " ",
        "_" to " ",
        "|" to " ",
        "\\" to " ",
        "^" to " ",
        "{" to " ",
        "}" to " ",
        "<" to " ",
        ">" to " ",
        "[" to " ",
        "]" to " "
    )

    const val EN_NUM = "\\d+(?:,\\d{3})*(?:\\.\\d+)?"
    val DE_ONES: List<String> = listOf("null", "eins", "zwei", "drei", "vier", "f\u00fcnf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zw\u00f6lf", "dreizehn", "vierzehn", "f\u00fcnfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn")

    val DE_TENS: List<String> = listOf("_", "_", "zwanzig", "drei\u00dfig", "vierzig", "f\u00fcnfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    val DE_MONTHS: List<String> = listOf("Januar", "Februar", "M\u00e4rz", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    val DE_ORD_SMALL: Map<String, String> = linkedMapOf(
        "1" to "erste",
        "3" to "dritte",
        "7" to "siebte",
        "8" to "achte"
    )

    val DE_TYPOGRAPHY: Map<String, String> = linkedMapOf(
        "\u201a" to "'",
        "\u203a" to "\"",
        "\u2039" to "\""
    )

    val DE_ABBREVIATIONS: List<Pair<String, String>> = listOf(
        "z\\.\\s?B\\." to "zum Beispiel",
        "d\\.\\s?h\\." to "das hei\u00dft",
        "u\\.\\s?a\\." to "unter anderem",
        "u\\.\\s?U\\." to "unter Umst\u00e4nden",
        "v\\.\\s?a\\." to "vor allem",
        "o\\.\\s?\u00c4\\." to "oder \u00c4hnliches",
        "bzw\\." to "beziehungsweise",
        "ca\\." to "circa",
        "Nr\\." to "Nummer",
        "Dr\\." to "Doktor",
        "Prof\\." to "Professor",
        "Hr\\." to "Herr",
        "Fr\\." to "Frau",
        "Str\\." to "Stra\u00dfe",
        "St\\." to "Sankt",
        "evtl\\." to "eventuell",
        "ggf\\." to "gegebenenfalls",
        "inkl\\." to "inklusive",
        "exkl\\." to "exklusive",
        "zzgl\\." to "zuz\u00fcglich",
        "bspw\\." to "beispielsweise",
        "vgl\\." to "vergleiche",
        "sog\\." to "sogenannte",
        "Jh\\." to "Jahrhundert",
        "Tel\\." to "Telefon",
        "Abb\\." to "Abbildung",
        "Hrsg\\." to "Herausgeber",
        "Jan\\." to "Januar",
        "Feb\\." to "Februar",
        "Febr\\." to "Februar",
        "Apr\\." to "April",
        "Aug\\." to "August",
        "Sept?\\." to "September",
        "Okt\\." to "Oktober",
        "Nov\\." to "November",
        "Dez\\." to "Dezember"
    )

    val DE_SENTENCE_FINAL: List<Pair<String, String>> = listOf(
        "usw\\." to "und so weiter",
        "etc\\." to "et cetera"
    )

    val DE_SCALE_WORDS: Map<String, String> = linkedMapOf(
        "mio." to "1000000",
        "mio" to "1000000",
        "million" to "1000000",
        "millionen" to "1000000",
        "mrd." to "1000000000",
        "mrd" to "1000000000",
        "milliarde" to "1000000000",
        "milliarden" to "1000000000",
        "billion" to "1000000000000",
        "billionen" to "1000000000000"
    )

    val DE_CURRENCY_UNIT: Map<String, String> = linkedMapOf(
        "\u20ac" to "Euro",
        "eur" to "Euro",
        "euro" to "Euro",
        "\$" to "Dollar",
        "usd" to "Dollar",
        "dollar" to "Dollar",
        "\u00a3" to "Pfund",
        "chf" to "Franken",
        "\u00a5" to "Yen"
    )

    val DE_CURRENCY_SUB: Map<String, String> = linkedMapOf(
        "\u20ac" to "Cent",
        "eur" to "Cent",
        "euro" to "Cent",
        "\$" to "Cent",
        "usd" to "Cent",
        "dollar" to "Cent",
        "\u00a3" to "Pence",
        "chf" to "Rappen",
        "\u00a5" to ""
    )

    val DE_UNITS: Map<String, String> = linkedMapOf(
        "km/h" to "Kilometer pro Stunde",
        "km" to "Kilometer",
        "m" to "Meter",
        "cm" to "Zentimeter",
        "mm" to "Millimeter",
        "kg" to "Kilogramm",
        "g" to "Gramm",
        "mg" to "Milligramm",
        "l" to "Liter",
        "ml" to "Milliliter",
        "\u00b0C" to "Grad Celsius",
        "\u00b0F" to "Grad Fahrenheit",
        "\u00b0" to "Grad",
        "m\u00b2" to "Quadratmeter",
        "qm" to "Quadratmeter",
        "km\u00b2" to "Quadratkilometer",
        "m\u00b3" to "Kubikmeter",
        "kWh" to "Kilowattstunden",
        "kW" to "Kilowatt",
        "PS" to "PS",
        "GB" to "Gigabyte",
        "MB" to "Megabyte"
    )

    val DE_UNITS_BY_LENGTH: List<String> = listOf("km/h", "km\u00b2", "kWh", "km", "cm", "mm", "kg", "mg", "ml", "\u00b0C", "\u00b0F", "m\u00b2", "qm", "m\u00b3", "kW", "PS", "GB", "MB", "m", "g", "l", "\u00b0")

    val DE_LETTER_WORDS: List<String> = listOf("ARD", "AfD", "BMW", "BRD", "CDU", "CSU", "DB", "DDR", "EU", "FDP", "ICE", "KI", "PC", "SPD", "UK", "USA", "VW", "ZDF")

    val DE_SYMBOLS: Map<String, String> = linkedMapOf(
        "&" to " und ",
        "+" to " plus ",
        "=" to " gleich ",
        "%" to " Prozent ",
        "\u2030" to " Promille ",
        "\u00b0" to " Grad ",
        "\u00a7" to " Paragraf ",
        "~" to " circa ",
        "@" to " \u00e4t ",
        "#" to " Nummer ",
        "\u20ac" to " Euro ",
        "\$" to " Dollar ",
        "\u00a3" to " Pfund ",
        "*" to " ",
        "_" to " ",
        "|" to " ",
        "\\" to " ",
        "^" to " ",
        "{" to " ",
        "}" to " ",
        "<" to " ",
        ">" to " ",
        "[" to " ",
        "]" to " "
    )

    val DE_PREPOSITIONS: List<String> = listOf("an", "in", "auf", "von", "zu", "mit", "bei", "nach", "aus", "seit", "vor", "unter", "\u00fcber", "hinter", "neben", "zwischen")

    const val DE_NUM = "\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?|\\d+(?:,\\d+)?"
    const val LETTERS = " !\"%&'(),-./:;?abcdefghijklmnopqrstuvwxyz"
    val GERMAN_LETTERS: List<String> = listOf("\u00e4", "\u00f6", "\u00fc", "\u00df")

    val GERMAN_FOLD: Map<String, String> = linkedMapOf(
        "\u00e4" to "ae",
        "\u00f6" to "oe",
        "\u00fc" to "ue",
        "\u00df" to "ss"
    )

    val ML_LANGS: List<String> = listOf("it", "pt", "el", "bg", "ru", "ja", "hi", "fr", "es", "pl", "lv", "nl", "cs", "sk", "sl", "hr", "hu", "ro", "lt", "et", "da", "sv")

    val NO_SPACE_LANGS: List<String> = listOf("ja")

    val ML_PUNCT: Map<String, String> = linkedMapOf(
        "\u3002" to ".",
        "\uff61" to ".",
        "\u3001" to ",",
        "\uff64" to ",",
        "\uff0c" to ",",
        "\u00ab" to "\"",
        "\u00bb" to "\"",
        "\u201e" to "\"",
        "\u201c" to "\"",
        "\u201d" to "\"",
        "\u201f" to "\"",
        "\u2018" to "'",
        "\u2019" to "'",
        "\u201a" to "'",
        "\u201b" to "'",
        "\u02bc" to "'",
        "\u00b4" to "'",
        "`" to "'",
        "\u2039" to "'",
        "\u203a" to "'",
        "\u300c" to "\"",
        "\u300d" to "\"",
        "\u300e" to "\"",
        "\u300f" to "\"",
        "\u301d" to "\"",
        "\u301e" to "\"",
        "\u2014" to "-",
        "\u2013" to "-",
        "\u2010" to "-",
        "\u2011" to "-",
        "\u2212" to "-",
        "\u2015" to "-",
        "\u0964" to ".",
        "\u0965" to ".",
        "\u00b7" to ",",
        "\u2027" to ",",
        "\u30fb" to " ",
        "\u301c" to "-",
        "\uff5e" to "-",
        "\u061f" to "?",
        "\u060c" to ",",
        "\u061b" to ";"
    )

    val ML_PRE: Map<String, String> = linkedMapOf(
        "\u00b4" to "'",
        "\u0384" to "'",
        "\u1ffe" to "'",
        "\u1fbf" to "'",
        "\uff5e" to "-",
        "\u02dc" to "-"
    )

    const val TAG = "\\((?<event>[^()]*)\\)|\\[(?<sfx>[^\\[\\]]*)\\]|<\\s*bg\\s*:\\s*(?<bg>[^<>]+?)\\s*>|(?<bgclose><\\s*/\\s*bg\\s*>)|<\\s*(?<close>/?)\\s*(?<style>[^<>/\\s][^<>/]*?)\\s*>"
    const val LETTERS_PER_SECOND = 18.0
    const val MAX_SECONDS = 20.0
    const val MAX_LETTERS = 400
    const val SENTENCE_PAUSE = 0.25
    const val CLAUSE_PAUSE = 0.12
}
