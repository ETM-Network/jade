"""The synthesis pipeline shared by all backends: text -> pieces -> durations -> latents -> audio."""
from dataclasses import dataclass, field

import numpy as np

from . import text

BATCH = 8  # pieces per model call


@dataclass(eq=False)
class Piece:
    ids: np.ndarray
    pause: float
    seed: int
    lang: str
    frames: int = 0
    latents: np.ndarray = field(default=None, repr=False)


class Pipeline:
    """Runs a backend's three networks (text, step, vocoder) over a text, piece by piece."""

    def __init__(self, config, backend):
        self.config, self.backend = config, backend
        self.sample_rate = config["sample_rate"]
        self.frame = config["frame_samples"]
        self.latent_dim = config["latent_dim"]
        self.fps = self.sample_rate / self.frame
        self._ids = {t: i for i, t in enumerate(config["vocab"])}
        self._extra = frozenset(t for t in config["vocab"] if len(t) == 1 and t not in text.LETTERS)

    def pieces(self, txt, lang, speed=1.0, seed=0):
        tokens = text.tokenize(txt, lang, self._extra)
        out = []
        for i, (piece, pause) in enumerate(text.split(tokens, speed, self._pace(lang)[1])):
            ids = np.array([self._ids.get(t, 1) for t in piece], np.int64)
            out.append(Piece(ids, pause, (seed * 1_000_003 + i) % 2 ** 63, lang))
        return out

    def render(self, pieces, voice, steps, speed=1.0):
        """Yield (piece, audio) in order. voice: the speaker id. Batches grow 1, 2, 4, ... up to BATCH, so the first
        piece is ready quickly and each batch is done before the audio before it has played."""
        start, size = 0, 1
        while start < len(pieces):
            group = pieces[start:start + size]
            context = self._plan(group, voice, speed)
            self._think(group, context, voice, steps)
            yield from zip(group, self._decode(group))
            start, size = start + size, min(2 * size, BATCH)

    def _pace(self, lang):
        """(seconds per letter allowed, letters per second for splitting); English and German use the defaults."""
        lo, hi = self.config["guard_pace"]
        pace = self.config["pace"].get(lang)
        if lang in ("en", "de") or not pace:
            return (lo, hi), text.LETTERS_PER_SECOND
        scale = self.config["pace"]["en"]["rate"][0] / pace["rate"][0]
        return (lo * scale, hi * scale), pace["rate"][1]

    def _plan(self, group, voice, speed):
        width = max(len(p.ids) for p in group)
        ids = np.zeros((len(group), width), np.int64)
        for i, p in enumerate(group):
            ids[i, :len(p.ids)] = p.ids
        speaker = np.full(len(group), voice, np.int64)
        language = np.array([self.config["languages"].index(p.lang) for p in group], np.int64)
        k, v, duration = self.backend.text(ids, speaker, language, np.zeros(len(group), np.float32))
        for p, d in zip(group, duration):
            lo, hi = self._pace(p.lang)[0]
            d = min(max(d, len(p.ids) * lo * self.fps), len(p.ids) * hi * self.fps)
            p.frames = int(np.clip(np.round(np.float32(d) / np.float32(speed)), 1, self.config["max_frames"]))
        return k, v, (ids != 0).astype(np.float32), speaker, language

    def _think(self, group, context, voice, steps):
        k, v, text_mask, speaker, language = context
        x = np.zeros((len(group), max(p.frames for p in group), self.latent_dim), np.float32)
        latent_mask = np.zeros(x.shape[:2], np.float32)
        for i, p in enumerate(group):
            rng = np.random.default_rng(p.seed)
            rng.standard_normal(dtype=np.float32)  # reserved for duration sampling
            x[i, :p.frames] = rng.standard_normal((p.frames, self.latent_dim), dtype=np.float32)
            latent_mask[i, :p.frames] = 1.0
        if getattr(self.backend, "device_loop", False):  # the whole loop on the device
            x = self.backend.denoise(x, steps, k, v, text_mask, latent_mask, speaker, language)
        else:
            dt = np.full(len(group), 1.0 / steps, np.float32)
            for s in range(steps):
                t = np.full(len(group), s / steps, np.float32)
                x = x + dt[:, None, None] * self.backend.step(x, t, dt, k, v, text_mask, latent_mask, speaker,
                                                              language)
        for i, p in enumerate(group):
            p.latents = x[i, :p.frames]

    def _decode(self, group):
        latent = np.zeros((len(group), max(p.frames for p in group), self.latent_dim), np.float32)
        for i, p in enumerate(group):
            latent[i, :p.frames] = p.latents
        audio = self.backend.vocoder(latent)
        return [audio[i, :p.frames * self.frame] for i, p in enumerate(group)]
