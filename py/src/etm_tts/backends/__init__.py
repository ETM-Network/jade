"""Where the networks run. Each backend offers the same three calls: text, step and vocoder."""
import importlib.util
import platform


def default():
    """MLX on Apple silicon when it is installed, ONNX Runtime everywhere else."""
    apple = platform.system() == "Darwin" and platform.machine() == "arm64"
    return "mlx" if apple and importlib.util.find_spec("mlx") else "onnx"


def load(name, folder, threads=4, device="cpu"):
    if name == "onnx":
        from .onnx import OnnxBackend
        return OnnxBackend(folder, threads, device)
    if name == "mlx":
        if device != "cpu":
            raise ValueError("device applies to ONNX Runtime; MLX already runs on the Apple GPU")
        from .mlx import MlxBackend
        return MlxBackend(folder)
    raise ValueError(f"unknown backend {name!r}; choose onnx or mlx")
