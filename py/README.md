# etm-tts

Text to speech that runs on your own device: nine voices, 23 languages, faster than real time on a CPU. It runs on ONNX Runtime anywhere and on MLX on Apple silicon.

```
pip install etm-tts          # on Apple silicon: pip install "etm-tts[mlx]"
```

```python
from etm_tts import TTS

tts = TTS()  # fetches the model once, about 150 MB
tts.speak("Hello! It's nice to finally meet you.").save("hello.wav")
tts.speak("Guten Morgen, wie geht es dir?", voice="Henry", lang="de").save("morgen.wav")
```

For long texts, `stream` yields audio piece by piece, the first one after about a second, so playback can start right away:

```python
for chunk in tts.stream(chapter, voice="Vivienne"):
    player.write(chunk)  # float32 samples at tts.sample_rate
```

From the command line:

```
etm-tts speak "Hello there." -o hello.wav --voice Gabriel
etm-tts voices
etm-tts detect hello.wav
```

## Voices

Female: Juliette (default), Vivienne, Sienna, Molly, Willow.
Male: Henry, Elias, Silas, Gabriel.

Every voice speaks every language.

## Languages

English `en`, German `de`, French `fr`, Spanish `es`, Italian `it`, Portuguese `pt`, Dutch `nl`, Danish `da`, Polish `pl`, Czech `cs`, Slovak `sk`, Slovenian `sl`, Croatian `hr`, Hungarian `hu`, Romanian `ro`, Bulgarian `bg`, Russian `ru`, Greek `el`, Latvian `lv`, Lithuanian `lt`, Estonian `et`, Hindi `hi`, Japanese `ja`.

English and German read numbers, dates and abbreviations as they're spoken. In the other languages, write numbers as words for now.

## Model variants

| variant | size | |
|---|---|---|
| `q8` | 150 MB | default; recommended for long-form reading |
| `q4` | 81 MB | smaller and faster |
| `q3` | 65 MB | smallest |
| `raw` | 437 MB | full precision |

Pick a variant with `TTS("q4")`, or use a copy on disk with `TTS(path="onnx/quants/q8")`. The model takes 12 steps by default; `speak(text, steps=16)` is steadier still, fewer steps are faster. q4 and q3 with few steps can make pitch errors over long passages, so use them when size or speed requires it.

On an NVIDIA GPU, install `onnxruntime-gpu` in place of `onnxruntime` and use `TTS(device="cuda")`. An RTX 4090 reads about 250 times faster than real time, 340 times on long texts.
