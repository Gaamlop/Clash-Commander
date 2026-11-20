"""Generate small grayscale descriptors and synthetic regression cases, not gameplay accuracy."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
from PIL import Image, ImageEnhance, ImageOps
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
CARDS = {"cannon": "Cannon", "dart-goblin": "Dart Goblin", "goblin-barrel": "Goblin Barrel",
         "goblin-barrel-ev1": "Goblin Barrel", "ice-spirit": "Ice Spirit", "princess": "Princess",
         "skeleton-army": "Skeleton Army", "skeleton-army-ev1": "Skeleton Army", "valkyrie": "Valkyrie",
         "valkyrie-ev1": "Valkyrie", "valkyrie-hero": "Valkyrie", "wall-breakers": "Wall Breakers"}


def describe(image):
    a = np.array(image.convert("RGB"), dtype=np.int64)
    h, w = a.shape[:2]
    gray = (a[:, :, 0] * 299 + a[:, :, 1] * 587 + a[:, :, 2] * 114) // 1000
    x0, x1, y0, y1 = int(w * .12), int(w * .88), int(h * .12), int(h * .86)
    values = []
    for row in range(28):
        top = y0 + row * (y1 - y0) // 28; bottom = max(top + 1, y0 + (row + 1) * (y1 - y0) // 28)
        for col in range(24):
            left = x0 + col * (x1 - x0) // 24; right = max(left + 1, x0 + (col + 1) * (x1 - x0) // 24)
            values.append(int(gray[top:bottom, left:right].mean()))
    return base64.b64encode(bytes(values)).decode()


def build(directory):
    refs, cases = [], []
    for slug, name in CARDS.items():
        path = directory / (slug + ".png")
        image = Image.open(path).convert("RGB")
        refs.append({"name": name, "variant": slug, "source": f"https://cdn.royaleapi.com/static/img/cards-150/{slug}.png",
                     "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "gray": describe(image)})
        for transform, candidate in [("resized", image.resize((177, 215))),
                                     ("dimmed", ImageEnhance.Brightness(image.resize((177, 215))).enhance(.50)),
                                     ("gray", ImageOps.grayscale(image.resize((177, 215))).convert("RGB"))]:
            cases.append({"variant": slug, "transform": transform, "expected": name, "gray": describe(candidate)})
    for slug in ["knight", "archers", "royal-giant"]:
        path = directory / (slug + ".png")
        if path.exists():
            cases.append({"variant": slug, "transform": "unrelated", "expected": None, "gray": describe(Image.open(path))})
    target = ROOT / "app/src/main/assets/local_hypno/portraits.json"
    target.write_text(json.dumps({"columns": 24, "rows": 28, "references": refs}, indent=2) + "\n")
    tests = ROOT / "app/src/test/resources/hypno-portrait-cases.json"
    tests.write_text(json.dumps({"references": refs, "cases": cases}, indent=2) + "\n")


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("directory", type=Path)
    build(p.parse_args().directory)
