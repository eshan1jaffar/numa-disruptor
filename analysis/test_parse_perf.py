"""Unit tests for parse_perf. The CSV strings are SYNTHETIC fixtures (hand-written, not real perf output).
Run:  python -m unittest analysis/test_parse_perf.py"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import parse_perf  # noqa: E402

SYNTHETIC = """# started on Mon Jan  1 00:00:00 2024

1000,,cycles,500000,100.00,,
200,,instructions,500000,100.00,0.20,insn per cycle
<not supported>,,node-loads,0,100.00,,
<not counted>,,node-stores,0,0.00,,
1.5,msec,task-clock,500000,100.00,,
"""


class ParsePerf(unittest.TestCase):
    def test_values_and_unsupported(self):
        d = parse_perf.parse(SYNTHETIC)
        self.assertEqual(d["cycles"], 1000)
        self.assertEqual(d["instructions"], 200)
        self.assertEqual(d["task-clock"], 1.5)
        self.assertIsNone(d["node-loads"])
        self.assertIsNone(d["node-stores"])
        self.assertEqual(len(d), 5)

    def test_empty(self):
        self.assertEqual(parse_perf.parse("# only a comment\n"), {})


if __name__ == "__main__":
    unittest.main()
