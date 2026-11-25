import io
import json
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from jjhands import load, summarize


class HandSummaryTest(unittest.TestCase):
    def test_duplicate_pulls_unknown_results_and_redeals(self):
        def hand(index, role, bid, win, end="hand_gap", **extra):
            return dict(session="s", hand=index, role=role, bid={"level": bid},
                        win=win, end=end, **extra)

        records = [
            hand(1, "landlord", 3, True, multiplier=4.5),
            hand(2, "landlord_down", 0, False, multiplier=3, tracker_breaks={"missed_play": 1}),
            hand(3, "landlord", 3, None, failures={"rules_fallback": 2}),
            hand(4, None, 0, None, end="redeal"),
        ]
        out, err = io.StringIO(), io.StringIO()
        with tempfile.TemporaryDirectory() as folder, redirect_stdout(out), redirect_stderr(err):
            file = Path(folder, "day.jsonl")
            file.write_text("\n".join(json.dumps(r) for r in records) + "\n{")
            summarize(load([Path(folder), file]))
        self.assertIn("3 completed, 4 deals observed", out.getvalue())
        self.assertIn("Overall win rate: 50.0% (1/2, 1 unknown)", out.getvalue())
        self.assertIn("Landlord rate: 66.7% (2/3 known roles)", out.getvalue())
        self.assertIn("Win rate when bidding 3: 100.0% (1/1, 1 unknown)", out.getvalue())
        self.assertIn("Average multiplier: 3.75 (2/3 readable)", out.getvalue())
        self.assertIn("0=2, 1=0, 2=0, 3=2, unknown=0", out.getvalue())
        self.assertIn("with failures: 0.0% (0/1, 1 unknown)", out.getvalue())
        self.assertIn("without failures: 100.0% (1/1, 0 unknown)", out.getvalue())
        self.assertIn("skipped invalid record", err.getvalue())


if __name__ == "__main__":
    unittest.main()
