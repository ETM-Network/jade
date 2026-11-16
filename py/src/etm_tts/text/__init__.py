"""Text front end: written text in, the model's tokens out, split into pieces the model reads in one go.

English and German are normalized (numbers, dates, money, abbreviations spelled out) and read as plain letters. The
other languages are read as Unicode NFKD characters, so accents become tokens of their own; their digits are not read
yet. Text in [brackets] and <angle brackets> is treated as markup and skipped.
"""
import re
import unicodedata

from . import english, german

LETTERS = tuple(" !\"%&'(),-./:;?abcdefghijklmnopqrstuvwxyz")
GERMAN_LETTERS = ("ä", "ö", "ü", "ß")
NFKD_LANGUAGES = ("it", "pt", "el", "bg", "ru", "ja", "hi", "fr", "es", "pl", "lv", "nl", "cs", "sk", "sl", "hr", "hu",
                  "ro", "lt", "et", "da", "sv")
NO_SPACE_LANGUAGES = ("ja",)

_BEFORE_NFKD = str.maketrans({"´": "'", "΄": "'", "῾": "'", "᾿": "'", "～": "-", "˜": "-"})
_PUNCTUATION = str.maketrans({"。": ".", "｡": ".", "、": ",", "､": ",", "，": ",", "«": '"', "»": '"', "„": '"',
                              "“": '"', "”": '"', "‟": '"', "‘": "'", "’": "'", "‚": "'", "‛": "'", "ʼ": "'",
                              "´": "'", "`": "'", "‹": "'", "›": "'", "「": '"', "」": '"', "『": '"', "』": '"',
                              "〝": '"', "〞": '"', "—": "-", "–": "-", "‐": "-", "‑": "-", "−": "-", "―": "-",
                              "।": ".", "॥": ".", "·": ",", "‧": ",", "・": " ", "〜": "-", "～": "-", "؟": "?",
                              "،": ",", "؛": ";"})
_MARKUP = re.compile(r"\((?P<paren>[^()]*)\)|\[[^\[\]]*\]|<\s*bg\s*:\s*[^<>]+?\s*>|<\s*/\s*bg\s*>"
                     r"|<\s*/?\s*[^<>/\s][^<>/]*?\s*>")

LETTERS_PER_SECOND = 18.0
MAX_SECONDS = 20.0
MAX_LETTERS = 400
SENTENCE_PAUSE = 0.25
CLAUSE_PAUSE = 0.12
_CUTS = ((re.compile(r"[.!?]+[\"')]*(?= )"), SENTENCE_PAUSE), (re.compile(r"[,;:](?= )"), CLAUSE_PAUSE),
         (re.compile(r"\S(?= )"), CLAUSE_PAUSE))


def tokenize(text, lang, extra_letters=frozenset()):
    """Text -> the model's tokens (single characters). extra_letters: the model's non-English characters."""
    tokens = []

    def add(token):
        if token != " " or (tokens and tokens[-1] != " "):
            tokens.append(token)

    for segment in _segments(text):
        if segment[:1].isspace():
            add(" ")
        for ch in _letters(segment, lang, extra_letters):
            add(ch)
        if segment[-1:].isspace():
            add(" ")
    while tokens and tokens[-1] == " ":
        tokens.pop()
    return tokens


def split(tokens, speed=1.0, letters_per_second=LETTERS_PER_SECOND):
    """Tokens -> [(piece tokens, seconds of silence after it)], cut at sentence ends, else clauses, else words."""
    limit = int(min(MAX_LETTERS, letters_per_second * MAX_SECONDS * speed))
    pieces, rest = [], _strip(tokens)
    while rest:
        cut, pause = len(rest), 0.0
        if len(rest) > limit:
            cut, flat = limit, "".join(rest)
            for pattern, gap in _CUTS:
                ends = [m.end() for m in pattern.finditer(flat, 0, limit + 1)]
                if ends:
                    cut, pause = ends[-1], gap
                    break
        piece, rest = _strip(rest[:cut]), _strip(rest[cut:])
        if any(t.isalpha() for t in piece):
            pieces.append((_finish(piece), pause))
    if pieces:
        pieces[-1] = (pieces[-1][0], 0.0)
    return pieces


def _segments(text):
    """The text between markup; parentheses with words in them stay text."""
    out, pos = [], 0
    for m in _MARKUP.finditer(text):
        if m.group("paren") is not None and m.group("paren").strip():
            continue
        out.append(text[pos:m.start()])
        pos = m.end()
    out.append(text[pos:])
    return out


def _letters(text, lang, extra):
    if lang in NFKD_LANGUAGES:
        return _nfkd_letters(_clean(text), lang, extra).strip()
    if lang == "en":
        return _latin_letters(english.normalize(text), keep=()).strip()
    if lang == "de":
        return _latin_letters(german.normalize(text), keep=GERMAN_LETTERS).strip()
    raise ValueError(f"unsupported language {lang!r}")


def _latin_letters(text, keep):
    """Lowercase, strip accents (except those in `keep`), anything else unreadable becomes a space."""
    out = []
    for ch in unicodedata.normalize("NFC", text).lower().replace("ẞ", "ß") if keep else text.lower():
        if ch not in keep:
            ch = "".join(c for c in unicodedata.normalize("NFKD", ch) if not unicodedata.combining(c))
        out.append(ch if ch and all(c in LETTERS or c in keep for c in ch) else " ")
    return re.sub(r"\s+", " ", "".join(out))


def _nfkd_letters(text, lang, extra):
    s = unicodedata.normalize("NFKD", text.translate(_BEFORE_NFKD)).lower().translate(_PUNCTUATION)
    if lang == "el":
        s = s.replace(";", "?")  # the Greek question mark
    s = re.sub(r"\s+", " ", "".join(c if c in LETTERS or c in extra else " " for c in s))
    if lang in NO_SPACE_LANGUAGES:
        s = re.sub(r"([.!?,;:]+[\"')]*)(?=[^\s.!?,;:\"')])", r"\1 ", s)  # so the splitter can cut after punctuation
    return s


def _clean(text):
    # zero-width joiners shape Devanagari conjuncts: removed, not turned into spaces
    text = text.encode("utf-8", "ignore").decode("utf-8").replace("‌", "").replace("‍", "")
    return re.sub(r"\s+", " ", english.UNSAFE.sub(" ", text)).strip()


def _strip(tokens):
    a, b = 0, len(tokens)
    while a < b and tokens[a] == " ":
        a += 1
    while b > a and tokens[b - 1] == " ":
        b -= 1
    return tokens[a:b]


def _finish(piece):
    """Every piece ends like a sentence."""
    core = list(piece)
    while core and core[-1] in ("\"", "'", ")"):
        core.pop()
    if not core or core[-1] in (".", "!", "?"):
        return piece
    while piece and piece[-1] in (",", ";", ":", "-", " "):
        piece = piece[:-1]
    return piece + ["."] if piece else piece
