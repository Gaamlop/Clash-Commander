#!/usr/bin/env python3
"""Join private CARD-ML-FRAME Logcat predictions to manually labeled replay frames.

Labels JSONL fields: scan, deck, match_id, time_ms, source_permission,
ground_truth (four cards). Keep logs, labels and output OFF public GitHub.
Run: python3 scripts/join_hand_logcat.py --logcat local.log --labels labels.jsonl --output scored.jsonl
Then: python3 scripts/hand_slot_benchmark.py scored.jsonl
"""
import argparse
import json
from pathlib import Path

MARKER = "CARD-ML-FRAME "


def parse_logcat(lines):
    predictions = {}
    for line_no, line in enumerate(lines, 1):
        if MARKER not in line:
            continue
        try:
            event = json.loads(line.split(MARKER, 1)[1].strip())
            scan = event["scan"]
            cards = event["predicted"]
            if type(scan) is not int or scan < 1:
                raise ValueError("invalid scan")
            if not isinstance(cards, list) or len(cards) != 4:
                raise ValueError("expected four predictions")
            if any(card is not None and type(card) is not str for card in cards):
                raise ValueError("invalid prediction")
            if scan in predictions:
                raise ValueError("duplicate scan (multiple sessions or logs)")
            predictions[scan] = cards
        except (ValueError, KeyError, TypeError) as exc:
            raise ValueError(f"logcat line {line_no}: {exc}") from exc
    if not predictions:
        raise ValueError("no CARD-ML-FRAME observations")
    return predictions


def join_labels(lines, predictions):
    seen = set()
    joined = []
    for line_no, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            label = json.loads(line)
            scan = label.pop("scan")
            if type(scan) is not int or scan < 1 or scan in seen:
                raise ValueError("invalid or duplicate label scan")
            seen.add(scan)
            if "predicted" in label:
                raise ValueError("labels must not supply predictions")
            if scan not in predictions:
                raise ValueError("missing Logcat scan")
            row = {**label, "predicted": predictions[scan]}
            # Reuse the strict benchmark validator (deck, permissions, slots,
            # duplicate match/time, prediction vocabulary).
            joined.append(row)
        except (ValueError, KeyError, TypeError) as exc:
            raise ValueError(f"labels line {line_no}: {exc}") from exc
    if not joined:
        raise ValueError("no labeled frames")
    from hand_slot_benchmark import read_frames
    import tempfile
    with tempfile.TemporaryDirectory() as folder:
        path = Path(folder) / "private.jsonl"
        path.write_text("".join(json.dumps(row) + "\n" for row in joined), encoding="utf-8")
        list(read_frames(path))
    return joined


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--logcat", type=Path, required=True)
    parser.add_argument("--labels", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("output already exists; refusing to overwrite private labels")
    try:
        predictions = parse_logcat(args.logcat.read_text(encoding="utf-8").splitlines())
        rows = join_labels(args.labels.read_text(encoding="utf-8").splitlines(), predictions)
        with args.output.open("x", encoding="utf-8") as output:
            for row in rows:
                output.write(json.dumps(row, ensure_ascii=False) + "\n")
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(f"Joined {len(rows)} labeled frames locally. Run hand_slot_benchmark.py on output.")


if __name__ == "__main__":
    main()
