import numpy as np

from etm_tts import watermark


def speechlike(seconds=8.0, seed=0):
    """Noise in syllable-like bursts: enough spectrum for the mark, no model needed."""
    t = np.arange(int(seconds * watermark.SAMPLE_RATE)) / watermark.SAMPLE_RATE
    bursts = np.clip(np.sin(2 * np.pi * 3.0 * t), 0.0, None) ** 2
    return (0.1 * np.random.default_rng(seed).standard_normal(len(t)) * bursts).astype(np.float32)


def test_marked_audio_is_detected():
    found = watermark.detect(watermark.apply(speechlike()))
    assert found.marked and found.model == watermark.MODEL


def test_unmarked_audio_is_not():
    for seed in range(5):
        assert not watermark.detect(speechlike(seed=seed)).marked


def test_pieces_marked_one_by_one_carry_one_mark():
    x = speechlike()
    cuts = [0, 30_000, 100_001, 200_000, len(x)]
    y = np.concatenate([watermark.apply(x[a:b], offset=a) for a, b in zip(cuts, cuts[1:])])
    assert watermark.detect(y).marked


def test_mark_survives_resampling():
    y = watermark.apply(speechlike())
    n = len(y) * 16000 // watermark.SAMPLE_RATE
    y16 = np.fft.irfft(np.fft.rfft(y)[:n // 2 + 1], n) * (n / len(y))
    assert watermark.detect(y16, 16000).marked


def test_mark_is_small_and_silence_stays_silent():
    x = speechlike()
    assert np.sqrt(np.mean((watermark.apply(x) - x) ** 2)) < 0.1 * np.sqrt(np.mean(x ** 2))
    assert not watermark.apply(np.zeros(watermark.SAMPLE_RATE, np.float32)).any()
