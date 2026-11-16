"""Needs a model: set ETM_TTS_MODEL to a variant folder, e.g. <download>/onnx/quants/q4."""
import os

import numpy as np
import pytest

from etm_tts import TTS, detect

MODEL = os.environ.get("ETM_TTS_MODEL")
pytestmark = pytest.mark.skipif(not MODEL, reason="ETM_TTS_MODEL is not set")
TEXT = "The morning was quiet. A light rain had fallen overnight, and the streets were still wet when we left the house."


@pytest.fixture(scope="module")
def tts():
    return TTS(path=MODEL)


def test_speech_is_marked_and_labelled(tts, tmp_path):
    speech = tts.speak(TEXT)
    assert 4.0 < speech.seconds < 15.0 and np.isfinite(speech.audio).all()
    assert detect(speech.audio).marked
    speech.save(tmp_path / "speech.wav")
    assert (tmp_path / "speech.wav").stat().st_size > len(speech.audio) * 2


def test_same_seed_same_audio_and_stream_matches_speak(tts):
    speech = tts.speak(TEXT, voice="Henry", seed=3)
    assert np.array_equal(speech.audio, tts.speak(TEXT, voice="Henry", seed=3).audio)
    assert np.array_equal(speech.audio, np.concatenate(list(tts.stream(TEXT, voice="Henry", seed=3))))


def test_unknown_voice_or_language_is_refused(tts):
    with pytest.raises(ValueError, match="Juliette"):
        tts.stream(TEXT, voice="Nobody")
    with pytest.raises(ValueError, match="en"):
        tts.stream(TEXT, lang="xx")
