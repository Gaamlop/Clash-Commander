#!/usr/bin/env python3
"""Validate manually annotated Clash Royale replay decisions and score predictions.

This is an offline dataset/evaluation utility, NOT a neural-net trainer.
Annotate only videos owned by or explicitly authorized for the project.
"""

import argparse
import json
from pathlib import Path

DECKS = {
    "ice_bow": {"X-Bow", "Tesla", "Ice Wizard", "Knight", "Skeletons", "Rocket", "Tornado", "The Log"},
    "miner_control": {"Bats", "Knight", "Wall Breakers", "Spear Goblins", "Bomb Tower", "The Log", "Miner", "Poison"},
    "hypno_wb_bait": {"Cannon", "Dart Goblin", "Goblin Barrel", "Ice Spirit", "Princess", "Skeleton Army", "Valkyrie", "Wall Breakers"},
}


class ReplayError(ValueError):
    pass


def validate_action(action, example, *, expert=False):
    if not isinstance(action, dict):
        raise ReplayError("action must be an object")
    kind = action.get("action")
    if kind == "wait":
        if action.get("card") is not None or action.get("zone") is not None:
            raise ReplayError("wait must not contain card or zone")
    elif kind == "play":
        if not isinstance(action.get("card"), str) or not isinstance(action.get("zone"), str):
            raise ReplayError("play needs card and zone strings")
        if not action["card"].strip() or not action["zone"].strip():
            raise ReplayError("play card and zone must not be blank")
        # Predictions can be illegal: the benchmark must detect those mistakes.
        if expert and action["card"] not in example["hand"]:
            raise ReplayError("expert card must be in observed hand")
    else:
        raise ReplayError("action must be play or wait")


def validate_example(example):
    if not isinstance(example, dict):
        raise ReplayError("entry must be an object")
    if not isinstance(example.get("match_id"), str) or not example["match_id"].strip():
        raise ReplayError("match_id required")
    t = example.get("time_ms")
    if type(t) is not int or t < 0:
        raise ReplayError("time_ms must be a nonnegative integer")
    deck = example.get("deck")
    if deck not in DECKS:
        raise ReplayError("deck must be one of the three verified archetypes")
    hand = example.get("hand")
    if not isinstance(hand, list) or len(hand) != 4 or len(set(hand)) != 4:
        raise ReplayError("hand must contain four different cards")
    if not all(isinstance(card, str) and card in DECKS[deck] for card in hand):
        raise ReplayError("hand includes card outside verified deck")
    elixir = example.get("own_elixir")
    if elixir is not None and (type(elixir) is not int or not 0 <= elixir <= 10):
        raise ReplayError("own_elixir must be 0..10 or null when unknown")
    if example.get("source_permission") not in {"owner", "authorized"}:
        raise ReplayError("source_permission must be owner or authorized")
    if not isinstance(example.get("situation"), str) or not example["situation"].strip():
        raise ReplayError("situation must describe observed board state")
    validate_action(example.get("expert"), example, expert=True)
    if "prediction" in example:
        validate_action(example["prediction"], example)
    return example


def read_jsonl(path):
    examples, seen = [], set()
    with open(path, encoding="utf-8") as source:
        for line_no, line in enumerate(source, 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            try:
                example = validate_example(json.loads(line))
                key = (example["match_id"], example["time_ms"])
                if key in seen:
                    raise ReplayError("duplicate match_id/time_ms")
                seen.add(key)
                examples.append(example)
            except (ReplayError, ValueError, TypeError) as exc:
                raise ReplayError(f"{path}:{line_no}: {exc}") from exc
    if not examples:
        raise ReplayError("no valid annotated examples")
    return examples


def evaluate(examples):
    results = {}
    for deck in DECKS:
        items = [x for x in examples if x["deck"] == deck]
        predicted = [x for x in items if "prediction" in x]
        legal = sum(x["prediction"]["action"] == "wait" or
                    x["prediction"].get("card") in x["hand"] for x in predicted)
        action_match = sum(x["prediction"]["action"] == x["expert"]["action"] for x in predicted)
        card_match = sum(x["prediction"]["action"] == x["expert"]["action"] and
                         x["prediction"].get("card") == x["expert"].get("card") for x in predicted)
        exact = sum(x["prediction"] == x["expert"] for x in predicted)
        results[deck] = {
            "annotated": len(items),
            "predicted": len(predicted),
            "legal_predicted": legal,
            "action_match": action_match,
            "card_match": card_match,
            "exact_match": exact,
            "exact_accuracy": round(exact / len(predicted), 4) if predicted else None,
        }
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jsonl", type=Path, help="Authorized, manually labeled replay decisions")
    parser.add_argument("--json", action="store_true", help="Print evaluation in JSON")
    args = parser.parse_args()
    try:
        examples = read_jsonl(args.jsonl)
    except (OSError, ReplayError) as exc:
        parser.error(str(exc))
    score = evaluate(examples)
    if args.json:
        print(json.dumps(score, indent=2, ensure_ascii=False))
    else:
        print(f"Validated {len(examples)} authorized replay decision(s)")
        for deck, stats in score.items():
            print(f"{deck}: {stats['predicted']}/{stats['annotated']} scored; "
                  f"legal={stats['legal_predicted']}, exact={stats['exact_match']}, "
                  f"accuracy={stats['exact_accuracy']}")


if __name__ == "__main__":
    main()
