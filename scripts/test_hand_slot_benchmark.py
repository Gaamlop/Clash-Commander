import json
import tempfile
import unittest
from pathlib import Path
from hand_slot_benchmark import read_frames, score

BASE = {"deck": "ice_bow", "match_id": "sample-match", "time_ms": 1000,
        "source_permission": "owner",
        "ground_truth": ["X-Bow", "Tesla", "Ice Wizard", "Knight"],
        "predicted": ["X-Bow", "Tesla", None, "Rocket"]}


class HandSlotBenchmarkTest(unittest.TestCase):
    def test_counts_wrong_card_and_unknown_separately(self):
        stats = score([BASE])["ice_bow"]
        self.assertEqual((stats["correct"], stats["false_positive"], stats["missed"], stats["complete"]), (2, 1, 2, 0))
        self.assertEqual(stats["precision"], 0.6667)
        self.assertEqual(stats["recall"], 0.5)

    def test_exact_four_of_four(self):
        row = {**BASE, "predicted": BASE["ground_truth"]}
        self.assertEqual(score([row])["ice_bow"]["full_hand_rate"], 1.0)

    def test_no_data_does_not_claim_accuracy(self):
        self.assertIsNone(score([])["miner_control"]["precision"])

    def test_duplicate_frame_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "frames.jsonl"
            p.write_text(json.dumps(BASE) + "\n" + json.dumps(BASE) + "\n")
            with self.assertRaisesRegex(ValueError, "duplicate frame"):
                list(read_frames(p))

    def test_reject_other_deck_card_and_unapproved_source(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "frames.jsonl"
            p.write_text(json.dumps({**BASE, "predicted": ["Miner", None, None, None]}) + "\n")
            with self.assertRaisesRegex(ValueError, "predicted cards"):
                list(read_frames(p))
            p.write_text(json.dumps({**BASE, "source_permission": "unknown"}) + "\n")
            with self.assertRaisesRegex(ValueError, "permission"):
                list(read_frames(p))


if __name__ == "__main__":
    unittest.main()
