"""Minimal WAV reading and writing, so labels go exactly where other tools look for them: the comment in LIST/INFO/ICMT,
the XMP packet in a _PMX chunk."""
from pathlib import Path
import struct

import numpy as np


def write(path, audio, sample_rate, label=None):
    """Write mono 16-bit PCM, with the label's comment and XMP if one is given."""
    pcm = np.round(np.clip(audio, -1.0, 1.0) * 32767).astype("<i2").tobytes()
    chunks = [_chunk(b"fmt ", struct.pack("<HHIIHH", 1, 1, sample_rate, 2 * sample_rate, 2, 16))]
    if label is not None:
        chunks.append(_chunk(b"LIST", b"INFO" + _chunk(b"ICMT", label.comment().encode() + b"\0")))
        chunks.append(_chunk(b"_PMX", label.xmp().encode()))
    chunks.append(_chunk(b"data", pcm))
    body = b"WAVE" + b"".join(chunks)
    Path(path).write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)


def read(path):
    """-> (audio float32, [samples] or [samples, channels], sample_rate, {"comment": ..., "xmp": ...})."""
    data = Path(path).read_bytes()
    if data[:4] != b"RIFF" or data[8:12] != b"WAVE":
        raise ValueError(f"{path} is not a WAV file")
    chunks, tags = {}, {}
    for name, payload in _chunks(data[12:]):
        chunks.setdefault(name, payload)
        if name == b"LIST" and payload[:4] == b"INFO":
            info = dict(_chunks(payload[4:]))
            if b"ICMT" in info:
                tags["comment"] = info[b"ICMT"].rstrip(b"\0").decode("utf-8", "replace")
        elif name == b"_PMX":
            tags["xmp"] = payload.decode("utf-8", "replace")
    return (*_samples(chunks[b"fmt "], chunks[b"data"]), tags)


def _chunk(name, payload):
    return name + struct.pack("<I", len(payload)) + payload + b"\0" * (len(payload) % 2)


def _chunks(data):
    pos = 0
    while pos + 8 <= len(data):
        size = struct.unpack("<I", data[pos + 4:pos + 8])[0]
        yield data[pos:pos + 4], data[pos + 8:pos + 8 + size]
        pos += 8 + size + size % 2


def _samples(fmt, data):
    encoding, channels, sample_rate = struct.unpack("<HHI", fmt[:8])
    bits = struct.unpack("<H", fmt[14:16])[0]
    if encoding == 0xFFFE:
        encoding = struct.unpack("<H", fmt[24:26])[0]
    if encoding == 3 and bits in (32, 64):
        audio = np.frombuffer(data, f"<f{bits // 8}").astype(np.float32)
    elif encoding == 1 and bits == 24:
        raw = np.frombuffer(data[:len(data) // 3 * 3], np.uint8).reshape(-1, 3)
        audio = (raw[:, 0].astype(np.int32) << 8 | raw[:, 1].astype(np.int32) << 16
                 | raw[:, 2].astype(np.int32) << 24).astype(np.float32) / 2 ** 31
    elif encoding == 1 and bits in (8, 16, 32):
        kind = {8: "u1", 16: "<i2", 32: "<i4"}[bits]
        audio = np.frombuffer(data[:len(data) // (bits // 8) * (bits // 8)], kind).astype(np.float32)
        audio = (audio - 128) / 128 if bits == 8 else audio / 2 ** (bits - 1)
    else:
        raise ValueError(f"unsupported WAV encoding {encoding} with {bits} bits")
    audio = audio[:len(audio) // channels * channels]
    return (audio if channels == 1 else audio.reshape(-1, channels)), sample_rate
