# etm-tts for Android

On-device text to speech with the Jade 1.1 model: 23 languages, 9 voices, 44.1 kHz, no network needed.
Runs on the CPU with ONNX Runtime; a mid-range phone speaks about four times faster than real time at the default settings.

## Install

Add the `tts` module to your project and depend on it:

```kotlin
// settings.gradle.kts
include(":tts")

// app/build.gradle.kts
dependencies { implementation(project(":tts")) }
```

Requires Android 8.0 (API 26) or newer.

## Use

```kotlin
val tts = Tts.load(modelDir)                                          // off the main thread
val speech = tts.speak("Hallo, wie geht's?", voice = "Juliette", lang = "de")
speech.saveWav(File(filesDir, "hallo.wav"))
tts.stream(text, voice = "Elias", lang = "en").collect { chunk -> track.write(chunk, 0, chunk.size, WRITE_BLOCKING) }
```

`stream` yields float chunks piece by piece, the first after about a second, so playback can start right away.

| option | default | |
|---|---|---|
| `voice` | `Juliette` | `tts.voices`: Henry, Molly, Sienna, Elias, Vivienne, Silas, Juliette, Gabriel, Willow |
| `lang` | `en` | `tts.languages`: ISO 639-1 codes |
| `steps` | 12 | 2 to 16; fewer is faster, more is steadier |
| `speed` | 1.0 | speaking rate |
| `threads` (load) | 4 | CPU threads |
| `kernels` (load) | `AUTO` | `ACCURATE` or `FAST` matrix kernels for 4-bit models |

Use q8 at 12 to 16 steps for long-form reading. q4 and q3 are smaller and faster, but over long passages they can
make pitch errors, especially at few steps.

## Models

The library does no downloading. Get a model folder from Hugging Face,
[ETM-Network/jade-1.1](https://huggingface.co/ETM-Network/jade-1.1), and put it on the device (for example
in `getExternalFilesDir(null)`):

| folder | size | |
|---|---|---|
| `onnx/quants/q8` | 150 MB | recommended |
| `onnx/quants/q4` | 81 MB | smaller and faster |
| `onnx/quants/q3` | 65 MB | smallest |
| `onnx/raw` | 437 MB | full precision |

```
hf download ETM-Network/jade-1.1 --include "onnx/quants/q8/*" --local-dir jade
adb push jade/onnx/quants/q8 /sdcard/Android/data/<your.app>/files/models/q8
```

The `sample` app shows a complete screen: load a folder, pick voice, language and steps, speak or save a WAV.
