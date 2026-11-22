#!/usr/bin/env python3
"""Synthetic tests for private Logcat-to-label joining; no user frames."""
import json
import unittest
from join_hand_logcat import parse_logcat, join_labels
from replay_eval import DECKS


class JoinHandLogcatTests(unittest.TestCase):
    def setUp(self):
        self.deck = next(iter(DECKS))
        self.cards = list(DECKS[self.deck])[:4]
        self.event = {"scan": 1, "predicted": self.cards, "top1": [None] * 4}
        self.label = {"scan": 1, "deck": self.deck, "match_id": "synthetic",
                      "time_ms": 1000, "source_permission": "owner",
                      "ground_truth": self.cards}

    def test_join_four_of_four(self):
        logs = parse_logcat(["I/ClashCompanion: CARD-ML-FRAME " + json.dumps(self.event)])
        rows = join_labels([json.dumps(self.label)], logs)
        self.assertEqual(rows[0]["predicted"], self.cards)
        self.assertNotIn("scan", rows[0])

    def test_unknown_slot_preserved(self):
        self.event["predicted"][2] = None
        logs = parse_logcat(["CARD-ML-FRAME " + json.dumps(self.event)])
        self.assertIsNone(join_labels([json.dumps(self.label)], logs)[0]["predicted"][2])

    def test_duplicate_scans_rejected(self):
        line = "CARD-ML-FRAME " + json.dumps(self.event)
        with self.assertRaisesRegex(ValueError, "duplicate scan"):
            parse_logcat([line, line])

    def test_missing_scan_rejected(self):
        with self.assertRaisesRegex(ValueError, "missing Logcat scan"):
            join_labels([json.dumps(self.label)], {2: self.cards})

    def test_unapproved_deck_rejected(self):
        self.label["deck"] = "other"
        with self.assertRaises(ValueError):
            join_labels([json.dumps(self.label)], {1: self.cards})

    def test_no_permission_rejected(self):
        self.label["source_permission"] = "unknown"
        with self.assertRaises(ValueError):
            join_labels([json.dumps(self.label)], {1: self.cards})

    def test_malformed_predictions_rejected(self):
        self.event["predicted"] = self.cards[:3]
        with self.assertRaisesRegex(ValueError, "four predictions"):
            parse_logcat(["CARD-ML-FRAME " + json.dumps(self.event)])


if __name__ == "__main__":
    unittest.main()
