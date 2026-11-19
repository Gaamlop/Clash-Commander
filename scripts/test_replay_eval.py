import json
import tempfile
import unittest
from pathlib import Path

from replay_eval import ReplayError, evaluate, read_jsonl, validate_example


BASE = {
    "match_id": "synthetic-test-match",
    "time_ms": 42000,
    "deck": "ice_bow",
    "hand": ["Tesla", "Knight", "Skeletons", "Rocket"],
    "own_elixir": 7,
    "situation": "Synthetic example: ground push approaching left princess tower",
    "source_permission": "owner",
    "expert": {"action": "play", "card": "Tesla", "zone": "center_defense"},
}


class ReplayEvalTest(unittest.TestCase):
    def test_valid_verified_hand(self):
        self.assertEqual(validate_example(BASE), BASE)

    def test_rejects_wrong_deck_and_duplicate_hand(self):
        with self.assertRaises(ReplayError):
            validate_example({**BASE, "hand": ["Tesla", "Tesla", "Knight", "Rocket"]})
        with self.assertRaises(ReplayError):
            validate_example({**BASE, "hand": ["Tesla", "Knight", "Skeletons", "Miner"]})

    def test_requires_explicit_rights(self):
        with self.assertRaises(ReplayError):
            validate_example({**BASE, "source_permission": "unknown"})

    def test_rejects_unobserved_expert_card_and_invalid_elixir(self):
        with self.assertRaises(ReplayError):
            validate_example({**BASE, "expert": {
                "action": "play", "card": "X-Bow", "zone": "left_bridge"
            }})
        with self.assertRaises(ReplayError):
            validate_example({**BASE, "own_elixir": 11})

    def test_predictions_can_be_wrong_and_are_scored(self):
        good = {**BASE, "prediction": BASE["expert"]}
        illegal = {**BASE, "time_ms": 45000, "prediction": {
            "action": "play", "card": "Miner", "zone": "left_bridge"
        }}
        scores = evaluate([good, illegal])
        self.assertEqual(scores["ice_bow"]["predicted"], 2)
        self.assertEqual(scores["ice_bow"]["legal_predicted"], 1)
        self.assertEqual(scores["ice_bow"]["exact_match"], 1)
        self.assertEqual(scores["ice_bow"]["exact_accuracy"], .5)
        self.assertIsNone(scores["miner_control"]["exact_accuracy"])

    def test_duplicate_timestamp_has_line_number(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "labels.jsonl"
            path.write_text(json.dumps(BASE) + "\n" + json.dumps(BASE) + "\n", encoding="utf-8")
            with self.assertRaisesRegex(ReplayError, r":2: duplicate match_id/time_ms"):
                read_jsonl(path)


if __name__ == "__main__":
    unittest.main()
