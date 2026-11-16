from etm_tts import text


def read(txt, lang, extra=frozenset()):
    return "".join(text.tokenize(txt, lang, extra))


def test_english_spells_out_numbers_and_abbreviations():
    assert read("Dr. Smith paid $12.50 on May 3rd, 2024.", "en") == \
        "doctor smith paid twelve dollars fifty cents on may third, twenty twenty four."


def test_german_spells_out_numbers():
    assert read("Am 3. Mai kostete es 12,50 €.", "de") == "am dritten mai kostete es zwölf euro fünfzig."


def test_markup_is_skipped_but_parentheses_are_read():
    assert read("Hello [laughs] there <happy>you</happy> (quietly) now.", "en") == "hello there you (quietly) now."


def test_accents_are_tokens_of_their_own():
    assert text.tokenize("Très", "fr", frozenset("̀")) == ["t", "r", "e", "̀", "s"]


def test_long_text_is_cut_at_sentence_ends():
    pieces = text.split(text.tokenize("This is a sentence that goes on. " * 40, "en"))
    assert len(pieces) > 1
    assert all(len(piece) <= text.MAX_LETTERS and piece[-1] == "." for piece, _ in pieces)
    assert [pause for _, pause in pieces] == [text.SENTENCE_PAUSE] * (len(pieces) - 1) + [0.0]
