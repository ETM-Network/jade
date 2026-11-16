"""The etm-tts command: speak, voices, detect."""
import argparse
import sys

from . import wav
from ._release import DEFAULT_VOICE


def main(argv=None):
    parser = argparse.ArgumentParser(prog="etm-tts", description="On-device text to speech.")
    commands = parser.add_subparsers(dest="command", required=True)

    speak = commands.add_parser("speak", help="read text aloud into a WAV file")
    speak.add_argument("text", help='the text, or "-" to read it from stdin')
    speak.add_argument("-o", "--output", default="speech.wav")
    speak.add_argument("--voice", default=DEFAULT_VOICE)
    speak.add_argument("--lang", default="en")
    speak.add_argument("--speed", type=float, default=1.0)
    speak.add_argument("--steps", type=int)
    speak.add_argument("--seed", type=int, default=0)
    _model_options(speak)

    voices = commands.add_parser("voices", help="list the voices and languages")
    _model_options(voices)

    detect = commands.add_parser("detect", help="check audio files for the watermark")
    detect.add_argument("files", nargs="+")

    args = parser.parse_args(argv)
    try:
        {"speak": _speak, "voices": _voices, "detect": _detect}[args.command](args)
    except ValueError as error:
        parser.exit(2, f"etm-tts: {error}\n")


def _model_options(parser):
    parser.add_argument("--variant", default="q8", choices=("q8", "q4", "q3", "raw"))
    parser.add_argument("--backend", default="auto", choices=("auto", "onnx", "mlx"))
    parser.add_argument("--model", metavar="FOLDER", help="a variant folder on disk instead of a download")
    parser.add_argument("--device", default="cpu", choices=("cpu", "cuda"), help="cuda needs onnxruntime-gpu")


def _load(args):
    from .tts import TTS
    return TTS(args.variant, args.backend, args.model, device=args.device)


def _speak(args):
    text = sys.stdin.read() if args.text == "-" else args.text
    speech = _load(args).speak(text, args.voice, args.lang, args.speed, args.steps, args.seed)
    speech.save(args.output)
    print(f"{args.output}: {speech.seconds:.1f} s, {args.voice}, {args.lang}")


def _voices(args):
    tts = _load(args)
    print("voices:   ", ", ".join(tts.voices))
    print("languages:", ", ".join(tts.languages))


def _detect(args):
    from .watermark import detect
    for path in args.files:
        audio, sample_rate = _read(path)
        found = detect(audio, sample_rate)
        verdict = f"marked by {found.model}" if found.marked else "no mark found"
        print(f"{path}: {verdict} (score {found.score:.1f})")


def _read(path):
    if path.lower().endswith(".wav"):
        return wav.read(path)[:2]
    try:
        import soundfile
    except ImportError:
        sys.exit(f"{path}: only WAV files can be read without the soundfile package (pip install soundfile)")
    return soundfile.read(path, dtype="float32")
