#!/usr/bin/env python3
"""Score labeled fresh-hand detections, without reading or uploading images."""
import argparse
import json
from collections import defaultdict
from pathlib import Path
from replay_eval import DECKS


def read_frames(path):
    seen = set()
    with open(path, encoding="utf-8") as stream:
        for line_no, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                deck = row["deck"]
                if deck not in DECKS or row.get("source_permission") not in ("owner", "authorized"):
                    raise ValueError("unsupported deck or missing permission")
                match = row["match_id"]
                timestamp = row["time_ms"]
                if not isinstance(match, str) or not match or type(timestamp) is not int or timestamp < 0:
                    raise ValueError("invalid match/time")
                key = (match, timestamp)
                if key in seen:
                    raise ValueError("duplicate frame")
                seen.add(key)
                truth, predicted = row["ground_truth"], row["predicted"]
                if not isinstance(truth, list) or not isinstance(predicted, list) or len(truth) != 4 or len(predicted) != 4:
                    raise ValueError("expected four slots per list")
                if any(type(card) is not str or card not in DECKS[deck] for card in truth) or len(set(truth)) != 4:
                    raise ValueError("ground truth must be four distinct deck cards")
                if any(card is not None and (type(card) is not str or card not in DECKS[deck]) for card in predicted):
                    raise ValueError("predicted cards must belong to selected deck or be null")
                if len([c for c in predicted if c is not None]) != len(set(c for c in predicted if c is not None)):
                    raise ValueError("duplicate predictions")
                yield row
            except (KeyError, TypeError, ValueError) as exc:
                raise ValueError(f"{path}:{line_no}: {exc}") from exc


def score(frames):
    counts = defaultdict(lambda: dict(frames=0, slots=0, correct=0, false_positive=0, missed=0, complete=0))
    for frame in frames:
        s = counts[frame["deck"]]
        s["frames"] += 1
        s["slots"] += 4
        correct_frame = True
        for expected, actual in zip(frame["ground_truth"], frame["predicted"]):
            if expected == actual:
                s["correct"] += 1
            else:
                correct_frame = False
                if actual is not None:
                    s["false_positive"] += 1
                s["missed"] += 1
        s["complete"] += int(correct_frame)
    result = {}
    for deck in DECKS:
        s = counts[deck]
        n = s["slots"]
        detected = s["correct"] + s["false_positive"]
        result[deck] = {**s,
            "slot_accuracy": round(s["correct"] / n, 4) if n else None,
            "precision": round(s["correct"] / detected, 4) if detected else None,
            "recall": round(s["correct"] / n, 4) if n else None,
            "full_hand_rate": round(s["complete"] / s["frames"], 4) if s["frames"] else None}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", type=Path, help="Private labeled frame observations (no screenshots)")
    args = parser.parse_args()
    try:
        frames = list(read_frames(args.jsonl))
        if not frames:
            raise ValueError("empty sample")
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    print(json.dumps(score(frames), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
