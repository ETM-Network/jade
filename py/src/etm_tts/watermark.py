"""An inaudible mark in every output, so the audio can be identified as machine-made (EU AI Act, Article 50).

Spectral magnitudes between 1 and 8 kHz are scaled by 1 ± 7 % following a pseudo-random pattern derived from a public
key, one key per model version. Each pattern value lasts two STFT frames and the pattern repeats every 32 of these
tiles. The pattern follows the absolute sample position, so audio marked piece by piece carries one continuous mark.
The mark survives common lossy codecs, resampling and cutting; `detect` finds it in about four seconds of speech.
"""
from dataclasses import dataclass
import hashlib

import numpy as np

from ._release import DEVELOPER, MODEL_NAME, MODEL_VERSION

SCHEME = "etm-dsp v1"
MODEL = f"{MODEL_NAME} {MODEL_VERSION}"
KEYS = {MODEL: f"{DEVELOPER} / {MODEL}"}
SAMPLE_RATE = 44100
NFFT, HOP = 2048, 1024
BAND = (1000.0, 8000.0)
TILE, PERIOD = 2, 32
STRENGTH = 0.07
THRESHOLD = 5.5

_WINDOW = 0.5 - 0.5 * np.cos(2 * np.pi * np.arange(NFFT) / NFFT)
_OVERLAP = _WINDOW[:HOP] ** 2 + _WINDOW[HOP:] ** 2
_FREQS = np.fft.rfftfreq(NFFT, 1 / SAMPLE_RATE)
_BAND = np.flatnonzero((_FREQS >= BAND[0]) & (_FREQS <= BAND[1]))


@dataclass(frozen=True)
class Detection:
    marked: bool
    score: float
    model: str | None


def label(model=MODEL):
    """How the mark is named in file metadata."""
    return f"{SCHEME}, key {model}"


def apply(audio, offset=0, model=MODEL):
    """Mark float32 mono audio at 44.1 kHz. offset: the position of its first sample in the stream it belongs to."""
    x = np.asarray(audio, np.float64)
    if len(x) < NFFT:
        return np.asarray(audio, np.float32)
    lead = offset % HOP
    padded = np.pad(x, (lead + HOP, NFFT))
    frames = np.lib.stride_tricks.sliding_window_view(padded, NFFT)[::HOP][:(lead + len(x)) // HOP + 2]
    spectrum = np.fft.rfft(frames * _WINDOW, axis=1)
    first = (offset - lead) // HOP
    tiles = (first + np.arange(len(frames))) // TILE % PERIOD
    delta = np.zeros_like(spectrum)
    delta[:, _BAND] = spectrum[:, _BAND] * (STRENGTH * _pattern(KEYS[model])[tiles])
    change = np.fft.irfft(delta, NFFT, axis=1) * _WINDOW
    added = np.zeros((len(frames) + 1, HOP))
    added[:-1] += change[:, :HOP]
    added[1:] += change[:, HOP:]
    added = added.ravel()[HOP + lead:HOP + lead + len(x)]
    return (x + added / np.resize(_OVERLAP, len(x))).astype(np.float32)


def detect(audio, sample_rate=SAMPLE_RATE):
    """Look for the mark of any known model. audio: mono or [samples, channels], at any sample rate."""
    x = np.asarray(audio, np.float64)
    if x.ndim > 1:
        x = x.mean(1)
    if sample_rate != SAMPLE_RATE:
        n = round(len(x) * SAMPLE_RATE / sample_rate)
        x = np.fft.irfft(np.fft.rfft(x), n) * (n / max(len(x), 1))
    scores = [(_score(_whitened(x[shift * HOP // 4:]), _pattern(key)), name)
              for shift in range(4) for name, key in KEYS.items()]
    score, model = max(scores)
    return Detection(score > THRESHOLD, score, model if score > THRESHOLD else None)


def _pattern(key):
    """±1 per tile and band bin: the bits of SHA-256("<key>/0"), SHA-256("<key>/1"), ... read most significant first."""
    n = PERIOD * len(_BAND)
    stream = b"".join(hashlib.sha256(f"{key}/{i}".encode()).digest() for i in range(-(-n // 256)))
    bits = np.unpackbits(np.frombuffer(stream, np.uint8))[:n]
    return (2.0 * bits - 1.0).reshape(PERIOD, len(_BAND))


def _whitened(x):
    """Band log magnitudes with the spectral envelope and slow level changes removed; quiet frames are zero."""
    if len(x) < NFFT:
        return np.zeros((0, len(_BAND)))
    frames = np.lib.stride_tricks.sliding_window_view(x, NFFT)[::HOP]
    magnitude = np.empty((len(frames), len(_BAND)))
    for i in range(0, len(frames), 512):
        magnitude[i:i + 512] = np.abs(np.fft.rfft(frames[i:i + 512] * _WINDOW, axis=1)[:, _BAND])
    level = magnitude.sum(1)
    log = np.log(magnitude + 1e-7)
    log -= _moving_mean(log, 15, axis=1)
    log -= _moving_mean(log, 4 * TILE + 1, axis=0)
    log[level <= level.max(initial=0.0) * 0.01] = 0.0
    return log


def _score(log, pattern):
    """Best normalized correlation over the pattern's phase and the tile offset; about N(0, 1) for unmarked audio."""
    best = -np.inf
    conj = np.conj(np.fft.fft(pattern, axis=0))
    for offset in range(TILE):
        n = (len(log) - offset) // TILE
        if n < 2:
            continue
        tiles = log[offset:offset + n * TILE].reshape(n, TILE, -1).mean(1)
        folded = np.zeros((PERIOD, tiles.shape[1]))
        np.add.at(folded, np.arange(n) % PERIOD, tiles)
        correlation = np.real(np.fft.ifft(np.fft.fft(folded, axis=0) * conj, axis=0)).sum(1)
        best = max(best, float(correlation.max() / (np.sqrt((tiles ** 2).sum()) + 1e-12)))
    return best


def _moving_mean(a, size, axis):
    half = size // 2
    a = np.moveaxis(a, axis, 0)
    total = np.cumsum(np.pad(a, [(half, half)] + [(0, 0)] * (a.ndim - 1), mode="symmetric"), axis=0)
    total = np.concatenate([np.zeros_like(total[:1]), total])
    return np.moveaxis((total[size:] - total[:-size]) / size, 0, axis)
