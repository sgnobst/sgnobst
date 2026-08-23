import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.scenes import parse_showinfo_times, scene_boundaries  # noqa: E402

SAMPLE = """
[Parsed_showinfo_1 @ 0x5] n:   0 pts:  90090 pts_time:3.003   duration_time:0.033367
[Parsed_showinfo_1 @ 0x5] n:   1 pts: 180180 pts_time:6.006   duration_time:0.033367
[Parsed_showinfo_1 @ 0x5] n:   2 pts: 270270 pts_time:9.009
"""


class ParseTest(unittest.TestCase):
    def test_parse(self):
        times = parse_showinfo_times(SAMPLE)
        self.assertEqual(len(times), 3)
        self.assertAlmostEqual(times[0], 3.003)
        self.assertAlmostEqual(times[2], 9.009)

    def test_dedupe_close(self):
        self.assertEqual(parse_showinfo_times("pts_time:1.00\npts_time:1.02\npts_time:2.0"),
                         [1.0, 2.0])

    def test_empty(self):
        self.assertEqual(parse_showinfo_times(""), [])


class BoundariesTest(unittest.TestCase):
    def test_includes_edges(self):
        self.assertEqual(scene_boundaries([3.0, 6.0], 10.0), [0.0, 3.0, 6.0, 10.0])

    def test_drops_near_edges(self):
        self.assertEqual(scene_boundaries([0.05, 9.99], 10.0), [0.0, 10.0])

    def test_no_scenes(self):
        self.assertEqual(scene_boundaries([], 5.0), [0.0, 5.0])


if __name__ == "__main__":
    unittest.main()
