"""On-device text to speech in 23 languages."""
from .tts import TTS, Speech
from .watermark import Detection, detect

__version__ = "0.1.0"
__all__ = ["TTS", "Speech", "Detection", "detect"]
