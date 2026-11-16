"""German normalization: cardinals (German separators: 12.500, 3,5), years, decades, dates, ordinals after an
article, times, percent, money, common units, abbreviations and symbols are spelled out. Nothing is left unread:
digits it cannot place are read one by one.

Ordinals end in "-ten" ("am vierzehnten März"), or "-te" right after der/die/das without a preposition before it
("Heute ist der dritte Mai"). "1" is "eins" on its own and "ein" before a unit, a currency or "Uhr".
"""
import re
import unicodedata

from .english import TYPOGRAPHY, UNSAFE

ONES = ("null eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf dreizehn vierzehn fünfzehn sechzehn "
        "siebzehn achtzehn neunzehn").split()
TENS = "_ _ zwanzig dreißig vierzig fünfzig sechzig siebzig achtzig neunzig".split()
BIG = ((10 ** 12, "Billion", "Billionen"), (10 ** 9, "Milliarde", "Milliarden"), (10 ** 6, "Million", "Millionen"))
MONTHS = ("Januar Februar März April Mai Juni Juli August September Oktober November Dezember").split()
ORD_SMALL = {1: "erste", 3: "dritte", 7: "siebte", 8: "achte"}

TYPOGRAPHY_DE = str.maketrans({"‚": "'", "›": '"', "‹": '"'})

ABBREVIATIONS = [
    (r"z\.\s?B\.", "zum Beispiel"), (r"d\.\s?h\.", "das heißt"), (r"u\.\s?a\.", "unter anderem"),
    (r"u\.\s?U\.", "unter Umständen"), (r"v\.\s?a\.", "vor allem"), (r"o\.\s?Ä\.", "oder Ähnliches"),
    (r"bzw\.", "beziehungsweise"), (r"ca\.", "circa"), (r"Nr\.", "Nummer"), (r"Dr\.", "Doktor"),
    (r"Prof\.", "Professor"), (r"Hr\.", "Herr"), (r"Fr\.", "Frau"), (r"Str\.", "Straße"), (r"St\.", "Sankt"),
    (r"evtl\.", "eventuell"), (r"ggf\.", "gegebenenfalls"), (r"inkl\.", "inklusive"), (r"exkl\.", "exklusive"),
    (r"zzgl\.", "zuzüglich"), (r"bspw\.", "beispielsweise"), (r"vgl\.", "vergleiche"), (r"sog\.", "sogenannte"),
    (r"Jh\.", "Jahrhundert"), (r"Tel\.", "Telefon"), (r"Abb\.", "Abbildung"), (r"Hrsg\.", "Herausgeber"),
    (r"Jan\.", "Januar"), (r"Feb\.", "Februar"), (r"Febr\.", "Februar"), (r"Apr\.", "April"), (r"Aug\.", "August"),
    (r"Sept?\.", "September"), (r"Okt\.", "Oktober"), (r"Nov\.", "November"), (r"Dez\.", "Dezember"),
]
# abbreviations whose dot also ends the sentence when a new sentence follows or the text ends
SENTENCE_FINAL = [(r"usw\.", "und so weiter"), (r"etc\.", "et cetera")]
SCALE_WORDS = {"mio.": 10 ** 6, "mio": 10 ** 6, "million": 10 ** 6, "millionen": 10 ** 6,
               "mrd.": 10 ** 9, "mrd": 10 ** 9, "milliarde": 10 ** 9, "milliarden": 10 ** 9,
               "billion": 10 ** 12, "billionen": 10 ** 12}
CURRENCY = {"€": ("Euro", "Cent"), "eur": ("Euro", "Cent"), "euro": ("Euro", "Cent"), "$": ("Dollar", "Cent"),
            "usd": ("Dollar", "Cent"), "dollar": ("Dollar", "Cent"), "£": ("Pfund", "Pence"),
            "chf": ("Franken", "Rappen"), "¥": ("Yen", "")}
UNITS = {"km/h": "Kilometer pro Stunde", "km": "Kilometer", "m": "Meter", "cm": "Zentimeter", "mm": "Millimeter",
         "kg": "Kilogramm", "g": "Gramm", "mg": "Milligramm", "l": "Liter", "ml": "Milliliter", "°C": "Grad Celsius",
         "°F": "Grad Fahrenheit", "°": "Grad", "m²": "Quadratmeter", "qm": "Quadratmeter", "km²": "Quadratkilometer",
         "m³": "Kubikmeter", "kWh": "Kilowattstunden", "kW": "Kilowatt", "PS": "PS", "GB": "Gigabyte",
         "MB": "Megabyte"}
LETTER_WORDS = frozenset("EU USA UK CDU CSU SPD FDP AfD BRD DDR ARD ZDF ICE DB VW BMW PC KI".split())
SYMBOLS = {"&": " und ", "+": " plus ", "=": " gleich ", "%": " Prozent ", "‰": " Promille ", "°": " Grad ",
           "§": " Paragraf ", "~": " circa ", "@": " ät ", "#": " Nummer ", "€": " Euro ", "$": " Dollar ",
           "£": " Pfund ", "*": " ", "_": " ", "|": " ", "\\": " ", "^": " ", "{": " ", "}": " ", "<": " ", ">": " ",
           "[": " ", "]": " "}
PREPOSITIONS = ("an in auf von zu mit bei nach aus seit vor unter über hinter neben zwischen").split()

NUM = r"\d{1,3}(?:\.\d{3})+(?:,\d+)?|\d+(?:,\d+)?"  # thousands dots in groups of three, optional decimal comma
NB = r"(?<![\d.,])"
NA = r"(?![\d])"


def _under_1000(n, final=True):
    """0 < n < 1000 as one word; final: a trailing 1 is "eins" (else "ein", as in "einundzwanzig")."""
    hundreds, rest = divmod(n, 100)
    out = ""
    if hundreds:
        out = ("" if hundreds == 1 else ONES[hundreds]) + "hundert"
    if rest == 1:
        out += "eins" if final else "ein"
    elif rest and rest < 20:
        out += ONES[rest]
    elif rest:
        tens, ones = divmod(rest, 10)
        out += (("ein" if ones == 1 else ONES[ones]) + "und" if ones else "") + TENS[tens]
    return out


def cardinal(n):
    """Below a million one compound word; millions and up separate words."""
    if n < 0:
        return "minus " + cardinal(-n)
    if n == 0:
        return "null"
    parts = []
    for size, one, many in BIG:
        k, n = divmod(n, size)
        if k == 1:
            parts.append("eine " + one)
        elif k:
            words = cardinal(k)
            if words.endswith("eins"):  # 101 Millionen: "hunderteine Millionen"
                words = words[:-1] + "e"
            parts.append(words + " " + many)
    thousands, rest = divmod(n, 1000)
    word = ""
    if thousands:
        word = ("" if thousands == 1 else _under_1000(thousands, final=False)) + "tausend"
    if rest:
        word += _under_1000(rest)
    if word:
        parts.append(word)
    return " ".join(parts)


def ordinal(n, ending=""):
    """The ordinal stem ("dritte", "einundzwanzigste") plus an ending ("n" for "dritten")."""
    if n <= 0:
        return cardinal(n) + "te" + ending
    r = n % 100
    if r and r < 20 and n < 10 ** 6:
        head = cardinal(n - r) if n >= 100 else ""
        return head + ORD_SMALL.get(r, ONES[r] + "te") + ending
    return cardinal(n).replace(" ", "") + "ste" + ending


def year(n):
    if 1100 <= n <= 1999:
        hi, lo = divmod(n, 100)
        return ONES[hi] + "hundert" + (_under_1000(lo) if lo else "")
    return cardinal(n)


def digits(s):
    return " ".join(ONES[int(c)] for c in s if c.isdigit())


def number(s, before_noun=False):
    """A written German number read aloud; before_noun: a bare 1 is "ein"."""
    whole, _, frac = s.partition(",")
    whole = whole.replace(".", "") or "0"
    if len(whole) > 15 or (len(whole) > 1 and whole[0] == "0"):
        spoken = digits(whole)
    else:
        spoken = cardinal(int(whole))
        if before_noun and not frac and spoken.endswith("eins"):
            spoken = spoken[:-1]
    return spoken + (" Komma " + digits(frac) if frac else "")


def _scaled(amount, scale):
    one, many = next((o, m) for size, o, m in BIG if size == scale)
    if amount in ("1", "1,0"):
        return "eine " + one
    return f"{number(amount)} {many}"


def _money(amount, unit):
    name, sub = CURRENCY[unit.lower()]
    whole, _, frac = amount.partition(",")
    cents = int(frac[:2].ljust(2, "0")) if frac and sub and frac != "-" else 0
    if whole in ("", "0") and cents:
        return f" {cardinal(cents)} {sub} "
    out = f"{number(whole or '0', before_noun=True)} {name}"
    return f" {out} {cardinal(cents)} " if cents else f" {out} "


def _money_scaled(m):
    amount, scale, unit = m.group("a"), SCALE_WORDS[m.group("s").lower()], m.group("u") or m.group("p")
    return f" {_scaled(amount, scale)}" + (f" {CURRENCY[unit.lower()][0]} " if unit else " ")


def _time(h, mins):
    h, m = int(h), int(mins)
    if h > 24 or m > 59:
        return f" {cardinal(h)} {digits(mins)} "
    hour = "ein" if h == 1 else cardinal(h)
    return f" {hour} Uhr" + (f" {cardinal(m)} " if m else " ")


def _date(day, month, yr=None, ending="n"):
    d, mo = int(day), int(month)
    if not (1 <= d <= 31 and 1 <= mo <= 12):
        return None
    out = f" {ordinal(d, ending)} {MONTHS[mo - 1]}"
    if yr:
        y = int(yr)
        out += " " + (year(y) if len(yr) == 4 else cardinal(y))
    return out + " "


def _ending(before):
    """'-ten', or '-te' right after der/die/das with no preposition before it."""
    words = re.findall(r"[^\W\d_]+", before[-40:])
    if words and words[-1].lower() in ("der", "die", "das"):
        if len(words) < 2 or words[-2].lower() not in PREPOSITIONS:
            return ""
    return "n"


def _capitals(m):
    word = m.group(0)
    if word in LETTER_WORDS or not re.search("[AEIOUYÄÖÜaeiouyäöü]", word):
        return " " + " ".join(word.lower()) + " "
    return word


def normalize(text):
    """Written German -> spoken German (still cased)."""
    text = UNSAFE.sub(" ", text.encode("utf-8", "ignore").decode("utf-8")).translate(TYPOGRAPHY_DE)
    text = text.translate(TYPOGRAPHY)
    text = unicodedata.normalize("NFKC", text).replace("º", "°").replace("ẞ", "ß")
    text = re.sub(r"(\d)\s*[–—]\s*(\d)", r"\1 bis \2", text)  # ranges: 1990–1995
    text = re.sub(r"\s*[–—]\s*", " - ", text)  # a dash between words is a pause
    text = re.sub(r"(?<![\w.,])-(?=\d)", "minus ", text)

    for short, long in SENTENCE_FINAL:
        text = re.sub(r"(?<![\w.])" + short + r"(?=\s*$|\s+[A-ZÄÖÜ])", long + ".", text)
        text = re.sub(r"(?<![\w.])" + short, long, text)
    for short, long in ABBREVIATIONS:
        text = re.sub(r"(?<![\w.])" + short, long, text)
    text = re.sub(r"(?<=[a-zäöüß])str\.", "straße", text)  # Hauptstr. -> Hauptstraße
    text = re.sub(r"(?<![\w.])(?:[A-Za-z]\.){2,}", lambda m: " " + " ".join(m.group(0).replace(".", "").lower()) + " ",
                  text)

    # scaled amounts with an optional currency: "2,5 Mio. €", "€ 3 Mrd.", "1 Million Euro"
    scale = r"(?P<s>Mio\.|Mrd\.|Millionen|Million|Milliarden|Milliarde|Billionen|Billion)"
    cur = r"€|\$|£|¥|EUR|USD|CHF|Euro|Dollar"
    text = re.sub(r"(?:(?P<p>[€$£¥])\s?)?" + NB + r"(?P<a>" + NUM + r")\s?" + scale
                  + r"(?:\s?(?P<u>" + cur + r")(?![\w]))?", _money_scaled, text)
    text = re.sub(r"([€$£¥])\s?" + NB + r"(" + NUM + r")(?:,-)?", lambda m: _money(m.group(2), m.group(1)), text)
    text = re.sub(NB + r"(" + NUM + r")(?:,-)?\s?(€|\$|£|¥|EUR|USD|CHF|Euro|Dollar)(?![\w])",
                  lambda m: _money(m.group(1), m.group(2)), text)
    text = re.sub(NB + r"(" + NUM + r")\s?(%|Prozent)", lambda m: f" {number(m.group(1))} Prozent ", text)
    text = re.sub(NB + r"(" + NUM + r")\s?‰", lambda m: f" {number(m.group(1))} Promille ", text)
    # times: "11:30 Uhr", "11.30 Uhr", "11:30", "7 Uhr"; scores: "2:1"
    text = re.sub(NB + r"(\d{1,2})[:.](\d{2})\s?(?:Uhr|h)\b", lambda m: _time(m.group(1), m.group(2)), text)
    text = re.sub(NB + r"(\d{1,2}):(\d{2})(?![\d:])", lambda m: _time(m.group(1), m.group(2)), text)
    text = re.sub(NB + r"(\d{1,2})\s?Uhr\b", lambda m: _time(m.group(1), "00"), text)
    text = re.sub(NB + r"(\d+):(\d+)" + NA, lambda m: f" {number(m.group(1))} zu {number(m.group(2))} ", text)

    def numeric_date(m):
        return _date(m.group(1), m.group(2), m.group(3), _ending(m.string[:m.start()])) or m.group(0)
    text = re.sub(NB + r"(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2}(?!\d))?" + NA, numeric_date, text)
    months = "|".join(MONTHS)
    text = re.sub(NB + r"(\d{1,2})\.\s?(" + months + r")\b",
                  lambda m: f" {ordinal(int(m.group(1)), _ending(m.string[:m.start()]))} {m.group(2)}", text)
    art = r"\b([Dd]er|[Dd]ie|[Dd]as|[Dd]en|[Dd]em|[Dd]es|[Aa]m|[Ii]m|[Zz]um|[Zz]ur|[Vv]om|[Bb]eim)\s+"
    text = re.sub(art + NB + r"(\d+)\.(?=\s)",
                  lambda m: f"{m.group(1)} {ordinal(int(m.group(2)), _ending(m.string[:m.start(2)]))}", text)
    text = re.sub(NB + r"(\d{2}|\d{4})er(?=\b|[a-zäöü])", lambda m: (year(int(m.group(1))) if len(m.group(1)) == 4
                                                                      else cardinal(int(m.group(1)))) + "er", text)
    text = re.sub(NB + r"(1[1-9]\d\d)(?![\d]|,\d|\.\d)", lambda m: f" {year(int(m.group(1)))} ", text)
    units = "|".join(re.escape(u) for u in sorted(UNITS, key=len, reverse=True))
    text = re.sub(NB + r"(" + NUM + r")\s?(" + units + r")(?![\w/²³])",
                  lambda m: f" {number(m.group(1), before_noun=True)} {UNITS[m.group(2)]} ", text)
    text = re.sub(r"(\d)\s*/\s*(\d)", r"\1 durch \2", text)
    text = re.sub(r"(\d)-(\d)", r"\1 bis \2", text)
    # numbers with thousands dots, then an English-style decimal point: "3.5" -> "drei Punkt fünf"
    text = re.sub(NB + r"(\d{1,3}(?:\.\d{3})+(?:,\d+)?)" + NA, lambda m: f" {number(m.group(1))} ", text)
    text = re.sub(NB + r"(\d+)\.(\d+)" + NA, lambda m: f" {number(m.group(1))} Punkt {digits(m.group(2))} ", text)
    text = re.sub(NB + r"(" + NUM + r")" + NA, lambda m: f" {number(m.group(1))} ", text)
    text = re.sub(r"\d", lambda m: f" {ONES[int(m.group(0))]} ", text)
    text = re.sub(r"\b(?:OK|Ok|ok)\b", "okay", text)
    text = re.sub(r"\b[A-ZÄÖÜ][A-ZÄÖÜa-z]{1,4}\b", lambda m: _capitals(m) if m.group(0)[1:].isupper()
                  or m.group(0) in LETTER_WORDS else m.group(0), text)
    text = re.sub(r"(?<=\w)/(?=\w)", " ", text)  # und/oder
    for symbol, spoken in SYMBOLS.items():
        text = text.replace(symbol, spoken)
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)
    return re.sub(r"\s+", " ", text).strip()
