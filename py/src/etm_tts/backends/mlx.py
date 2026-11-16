"""The model on MLX (Apple silicon GPU). Quantized folders need the GPU: MLX's CPU path is slow for them."""
import numpy as np
import mlx.core as mx

from .mlx_model import load


class MlxBackend:
    def __init__(self, folder):
        self.model, self.voc = load(folder)

    def text(self, ids, speaker, language, dur_noise):
        k, v, d = self.model.text_graph(mx.array(ids.astype(np.int32)), mx.array(speaker.astype(np.int32)),
                                        mx.array(language.astype(np.int32)), mx.array(dur_noise))
        mx.eval(k, v, d)
        return np.array(k), np.array(v), np.array(d)

    def step(self, x, t, dt, k, v, text_mask, latent_mask, speaker, language):
        u = self.model.step(mx.array(x), mx.array(t), mx.array(dt), mx.array(k), mx.array(v), mx.array(text_mask),
                            mx.array(latent_mask), mx.array(speaker.astype(np.int32)),
                            mx.array(language.astype(np.int32)))
        mx.eval(u)
        return np.array(u)

    def vocoder(self, latent):
        audio = self.voc(mx.array(latent))
        mx.eval(audio)
        return np.array(audio)
