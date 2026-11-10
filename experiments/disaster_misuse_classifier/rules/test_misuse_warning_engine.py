#!/usr/bin/env python3
"""Synthetic-only tests for the misuse warning rules engine."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from misuse_warning_engine import ConcernCategory, analyze_message


class MisuseWarningEngineTests(unittest.TestCase):
    def test_benign_shelter_instruction(self):
        r = analyze_message("Evacuate to the community shelter at the school gym now.")
        self.assertFalse(r.may_warrant_review)

    def test_benign_sympathy(self):
        r = analyze_message("Thinking of everyone affected by the flood.")
        self.assertFalse(r.may_warrant_review)

    def test_benign_traffic_diversion(self):
        r = analyze_message(
            "Traffic police have closed the bridge; use the alternate route via sector 9."
        )
        self.assertFalse(r.may_warrant_review)

    def test_direct_threat(self):
        r = analyze_message("I will kill you if you come near the supplies.")
        self.assertTrue(r.may_warrant_review)
        self.assertIn(ConcernCategory.POSSIBLE_THREAT, r.categories)

    def test_negated_threat(self):
        r = analyze_message("This is not a threat — stay safe everyone.")
        self.assertFalse(r.may_warrant_review)

    def test_report_of_looting_not_endorsement(self):
        r = analyze_message("Reports say looting happened near the market last night.")
        self.assertFalse(r.may_warrant_review)

    def test_report_with_endorsement(self):
        r = analyze_message("Unconfirmed reports — we should go loot too.")
        self.assertTrue(r.may_warrant_review)

    def test_debunk_warning(self):
        r = analyze_message("Don't share this hoax about fake NGOs — it's false rumor.")
        self.assertFalse(r.may_warrant_review)

    def test_denial_of_looting_rumor(self):
        r = analyze_message("Looting rumour is false; no looting in this district.")
        self.assertFalse(r.may_warrant_review)

    def test_coercion_pattern(self):
        r = analyze_message("Pay for rescue now or we will leave you behind.")
        self.assertTrue(r.may_warrant_review)
        self.assertIn(ConcernCategory.POSSIBLE_COERCION, r.categories)

    def test_impersonation_scam(self):
        r = analyze_message("Send money to this wallet — fake NGO relief fund.")
        self.assertTrue(r.may_warrant_review)
        self.assertIn(ConcernCategory.POSSIBLE_IMPERSONATION_FRAUD, r.categories)

    def test_quoted_threat_in_news_frame(self):
        r = analyze_message('RT @news: "kill them" was shouted according to witnesses unconfirmed')
        self.assertFalse(r.may_warrant_review)

    def test_user_message_no_accusation(self):
        r = analyze_message("Blackmail: pay or die before we help you.")
        self.assertIn("not proof", r.user_message.lower())
        self.assertNotIn("criminal", r.user_message.lower())
        self.assertNotIn("sender", r.user_message.lower())


if __name__ == "__main__":
    unittest.main()
