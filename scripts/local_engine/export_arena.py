"""Build the bundled detector from a pinned public checkpoint; no game connection.

Run in the isolated CPU environment in requirements.txt. The app never downloads it.
Original model: sacha9214/clash-ai; architecture/export: Ultralytics AGPL-3.0.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import urllib.request

os.environ.setdefault("YOLO_OFFLINE", "1")
# Keep checkpoint fusion identical on AVX2/AVX512 CI hosts before pinning the output hash.
os.environ.setdefault("ATEN_CPU_CAPABILITY", "default")
ROOT = Path(__file__).resolve().parents[2]
SOURCE_COMMIT = "093604e9a844d1032204f866bb76f9be8458bb87"
SOURCE_SHA = "b0c2afc3c55fbcfbaf467956f03e4feada10f203a44f57003ccfc545d8fd5379"
SOURCE_URL = f"https://raw.githubusercontent.com/sacha9214/clash-ai/{SOURCE_COMMIT}/models/yolo/clashai_yolo11s.pt"


def build(weights, output):
    import ast
    import numpy as np
    import onnx
    import onnxruntime as ort
    import torch
    from ultralytics import YOLO

    if not weights.exists():
        weights.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(SOURCE_URL, timeout=90) as source:
            weights.write_bytes(source.read())
    if hashlib.sha256(weights.read_bytes()).hexdigest() != SOURCE_SHA:
        raise ValueError("Source detector checksum mismatch")
    torch.set_num_threads(2)
    wrapper = YOLO(str(weights))
    exported = Path(wrapper.export(format="onnx", imgsz=640, opset=17, simplify=False,
                                  dynamic=False, batch=1, device="cpu"))
    model = onnx.load(exported)
    metadata = {p.key: p.value for p in model.metadata_props}
    names = ast.literal_eval(metadata["names"])
    labels = [names[i] for i in range(len(names))]
    expected = json.loads((ROOT / "app/src/main/assets/local_hypno/labels.json").read_text())
    if labels != expected or len(labels) != 393:
        raise ValueError("Detector class contract changed")
    # Remove export date/path metadata so the distributable carries only reproducible provenance.
    del model.metadata_props[:]
    for key, value in {"source_commit": SOURCE_COMMIT, "source_sha256": SOURCE_SHA,
                       "license": "AGPL-3.0", "input_contract": "RGB float32 BCHW 1x3x640x640; square letterbox 114"}.items():
        entry = model.metadata_props.add(); entry.key = key; entry.value = value
    onnx.checker.check_model(model)
    output.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, output)
    options = ort.SessionOptions(); options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(output), sess_options=options, providers=["CPUExecutionProvider"])
    if session.get_inputs()[0].shape != [1, 3, 640, 640] or session.get_outputs()[0].shape != [1, 397, 8400]:
        raise ValueError("Detector tensor dimensions changed")
    reference = YOLO(str(weights)).model.float().eval().fuse()
    errors = []
    from PIL import Image
    fixture = Image.open(ROOT / "app/src/main/assets/local_hypno/selftest.jpg").convert("RGB")
    w, h = fixture.size
    x, y, cw, ch = round(w*.020), round(h*.035), round(w*.960), round(h*.684)
    gain = min(640/cw, 640/ch); nw, nh = round(cw*gain), round(ch*gain)
    canvas = Image.new("RGB", (640,640), (114,114,114))
    canvas.paste(fixture.crop((x,y,x+cw,y+ch)).resize((nw,nh), Image.Resampling.BILINEAR), ((640-nw)//2,(640-nh)//2))
    real = np.asarray(canvas).transpose(2,0,1)[None].astype(np.float32)/255
    for image in [np.zeros((1, 3, 640, 640), np.float32),
                  np.random.default_rng(7).random((1, 3, 640, 640), dtype=np.float32), real]:
        with torch.no_grad():
            expected_output = reference(torch.from_numpy(image))[0].numpy()
        actual = session.run(None, {"images": image})[0]
        errors.append(float(np.max(np.abs(expected_output - actual))))
        # Coordinates are in 640px input space; scores must agree much more tightly.
        np.testing.assert_allclose(actual[:, :4], expected_output[:, :4], atol=.10, rtol=.001)
        np.testing.assert_allclose(actual[:, 4:], expected_output[:, 4:], atol=.0001, rtol=.001)
    best = actual[0,4:].argmax(0); scores = actual[0,4:].max(0)
    present = {labels[int(best[i])] for i in np.flatnonzero(scores > .5)}
    if not {"king-tower_0", "cannon_0"}.issubset(present):
        raise ValueError("Real-frame native self-test objects not detected")
    report = {"source_url": SOURCE_URL, "source_sha256": SOURCE_SHA,
              "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
              "classes": len(labels), "input": [1, 3, 640, 640], "output": [1, 397, 8400],
              "parity_max_abs_error": errors, "license": "AGPL-3.0"}
    output.with_suffix(".validation.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--weights", type=Path, default=ROOT / ".local-model-cache/arena-source.pt")
    parser.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/local_hypno/arena.onnx")
    args = parser.parse_args()
    build(args.weights, args.output)
