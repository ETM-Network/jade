"""The networks in MLX, channels-last, matching the ONNX graphs:

    text     ids [B, L], speaker [B], language [B], dur_noise [B] -> text_k, text_v [B, layers, heads, L, dh], frames [B]
    step     x [B, T, 144], t [B], dt [B], text_k, text_v, masks, speaker, language -> u   (x <- x + dt * u)
    vocoder  latent [B, T, 144] -> audio [B, T * 3072]
"""
import json
import math
from pathlib import Path

import mlx.core as mx
import mlx.nn as nn
from mlx.utils import tree_map_with_path


def rope(pos, dim):
    inv = 1.0 / (10000.0 ** (mx.arange(dim // 2, dtype=mx.float32) / (dim // 2)))
    ang = pos[..., None].astype(mx.float32) * inv
    ang = mx.concatenate([ang, ang], -1)
    return mx.cos(ang), mx.sin(ang)


def apply_rope(x, cos, sin):
    a, b = mx.split(x, 2, axis=-1)
    return x * cos + mx.concatenate([-b, a], -1) * sin


def length_rope(n, length, dim, base, scale):
    """Rotary positions relative to each row's length (position i of `length` items is i / length)."""
    theta = scale * base ** (-mx.arange(dim // 2, dtype=mx.float32) / (dim // 2))
    ang = (mx.arange(n, dtype=mx.float32)[None] / length.astype(mx.float32)[:, None])[..., None] * theta
    ang = mx.concatenate([ang, ang], -1)[:, None]
    return mx.cos(ang), mx.sin(ang)


def attention(q, k, v, keymask):
    s = (q * (1.0 / math.sqrt(q.shape[-1]))) @ k.transpose(0, 1, 3, 2)
    s = mx.where(keymask[:, None, None, :], s, -mx.inf)
    return mx.softmax(s, axis=-1, precise=True) @ v


class TimestepEmbed(nn.Module):
    def __init__(self, d, freq=256):
        super().__init__()
        self.freq = freq
        self.fc1, self.fc2 = nn.Linear(freq, d), nn.Linear(d, d)

    def __call__(self, t):
        half = self.freq // 2
        a = t[:, None] * 1000.0 * mx.exp(-math.log(10000) * mx.arange(half, dtype=mx.float32) / half)[None]
        return self.fc2(nn.silu(self.fc1(mx.concatenate([mx.cos(a), mx.sin(a)], -1))))


class SwiGLU(nn.Module):
    def __init__(self, d, hidden):
        super().__init__()
        self.w1, self.w3 = nn.Linear(d, hidden, bias=False), nn.Linear(d, hidden, bias=False)
        self.w2 = nn.Linear(hidden, d, bias=False)

    def __call__(self, x):
        return self.w2(nn.silu(self.w1(x)) * self.w3(x))


class Attention(nn.Module):
    def __init__(self, d, heads):
        super().__init__()
        self.h, self.dh = heads, d // heads
        self.qkv, self.proj = nn.Linear(d, 3 * d, bias=False), nn.Linear(d, d, bias=False)

    def __call__(self, x, cos, sin, mask):
        B, T, D = x.shape
        q, k, v = (t.reshape(B, T, self.h, self.dh).transpose(0, 2, 1, 3) for t in mx.split(self.qkv(x), 3, axis=-1))
        o = attention(apply_rope(q, cos, sin), apply_rope(k, cos, sin), v, mask)
        return self.proj(o.transpose(0, 2, 1, 3).reshape(B, T, D))


class TextBlock(nn.Module):
    def __init__(self, d, heads, hidden):
        super().__init__()
        self.n1, self.n2 = nn.LayerNorm(d), nn.LayerNorm(d)
        self.attn, self.ff = Attention(d, heads), SwiGLU(d, hidden)

    def __call__(self, x, cos, sin, mask):
        x = x + self.attn(self.n1(x), cos, sin, mask)
        return x + self.ff(self.n2(x))


class DepthwiseConv(nn.Module):
    def __init__(self, c, k, dilation=1, padding=0):
        super().__init__()
        self.weight, self.bias = mx.zeros((c, k, 1)), mx.zeros((c,))
        self.dilation, self.padding = dilation, padding

    def __call__(self, x):
        return mx.conv1d(x, self.weight, padding=self.padding, dilation=self.dilation, groups=x.shape[-1]) + self.bias


class ConvNeXtBlock(nn.Module):
    def __init__(self, d):
        super().__init__()
        self.dw = DepthwiseConv(d, 7, padding=3)
        self.norm = nn.LayerNorm(d)
        self.pw1, self.pw2 = nn.Linear(d, 2 * d), nn.Linear(2 * d, d)
        self.gamma = mx.ones((d,))

    def __call__(self, x, mask):
        y = self.norm(self.dw(x * mask[..., None]))
        return x + self.gamma * self.pw2(nn.gelu(self.pw1(y)))


class TextEncoder(nn.Module):
    def __init__(self, a):
        super().__init__()
        d = a["d"]
        self.emb = nn.Embedding(a["n_vocab"], d)
        self.lang = nn.Embedding(a["n_languages"], d)
        self.style = nn.Embedding(1, d)
        self.conv = [ConvNeXtBlock(d) for _ in range(a["text_conv"])]
        self.attn = [TextBlock(d, a["text_heads"], a["text_ff_hidden"]) for _ in range(a["text_attn"])]
        self.norm = nn.LayerNorm(d)
        self.dh = d // a["text_heads"]

    def __call__(self, ids, lang):
        real = ids != 0
        x = self.emb(ids) + self.style.weight[0] + self.lang(lang)[:, None] * real[..., None].astype(mx.float32)
        m = real.astype(mx.float32)
        for blk in self.conv:
            x = blk(x, m)
        cos, sin = rope(mx.arange(ids.shape[1]), self.dh)
        for blk in self.attn:
            x = blk(x, cos, sin, real)
        return self.norm(x)


class Field(nn.Module):
    def __init__(self, i, h):
        super().__init__()
        self.fc0, self.fc1, self.fc2 = nn.Linear(i, h), nn.Linear(h, h), nn.Linear(h, 1)

    def __call__(self, x):
        return self.fc2(nn.silu(self.fc1(nn.silu(self.fc0(x)))))


class Duration(nn.Module):
    """A piece's length in frames."""

    def __init__(self, a):
        super().__init__()
        u = a["uttdur"]
        h = u["hidden"]
        self.dh, self.steps, self.mean, self.std = h // u["heads"], u["steps"], u["mean"], u["std"]
        self.proj = nn.Linear(a["d"], h)
        self.token = mx.zeros((1, 1, h))
        self.spk, self.lang = nn.Embedding(a["n_speakers"], h), nn.Embedding(a["n_languages"], h)
        self.blocks = [TextBlock(h, u["heads"], u["ff_hidden"]) for _ in range(u["layers"])]
        self.norm = nn.LayerNorm(h)
        self.t_embed = TimestepEmbed(h)
        self.field = Field(2 * h + 1, u["mlp"])

    def __call__(self, h, real, spk, lang, noise):
        B = h.shape[0]
        token = self.token + self.spk(spk)[:, None] + self.lang(lang)[:, None]
        x = mx.concatenate([token, self.proj(h)], 1)
        m = mx.concatenate([mx.ones((B, 1), dtype=mx.bool_), real], 1)
        cos, sin = rope(mx.arange(x.shape[1]), self.dh)
        mf = m.astype(mx.float32)[..., None]
        for blk in self.blocks:
            x = blk(x * mf, cos, sin, m)
        s = self.norm(x[:, 0])
        ts = [k / self.steps for k in range(self.steps + 1)]
        y = noise
        for k in range(self.steps):
            t = mx.full((B,), ts[k], dtype=mx.float32)
            v = self.field(mx.concatenate([s, self.t_embed(t), y[:, None]], -1))[:, 0]
            y = y + mx.array(ts[k + 1] - ts[k], dtype=mx.float32) * v
        return mx.exp(y * self.std + self.mean)


class CrossAttention(nn.Module):
    def __init__(self, d):
        super().__init__()
        self.q, self.kv, self.proj = nn.Linear(d, d, bias=False), nn.Linear(d, 2 * d, bias=False), \
            nn.Linear(d, d, bias=False)


class DiTBlock(nn.Module):
    def __init__(self, d, heads, hidden):
        super().__init__()
        self.n1 = nn.LayerNorm(d, eps=1e-6, affine=False)
        self.n2 = nn.LayerNorm(d, eps=1e-6, affine=False)
        self.nx = nn.LayerNorm(d, eps=1e-6)
        self.attn, self.ff, self.xattn = Attention(d, heads), SwiGLU(d, hidden), CrossAttention(d)
        self.ada_offset = mx.zeros((6, d))


class Model(nn.Module):
    """Text encoder, duration model and the step network (model.safetensors)."""

    def __init__(self, cfg):
        super().__init__()
        a = cfg["arch"]
        self.rope_base, self.rope_scale = float(a["rope_base"]), float(a["rope_scale"])
        d = a["d"]
        self.d, self.h, self.dh = d, a["heads"], d // a["heads"]
        self.text = TextEncoder(a)
        self.uttdur = Duration(a)
        self.spk, self.lang = nn.Embedding(a["n_speakers"], d), nn.Embedding(a["n_languages"], d)
        self.in_proj = nn.Linear(cfg["latent_dim"], d)
        self.t_embed, self.dt_embed = TimestepEmbed(d), TimestepEmbed(d)
        self.ada_shared = nn.Linear(d, 6 * d)
        self.blocks = [DiTBlock(d, a["heads"], a["ff_hidden"]) for _ in range(a["n_layers"])]
        self.norm_out = nn.LayerNorm(d, eps=1e-6, affine=False)
        self.ada_out = nn.Linear(d, 2 * d)
        self.out_proj = nn.Linear(d, cfg["latent_dim"])

    def text_graph(self, text_ids, speaker_id, language_id, dur_noise):
        real = text_ids != 0
        h = self.text(text_ids, language_id)
        duration = self.uttdur(h, real, speaker_id, language_id, dur_noise)
        B, L = text_ids.shape
        krot = length_rope(L, real.sum(-1), self.dh, self.rope_base, self.rope_scale)
        ks, vs = [], []
        for blk in self.blocks:
            k, v = (t.reshape(B, L, self.h, self.dh).transpose(0, 2, 1, 3) for t in mx.split(blk.xattn.kv(h), 2, -1))
            ks.append(apply_rope(k, *krot))
            vs.append(v)
        return mx.stack(ks, 1), mx.stack(vs, 1), duration

    def step(self, x, t, dt, text_k, text_v, text_mask, latent_mask, speaker_id, language_id):
        B, T, _ = x.shape
        D, H, dh = self.d, self.h, self.dh
        fmask, tmask = latent_mask > 0.5, text_mask > 0.5
        c = self.t_embed(t) + self.dt_embed(dt) + self.spk(speaker_id) + self.lang(language_id)
        bc = self.ada_shared(nn.silu(c)).reshape(B, 6, D)
        h = self.in_proj(x)
        cos, sin = rope(mx.arange(T), dh)
        qrot = length_rope(T, fmask.sum(-1), dh, self.rope_base, self.rope_scale)
        for i, blk in enumerate(self.blocks):
            p = bc + blk.ada_offset[None]
            sa, ga, aa, sf, gf, af = (p[:, j:j + 1] for j in range(6))
            h = h + aa * blk.attn(blk.n1(h) * (1 + ga) + sa, cos, sin, fmask)
            q = apply_rope(blk.xattn.q(blk.nx(h)).reshape(B, T, H, dh).transpose(0, 2, 1, 3), *qrot)
            o = attention(q, text_k[:, i], text_v[:, i], tmask)
            h = h + blk.xattn.proj(o.transpose(0, 2, 1, 3).reshape(B, T, D)) * latent_mask[..., None]
            h = h + af * blk.ff(blk.n2(h) * (1 + gf) + sf)
        s, g = mx.split(self.ada_out(nn.silu(c)), 2, -1)
        return self.out_proj(self.norm_out(h) * (1 + g[:, None]) + s[:, None])


class CausalUnfold(nn.Module):
    """A causal convolution (replicated left edge) as one Linear over k shifted frames."""

    def __init__(self, cin, cout, k, bias=True):
        super().__init__()
        self.k = k
        self.lin = nn.Linear(k * cin, cout, bias=bias)

    def __call__(self, x):
        T = x.shape[1]
        xp = mx.concatenate([mx.repeat(x[:, :1], self.k - 1, axis=1), x], 1)
        return self.lin(mx.concatenate([xp[:, j:j + T] for j in range(self.k)], -1))


class VocoderBlock(nn.Module):
    def __init__(self, c, hidden, dilation):
        super().__init__()
        self.pad = 6 * dilation
        self.dw = DepthwiseConv(c, 7, dilation=dilation)
        self.norm = nn.LayerNorm(c, eps=1e-6)
        self.pw1, self.pw2 = nn.Linear(c, hidden), nn.Linear(hidden, c)
        self.gamma = mx.zeros((c,))

    def __call__(self, x):
        y = mx.concatenate([mx.repeat(x[:, :1], self.pad, axis=1), x], 1)
        y = self.norm(self.dw(y))
        return x + self.gamma * self.pw2(nn.gelu(self.pw1(y)))


class Vocoder(nn.Module):
    def __init__(self, cfg):
        super().__init__()
        v = cfg["arch"]["vocoder"]
        c = v["channels"]
        self.scale, self.shift = mx.zeros((cfg["latent_dim"],)), mx.zeros((cfg["latent_dim"],))
        self.embed = CausalUnfold(cfg["latent_dim"] // 6, c, v["embed_kernel"])
        self.blocks = [VocoderBlock(c, v["hidden"], dl) for dl in v["dilations"]]
        self.bn_scale, self.bn_shift = mx.zeros((c,)), mx.zeros((c,))
        self.head1 = CausalUnfold(c, v["head_hidden"], v["head_kernel"])
        self.prelu = mx.array(0.25)
        self.head2 = nn.Linear(v["head_hidden"], v["out"], bias=False)

    def __call__(self, latent):
        B, T, C = latent.shape
        z = latent * self.scale + self.shift
        z = z.reshape(B, T, C // 6, 6).transpose(0, 1, 3, 2).reshape(B, T * 6, C // 6)
        h = self.embed(z)
        for blk in self.blocks:
            h = blk(h)
        h = self.head1(h * self.bn_scale + self.bn_shift)
        h = self.head2(mx.where(h >= 0, h, h * self.prelu))
        return h.reshape(B, -1)


def load(folder):
    """-> (Model, Vocoder) from an mlx/raw or mlx/quants/* folder. Layers listed in quant.json become quantized
    layers before loading; tensors stored in fp16 are computed in fp32."""
    folder = Path(folder)
    cfg = json.loads((folder / "config.json").read_text(encoding="utf-8"))
    quant_file = folder / "quant.json"
    quant = json.loads(quant_file.read_text(encoding="utf-8")) if quant_file.exists() else {}
    group_size = quant.get("group_size", 32)
    loaded = []
    for module, key, file in ((Model(cfg), "model", "model.safetensors"),
                              (Vocoder(cfg), "vocoder", "vocoder.safetensors")):
        bits = quant.get(key, {})
        groups = quant.get("groups" if key == "model" else "vocoder_groups", {})
        if bits:
            nn.quantize(module, class_predicate=lambda p, m, b=bits, g=groups: (
                {"group_size": g.get(p, group_size), "bits": b[p], "mode": "affine"}
                if p in b and isinstance(m, (nn.Linear, nn.Embedding)) else False))
        module.load_weights(str(folder / file), strict=True)
        module.update(tree_map_with_path(
            lambda path, x: x.astype(mx.float32) if x.dtype in (mx.float16, mx.bfloat16)
            and not path.endswith((".scales", ".biases")) else x, module.parameters()))
        mx.eval(module.parameters())
        loaded.append(module)
    return loaded[0], loaded[1]
