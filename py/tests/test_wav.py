import xml.etree.ElementTree as ET

import numpy as np

from etm_tts import metadata, wav
from etm_tts.metadata import Label


def tone(seconds=1.0, rate=44100):
    return (0.5 * np.sin(2 * np.pi * 440 * np.arange(int(seconds * rate)) / rate)).astype(np.float32)


def test_label_is_written_where_tools_look(tmp_path):
    path = tmp_path / "speech.wav"
    wav.write(path, tone(), 44100, Label("Juliette", "de", 6, "onnx/quants/q4"))
    audio, rate, tags = wav.read(path)
    assert rate == 44100 and np.abs(audio - tone()).max() <= 1 / 32767
    assert tags["comment"].startswith(metadata.STATEMENT)
    xmp = tags["xmp"]
    ET.fromstring(xmp[xmp.index("<x:xmpmeta"):xmp.index("</x:xmpmeta>") + len("</x:xmpmeta>")])
    assert f'DigitalSourceType="{metadata.SOURCE_TYPE}"' in xmp
    assert 'AISystemVersionUsed="1.1 · 6 steps · onnx/quants/q4"' in xmp
    assert 'etm:Voice="Juliette"' in xmp and "<rdf:li>de</rdf:li>" in xmp


def test_unlabelled_file_has_no_tags(tmp_path):
    wav.write(tmp_path / "plain.wav", tone(), 44100)
    assert wav.read(tmp_path / "plain.wav")[2] == {}
