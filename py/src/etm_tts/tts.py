"""Text to speech: load a model, give it text, get watermarked audio."""
import json
import queue
import threading
from pathlib import Path

import numpy as np

from . import backends, hub, watermark, wav
from ._release import DEFAULT_VOICE
from .metadata import Label
from .pipeline import Pipeline


class Speech:
    """Synthesized audio: float32 samples at `sample_rate`, watermarked, and the label it is saved with."""

    def __init__(self, audio, sample_rate, label):
        self.audio, self.sample_rate, self.label = audio, sample_rate, label

    @property
    def seconds(self):
        return len(self.audio) / self.sample_rate

    def save(self, path):
        """Write a 16-bit WAV file labelled as AI-generated speech."""
        wav.write(path, self.audio, self.sample_rate, self.label)


class TTS:
    """A loaded model.

    TTS() fetches the q8 variant from Hugging Face on first use (about 150 MB) and runs it with MLX on Apple silicon,
    ONNX Runtime elsewhere. variant: "q8", "q4", "q3" or "raw"; q4 and q3 are smaller and faster but less steady over
    long passages. path: a variant folder on disk instead of a download; its format then decides the backend.
    device: "cpu", or "cuda" to run the ONNX files on an NVIDIA GPU (needs onnxruntime-gpu).
    """

    def __init__(self, variant="q8", backend="auto", path=None, threads=4, device="cpu"):
        if path is None:
            path = hub.download(backends.default() if backend == "auto" else backend, variant)
        folder = Path(path)
        self.config = json.loads((folder / "config.json").read_text(encoding="utf-8"))
        kind, variant = self.config["format"].removeprefix("etm-tts/"), self.config["model"]["variant"]
        self.variant = f"{kind}/raw" if variant == "raw" else f"{kind}/quants/{variant}"
        self.voices, self.languages = self.config["voices"], self.config["languages"]
        self.sample_rate, self.steps = self.config["sample_rate"], self.config["steps_default"]
        self._pipeline = Pipeline(self.config, backends.load(kind, folder, threads, device))
        # On the GPU, rendering runs ahead in a thread; the lock keeps it to one at a time however many streams are open
        self._lock = threading.Lock() if device == "cuda" else None

    def speak(self, text, voice=DEFAULT_VOICE, lang="en", speed=1.0, steps=None, seed=0):
        """Read `text` aloud. -> Speech.

        speed: 1.0 is the voice's natural pace. steps: 2 to 32, default 12; more is steadier and slower.
        seed: the same seed gives the same audio.
        """
        chunks = list(self.stream(text, voice, lang, speed, steps, seed))
        audio = np.concatenate(chunks) if chunks else np.zeros(0, np.float32)
        return Speech(audio, self.sample_rate, Label(voice, lang, steps or self.steps, self.variant))

    def stream(self, text, voice=DEFAULT_VOICE, lang="en", speed=1.0, steps=None, seed=0):
        """Like speak, but yields float32 audio piece by piece, each as soon as it is ready."""
        if voice not in self.voices:
            raise ValueError(f"unknown voice {voice!r}; choose one of {', '.join(self.voices)}")
        if lang not in self.languages:
            raise ValueError(f"unknown language {lang!r}; choose one of {', '.join(self.languages)}")
        pieces = self._pipeline.pieces(text, lang, speed, seed)
        rendered = self._pipeline.render(pieces, self.voices.index(voice), steps or self.steps, speed)
        if self._lock:
            rendered = _ahead(rendered, self._lock)
        return self._marked(rendered)

    def _marked(self, rendered):
        offset = 0
        for piece, audio in rendered:
            pause = np.zeros(round(piece.pause * self.sample_rate), np.float32)
            chunk = np.concatenate([watermark.apply(audio, offset), pause])
            offset += len(chunk)
            yield chunk


def _ahead(items, lock, size=16):
    """Iterate `items` in a worker thread that runs up to `size` items ahead, so the watermark of one piece overlaps
    the rendering of the next. Each item is made under `lock`; the worker stops when the consumer does."""
    out, stop = queue.Queue(size), threading.Event()

    def put(entry):
        while not stop.is_set():
            try:
                return out.put(entry, timeout=0.1)
            except queue.Full:
                pass

    def work():
        try:
            it = iter(items)
            while not stop.is_set():
                with lock:
                    item = next(it, out)  # `out` marks the end
                if item is out:
                    return put((False, None))
                put((True, item))
        except BaseException as error:  # re-raised in the consumer
            put((False, error))

    threading.Thread(target=work, daemon=True).start()
    try:
        while True:
            more, item = out.get()
            if not more:
                if item is not None:
                    raise item
                return
            yield item
    finally:
        stop.set()
