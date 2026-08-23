import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.plan import (  # noqa: E402
    EditPlan, Segment, intersect_intervals, invert_intervals,
    merge_intervals, pad_intervals, segments_from_intervals,
)


class MergeTest(unittest.TestCase):
    def test_merge_overlapping(self):
        self.assertEqual(merge_intervals([(0, 2), (1, 3), (5, 6)]), [(0, 3), (5, 6)])

    def test_merge_with_gap(self):
        self.assertEqual(merge_intervals([(0, 1), (1.2, 2)], gap=0.3), [(0, 2)])
        self.assertEqual(merge_intervals([(0, 1), (1.5, 2)], gap=0.3), [(0, 1), (1.5, 2)])

    def test_merge_drops_empty(self):
        self.assertEqual(merge_intervals([(2, 2), (3, 1)]), [])

    def test_merge_unsorted(self):
        self.assertEqual(merge_intervals([(5, 6), (0, 1)]), [(0, 1), (5, 6)])


class InvertTest(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(invert_intervals([(2, 4), (6, 8)], 10), [(0, 2), (4, 6), (8, 10)])

    def test_touching_edges(self):
        self.assertEqual(invert_intervals([(0, 3), (7, 10)], 10), [(3, 7)])

    def test_empty(self):
        self.assertEqual(invert_intervals([], 5), [(0, 5)])

    def test_full_cover(self):
        self.assertEqual(invert_intervals([(0, 5)], 5), [])

    def test_out_of_range_clipped(self):
        self.assertEqual(invert_intervals([(-2, 1), (4, 99)], 5), [(1, 4)])


class PadTest(unittest.TestCase):
    def test_pad_and_clamp(self):
        self.assertEqual(pad_intervals([(1, 2)], 0.5, 10), [(0.5, 2.5)])
        self.assertEqual(pad_intervals([(0.2, 1)], 0.5, 10), [(0, 1.5)])

    def test_pad_merges(self):
        self.assertEqual(pad_intervals([(1, 2), (2.4, 3)], 0.3, 10), [(0.7, 3.3)])


class IntersectTest(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(
            intersect_intervals([(0, 5)], [(2, 3), (4, 9)]),
            [(2, 3), (4, 5)],
        )

    def test_disjoint(self):
        self.assertEqual(intersect_intervals([(0, 1)], [(2, 3)]), [])


class PlanTest(unittest.TestCase):
    def _plan(self):
        return EditPlan(
            source="/tmp/a.mp4", duration=100.0, fps=30.0, width=1920,
            height=1080, has_audio=True,
            segments=[Segment(10, 20, "장면 1"), Segment(40, 45, "장면 2")],
        )

    def test_edited_duration_and_offsets(self):
        plan = self._plan()
        self.assertAlmostEqual(plan.edited_duration(), 15.0)
        self.assertEqual(plan.offsets(), [0.0, 10.0])

    def test_roundtrip(self):
        plan = self._plan()
        again = EditPlan.from_dict(plan.to_dict())
        self.assertEqual(len(again.segments), 2)
        self.assertAlmostEqual(again.segments[1].start, 40.0)
        self.assertEqual(again.width, 1920)
        self.assertTrue(again.has_audio)

    def test_segments_from_intervals(self):
        segs = segments_from_intervals([(0, 1), (2, 3)])
        self.assertEqual([s.label for s in segs], ["장면 1", "장면 2"])


if __name__ == "__main__":
    unittest.main()
