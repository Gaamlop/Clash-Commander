"""Standard-library integrity check; also executes on cache hits."""
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/main/assets/local_hypno"
contract = json.loads((ASSETS / "model_contract.json").read_text())
assert hashlib.sha256((ASSETS / "arena.onnx").read_bytes()).hexdigest() == contract["output_sha256"], "Bundled model checksum mismatch"
assert len(json.loads((ASSETS / "labels.json").read_text())) == contract["classes"] == 393
print("Local detector integrity verified; no provider connection required")
