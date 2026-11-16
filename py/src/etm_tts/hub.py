"""Model downloads from Hugging Face. Only the requested variant's folder is fetched, and it is cached for later runs."""
from pathlib import Path

from ._release import HF_REPO

VARIANTS = ("raw", "q8", "q4", "q3")


def download(backend, variant):
    """-> the local folder of `variant` ("raw", "q8", "q4" or "q3") for `backend` ("onnx" or "mlx")."""
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant {variant!r}; choose one of {', '.join(VARIANTS)}")
    from huggingface_hub import snapshot_download
    folder = f"{backend}/raw" if variant == "raw" else f"{backend}/quants/{variant}"
    return Path(snapshot_download(HF_REPO, allow_patterns=[f"{folder}/*"])) / folder
