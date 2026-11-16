"""The model on ONNX Runtime: the CPU by default, an NVIDIA GPU with device="cuda" (needs onnxruntime-gpu).

On the GPU the text keys and values stay on the device, and batches are padded up to a few shape buckets (exact: padded
letters and frames are masked out). One or two pieces are too little work to keep the GPU busy one operator at a time,
so their buckets get sessions with the sizes fixed: ONNX Runtime then folds the shape arithmetic away and runs the whole
step network as one CUDA graph, reading fixed GPU buffers. Bigger batches keep the GPU busy without it.
"""
from collections import OrderedDict
from pathlib import Path

import numpy as np
import onnxruntime as ort

GRAPH_BATCH = 2  # largest batch that runs as a CUDA graph
GRAPHS = 8  # captured step sessions kept, each with its own copy of the weights on the GPU


class OnnxBackend:
    def __init__(self, folder, threads=4, device="cpu"):
        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        # ARM's fastest 4-bit kernels quantize activations more coarsely than the packs were tuned for
        options.add_session_config_entry("mlas.disable_kleidiai", "1")
        providers = ["CPUExecutionProvider"]
        if device == "cuda":
            if "CUDAExecutionProvider" not in ort.get_available_providers():
                raise ValueError('device="cuda" needs onnxruntime-gpu: pip uninstall onnxruntime, then '
                                 'pip install "onnxruntime-gpu[cuda,cudnn]"')
            if hasattr(ort, "preload_dlls"):
                ort.preload_dlls()  # CUDA and cuDNN from the nvidia-* pip packages
            options.log_severity_level = 3  # it would warn about the shape arithmetic it leaves on the CPU
            providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
        elif device != "cpu":
            raise ValueError(f'unknown device {device!r}; choose "cpu" or "cuda"')
        self.device_loop = device == "cuda"  # Pipeline then calls denoise() for the whole step loop
        self._folder = Path(folder)
        self._text, self._step, self._vocoder = (
            ort.InferenceSession(str(self._folder / name), options, providers=providers)
            for name in ("text.onnx", "step.onnx", "vocoder.onnx"))
        self._buffers, self._graphs = {}, OrderedDict()

    def text(self, ids, speaker, language, dur_noise):
        if not self.device_loop:
            return self._text.run(["text_k", "text_v", "duration"], {
                "text_ids": ids, "speaker_id": speaker, "language_id": language, "dur_noise": dur_noise})
        # GPU: pad to the bucket and write the keys and values straight into the step's input buffers
        width = bucket(ids.shape[1], len(ids))
        ids = np.pad(ids, ((0, 0), (0, width - ids.shape[1])))
        buf = self._buffer("text", len(ids), width)
        io = self._text.io_binding()
        for name, value in (("text_ids", ids), ("speaker_id", speaker), ("language_id", language),
                            ("dur_noise", dur_noise)):
            io.bind_cpu_input(name, value)
        io.bind_ortvalue_output("text_k", buf["text_k"])
        io.bind_ortvalue_output("text_v", buf["text_v"])
        io.bind_output("duration")
        self._text.run_with_iobinding(io)
        return buf["text_k"], buf["text_v"], io.get_outputs()[2].numpy()

    def step(self, x, t, dt, k, v, text_mask, latent_mask, speaker, language):
        return self._step.run(["u"], {
            "x": x, "t": t, "dt": dt, "text_k": k, "text_v": v, "text_mask": text_mask, "latent_mask": latent_mask,
            "speaker_id": speaker, "language_id": language})[0]

    def denoise(self, x, steps, k, v, text_mask, latent_mask, speaker, language):
        """The step loop on the GPU: Pipeline's Euler update on the host, the network on GPU buffers."""
        batch, frames = x.shape[:2]
        width, length = k.shape()[3], bucket(frames, batch)
        buf = {**self._buffer("text", batch, width, text_k=k, text_v=v), **self._buffer("latent", batch, length)}
        buf["text_mask"].update_inplace(np.pad(text_mask, ((0, 0), (0, width - text_mask.shape[1]))))
        buf["latent_mask"].update_inplace(np.pad(latent_mask, ((0, 0), (0, length - frames))))
        buf["speaker_id"].update_inplace(speaker)
        buf["language_id"].update_inplace(language)
        dt = np.full(batch, 1.0 / steps, np.float32)
        buf["dt"].update_inplace(dt)
        session, io = self._session((batch, length, width), buf)
        x = np.pad(x, ((0, 0), (0, length - frames), (0, 0)))
        for s in range(steps):
            buf["x"].update_inplace(x)
            buf["t"].update_inplace(np.full(batch, s / steps, np.float32))
            session.run_with_iobinding(io)
            x = x + dt[:, None, None] * buf["u"].numpy()
        return x[:, :frames]

    def vocoder(self, latent):
        return self._vocoder.run(["audio"], {"latent": latent})[0]

    def _session(self, key, buffers):
        """-> (session, its binding to `buffers`): a captured one with the sizes of `key` for small batches."""
        if key[0] > GRAPH_BATCH:
            return self._step, _bind(self._step, buffers)
        if key not in self._graphs:
            options = ort.SessionOptions()
            options.log_severity_level = 3  # it would warn that the CPU fallback is unused
            for name, size in zip(("batch", "frames", "letters"), key):
                options.add_free_dimension_override_by_name(name, size)
            session = ort.InferenceSession(str(self._folder / "step.onnx"), options, providers=[
                ("CUDAExecutionProvider", {"enable_cuda_graph": "1"}), "CPUExecutionProvider"])
            self._graphs[key] = session, _bind(session, buffers)
            if len(self._graphs) > GRAPHS:
                self._graphs.popitem(last=False)
        self._graphs.move_to_end(key)
        return self._graphs[key]

    def _buffer(self, kind, batch, size, **given):
        """GPU buffers for the step's inputs, "text" (per letters) or "latent" (per frames), apart from those `given`.
        A captured graph reads fixed addresses, so small batches keep theirs; bigger ones get new ones from ONNX
        Runtime's pool."""
        key = (kind, batch, size)
        if key in self._buffers:
            return self._buffers[key]
        if kind == "text":
            layers, heads, _, dim = self._text.get_outputs()[0].shape[1:]
            shapes = {"text_k": (batch, layers, heads, size, dim), "text_v": (batch, layers, heads, size, dim),
                      "text_mask": (batch, size), "speaker_id": (batch,), "language_id": (batch,)}
        else:
            channels = self._step.get_inputs()[0].shape[2]
            shapes = {"x": (batch, size, channels), "u": (batch, size, channels), "latent_mask": (batch, size),
                      "t": (batch,), "dt": (batch,)}
        buf = {name: ort.OrtValue.ortvalue_from_shape_and_type(
            shape, np.int64 if name.endswith("_id") else np.float32, "cuda", 0)
            for name, shape in shapes.items() if name not in given}
        buf.update(given)
        if batch <= GRAPH_BATCH:
            self._buffers[key] = buf
        return buf


def bucket(n, batch):
    """Round a length up: for batches that run as CUDA graphs to a power of two from 128 (few sessions; one or two
    pieces cost about the same at twice the length), for bigger ones to 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, ...
    (a quarter of a doubling: little padded work)."""
    if batch <= GRAPH_BATCH:
        return max(128, 1 << int(n - 1).bit_length())
    if n <= 32:
        return 32
    step = (1 << (int(n - 1).bit_length() - 1)) // 4
    return -(-n // step) * step


def _bind(session, buffers):
    io = session.io_binding()
    for name, value in buffers.items():
        if name == "u":
            io.bind_ortvalue_output(name, value)
        else:
            io.bind_ortvalue_input(name, value)
    return io
