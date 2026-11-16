"""English normalization: numbers, ordinals, years, money, percentages, times, units, common abbreviations, symbols
and letter-read acronyms are spelled out. Nothing is left unread: digits it cannot place are read one by one.

All-capital words are read as words (capitals often mean emphasis) unless they have no vowel or are known acronyms.
"""
import re
import unicodedata

ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
        "seventeen eighteen nineteen").split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()
SCALES = ("", "thousand", "million", "billion", "trillion")
ORDINAL = {"one": "first", "two": "second", "three": "third", "five": "fifth", "eight": "eighth", "nine": "ninth",
           "twelve": "twelfth"}

TYPOGRAPHY = str.maketrans({"’": "'", "‘": "'", "ʼ": "'", "´": "'", "`": "'", "“": '"', "”": '"', "„": '"',
                            "«": '"', "»": '"', "−": "-", "…": "...", " ": " "})
UNSAFE = re.compile("[\x00-\x08\x0b-\x1f\x7f-\x9f؜​-‏‪-‮⁦-⁩]")

ABBREVIATIONS = {"Mr.": "mister", "Mrs.": "missus", "Ms.": "miz", "Dr.": "doctor", "Prof.": "professor",
                 "Jr.": "junior", "Sr.": "senior", "Capt.": "captain", "Gen.": "general", "Lt.": "lieutenant",
                 "Sgt.": "sergeant", "Col.": "colonel", "Gov.": "governor", "Rev.": "reverend", "Mt.": "mount",
                 "Ave.": "avenue", "Rd.": "road", "Blvd.": "boulevard", "vs.": "versus", "etc.": "et cetera",
                 "e.g.": "for example", "i.e.": "that is", "approx.": "approximately", "Inc.": "incorporated",
                 "Ltd.": "limited", "Co.": "company", "Corp.": "corporation", "Dept.": "department",
                 "Jan.": "January", "Feb.": "February", "Aug.": "August", "Sept.": "September", "Oct.": "October",
                 "Nov.": "November", "Dec.": "December"}
LETTER_WORDS = frozenset("FBI CIA USA UK EU UN AI CEO CFO UFO IQ FAQ ETA IOU ID ATM API GPU CPU USB DIY NYC LA DJ "
                         "VIP RSVP ASAP AKA BBQ PE PA".split())
SPOKEN_CAPS = frozenset("SHH SHHH HMM HM MM MMM PSST BRR GRR TSK PFFT ZZZ HMPH".split())
UNITS = {"km": "kilometer", "m": "meter", "cm": "centimeter", "mm": "millimeter", "kg": "kilogram",
         "g": "gram", "mg": "milligram", "lb": "pound", "lbs": "pound", "oz": "ounce", "ft": "foot",
         "mi": "mile", "mph": "mile per hour", "km/h": "kilometer per hour", "kph": "kilometer per hour",
         "ml": "milliliter", "sec": "second", "min": "minute", "hr": "hour", "hrs": "hour", "ms": "millisecond",
         "kb": "kilobyte", "mb": "megabyte", "gb": "gigabyte", "tb": "terabyte", "hz": "hertz", "khz": "kilohertz",
         "°c": "degree celsius", "°f": "degree fahrenheit", "°": "degree"}
CURRENCY = {"$": ("dollar", "cent"), "£": ("pound", "penny"), "€": ("euro", "cent"), "¥": ("yen", "")}
IRREGULAR = {"foot": "feet", "penny": "pence", "yen": "yen", "hertz": "hertz", "kilohertz": "kilohertz"}
SYMBOLS = {"&": " and ", "@": " at ", "+": " plus ", "=": " equals ", "#": " number ", "%": " percent ",
           "°": " degrees ", "~": " about ", "*": " ", "_": " ", "|": " ", "\\": " ", "^": " ", "{": " ", "}": " ",
           "<": " ", ">": " ", "[": " ", "]": " "}

NUM = r"\d+(?:,\d{3})*(?:\.\d+)?"


def cardinal(n):
    if n < 0:
        return "minus " + cardinal(-n)
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        return ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + cardinal(n % 100))
    parts, scale = [], 0
    while n:
        n, chunk = divmod(n, 1000)
        if chunk:
            parts.append(cardinal(chunk) + (" " + SCALES[scale] if SCALES[scale] else ""))
        scale += 1
    return " ".join(reversed(parts))


def ordinal(n):
    words = cardinal(n).split()
    last = words[-1]
    words[-1] = ORDINAL.get(last) or (last[:-1] + "ieth" if last.endswith("y") else last + "th")
    return " ".join(words)


def year(n):
    if 2000 <= n <= 2009 or n % 1000 == 0:
        return cardinal(n)
    hi, lo = divmod(n, 100)
    if lo == 0:
        return cardinal(hi) + " hundred"
    return cardinal(hi) + (" oh " + ONES[lo] if lo < 10 else " " + cardinal(lo))


def digits(s):
    return " ".join(ONES[int(c)] for c in s if c.isdigit())


def number(s):
    """A written number read aloud; long or zero-led integers digit by digit."""
    whole, _, frac = s.replace(",", "").partition(".")
    whole = whole or "0"
    spoken = digits(whole) if len(whole) > 15 or (len(whole) > 1 and whole[0] == "0") else cardinal(int(whole))
    return spoken + (" point " + digits(frac) if frac else "")


def plural(word, amount):
    if amount.replace(",", "") in ("1", "1.0") or not word:
        return word
    head, _, tail = word.partition(" ")  # "mile per hour" -> "miles per hour"
    return IRREGULAR.get(head, head + "s") + (" " + tail if tail else "")


def _money(m):
    symbol, amount, scale = m.group(1), m.group(2), (m.group(3) or "").lower()
    unit, sub = CURRENCY[symbol]
    if scale:
        return f" {number(amount)} {scale} {plural(unit, '2')} "
    whole, _, frac = amount.replace(",", "").partition(".")
    cents = int(frac[:2].ljust(2, "0")) if frac and sub else 0
    parts = [] if whole in ("", "0") and cents else [f"{number(whole or '0')} {plural(unit, whole or '0')}"]
    if cents:
        parts.append(f"{cardinal(cents)} {plural(sub, str(cents))}")
    return " " + " ".join(parts) + " "


def _unit(m):
    amount, word = m.group(1), UNITS[m.group(2).lower()]
    head, _, tail = word.partition(" ")
    if head == "degree":
        return f" {number(amount)} {plural('degree', amount)} {tail} "
    return f" {number(amount)} {plural(word, amount)} "


def _time(m):
    h, mins, ampm = int(m.group(1)), int(m.group(2)), (m.group(3) or "").replace(".", "").lower()
    if h > 24 or mins > 59:
        return f" {cardinal(h)} {digits(m.group(2))} "
    if mins == 0:
        spoken = cardinal(h) + ("" if ampm else " o'clock")
    else:
        spoken = cardinal(h) + (" oh " + ONES[mins] if mins < 10 else " " + cardinal(mins))
    return f" {spoken}{' ' + ' '.join(ampm) if ampm else ''} "


def _year(m):
    spoken = year(int(m.group(1)))
    if m.group(2):  # decades: 1990s
        spoken = spoken[:-1] + "ies" if spoken.endswith("y") else spoken + "s"
    return f" {spoken} "


def _capitals(m):
    word = m.group(0)
    if word == "OK":
        return "okay"
    if word in LETTER_WORDS or (word not in SPOKEN_CAPS and not re.search("[AEIOUY]", word)):
        return " " + " ".join(word.lower()) + " "
    return word


def normalize(text):
    """Written English -> spoken English (still cased)."""
    text = UNSAFE.sub(" ", text.encode("utf-8", "ignore").decode("utf-8")).translate(TYPOGRAPHY)
    text = unicodedata.normalize("NFKC", text).replace("º", "°")
    text = re.sub(r"(\d)\s*[–—]\s*(\d)", r"\1 to \2", text)  # ranges: 1990–1995
    text = re.sub(r"\s*[–—]\s*", " - ", text)  # a dash between words is a pause
    text = re.sub(r"(?<![\w.])-(?=\d)", "minus ", text)
    for short, long in ABBREVIATIONS.items():
        text = re.sub(r"(?<![\w.])" + re.escape(short), long, text)
    text = re.sub(r"\bSt\.(?=\s+[A-Z])", "saint", text)
    text = re.sub(r"\bSt\.", "street", text)
    text = re.sub(r"\bNo\.\s*(?=\d)", "number ", text)
    text = re.sub(r"\b(?:[A-Za-z]\.){2,}", lambda m: " " + " ".join(m.group(0).replace(".", "").lower()) + " ", text)
    text = re.sub(r"([$£€¥])\s?(" + NUM + r")(?:\s?(thousand|million|billion|trillion)\b)?", _money, text, flags=re.I)
    text = re.sub(r"(" + NUM + r")\s?%", lambda m: f" {number(m.group(1))} percent ", text)
    text = re.sub(r"\b(\d{1,2}):(\d{2})\s?([ap]\.?m\.?)?(?![\w:])", _time, text, flags=re.I)
    text = re.sub(r"\b(\d+(?:,\d{3})*)(st|nd|rd|th)\b",
                  lambda m: f" {ordinal(int(m.group(1).replace(',', '')))} ", text, flags=re.I)
    text = re.sub(r"\b(1[1-9]\d\d|20\d\d)(s)?\b(?![.,]\d)", _year, text)
    units = "|".join(re.escape(u) for u in sorted(UNITS, key=len, reverse=True))
    text = re.sub(r"(" + NUM + r")\s?(" + units + r")(?![\w/])", _unit, text, flags=re.I)
    text = re.sub(r"(\d)\s*/\s*(\d)", r"\1 slash \2", text)
    text = re.sub(r"(\d)-(\d)", r"\1 to \2", text)
    text = re.sub(NUM, lambda m: f" {number(m.group(0))} ", text)
    text = re.sub(r"\b[A-Z]{2,5}\b", _capitals, text)
    text = re.sub(r"\b(?:ok|Ok)\b", "okay", text)
    text = re.sub(r"(?<=\w)/(?=\w)", " ", text)  # and/or
    for symbol, spoken in SYMBOLS.items():
        text = text.replace(symbol, spoken)
    text = re.sub(r"\s+([,.;:!?])", r"\1", text)  # expansions leave a space before punctuation
    return re.sub(r"\s+", " ", text).strip()
