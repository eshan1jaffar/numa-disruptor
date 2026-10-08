"""Unit tests of the analysis statistics. The records below are SYNTHETIC test fixtures that exist only to check arithmetic;
they are never plotted into documentation figures.   Run:  python -m unittest analysis/test_plot_results.py"""
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).parent))
import plot_results as pr  # noqa: E402


def rec(variant, locality, trial, eps, status="ok", wk="W", reason=None):
    r = {"id": f"{variant}-{locality}--{wk}", "trial": trial, "status": status, "workloadKey": wk,
         "config": {"variant": variant, "locality": locality, "ringHome": "PRODUCER", "offheap": False, "metric": "THROUGHPUT",
                    "partitions": 1}}
    if status == "ok":
        r["throughput"] = {"eventsPerSec": eps}
        r["metadata"] = {"gitCommit": "abc", "host": "h", "usableNodes": [0, 1]}
    if reason:
        r["reason"] = reason
    return r


class StatsTest(unittest.TestCase):
    def test_t_critical_values(self):
        self.assertAlmostEqual(pr.t_crit(2), 12.706)
        self.assertAlmostEqual(pr.t_crit(4), 3.182)
        self.assertAlmostEqual(pr.t_crit(31), 2.042)
        self.assertTrue(math.isnan(pr.t_crit(1)))
        self.assertTrue(2.131 > pr.t_crit(20) > 2.086)  # df=19 lies between table rows 15 and 20

    def test_mean_ci(self):
        m, sd, ci, n = pr.mean_ci(pd.Series([10.0, 12.0, 14.0]))
        self.assertEqual((m, n), (12.0, 3))
        self.assertAlmostEqual(sd, 2.0)
        self.assertAlmostEqual(ci, 4.303 * 2.0 / math.sqrt(3))
        self.assertTrue(math.isnan(pr.mean_ci(pd.Series([5.0]))[2]))

    def write(self, d: Path, records):
        (d / "results.jsonl").write_text("\n".join(json.dumps(r) for r in records) + "\n")

    def test_ratio_verdicts(self):
        with tempfile.TemporaryDirectory() as t:
            recs = [rec("V0", "OS", i, 100 + i) for i in range(5)] + [rec("V3", "AUTO", i, 200 + i) for i in range(5)] \
                + [rec("V1", "DENSE", i, [60, 140, 90, 120, 100][i]) for i in range(5)]
            self.write(Path(t), recs)
            df, _ = pr.load([t])
            s = pr.summarise(df[df["status"] == "ok"], "throughput.eventsPerSec")
            r = pr.ratio_table(s, {"variant": "V0", "locality": "OS"}, "V0/OS").set_index("variant")
            self.assertAlmostEqual(r.loc["V3", "ratio"], 202 / 102, places=6)
            self.assertEqual(r.loc["V3", "verdict"], "better")
            self.assertEqual(r.loc["V1", "verdict"], "inconclusive")  # noisy trials: interval contains 1
            self.assertEqual(r.loc["V0", "ratio"], 1.0)

    def test_single_trial_is_not_called_better(self):
        with tempfile.TemporaryDirectory() as t:
            self.write(Path(t), [rec("V0", "OS", 1, 100), rec("V3", "AUTO", 1, 500)])
            df, _ = pr.load([t])
            s = pr.summarise(df, "throughput.eventsPerSec")
            r = pr.ratio_table(s, {"variant": "V0", "locality": "OS"}, "V0/OS").set_index("variant")
            self.assertEqual(r.loc["V3", "verdict"], "n<2")

    def test_skipped_never_overrides_a_real_result_and_latest_wins(self):
        with tempfile.TemporaryDirectory() as t:
            self.write(Path(t), [rec("V0", "OS", 1, 100), rec("V0", "OS", 1, 0, status="skipped", reason="later allocation lacked nodes"),
                                 rec("V3", "AUTO", 1, 1, status="skipped", reason="x"), rec("V3", "AUTO", 1, 7)])
            df, stats = pr.load([t])
            by = {(r["config.variant"]): r for _, r in df.iterrows()}
            self.assertEqual(by["V0"]["status"], "ok")
            self.assertEqual(by["V3"]["status"], "ok")
            self.assertEqual(len(df), 2)
            self.assertEqual(stats["records"], 4)

    def test_invalid_json_is_reported_not_ignored(self):
        with tempfile.TemporaryDirectory() as t:
            (Path(t) / "results.jsonl").write_text('{"id": "x"\n')
            with self.assertRaises(SystemExit):
                pr.load([t])

    def test_single_node_warning_in_notes(self):
        df = pd.json_normalize([rec("V0", "OS", 1, 1) | {"metadata": {"usableNodes": [0], "gitCommit": "c", "host": "h"}}])
        self.assertIn("SINGLE NODE", pr.numa_note(df))
        df2 = pd.json_normalize([rec("V0", "OS", 1, 1)])
        self.assertNotIn("SINGLE NODE", pr.numa_note(df2))


if __name__ == "__main__":
    unittest.main()
