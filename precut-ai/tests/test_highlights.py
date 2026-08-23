import math
import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.highlights import (  # noqa: E402
    build_candidates, pick_highlights, rms_per_bin, score_candidates,
)
from precut.plan import Segment  # noqa: E402


def make_pcm(levels, rate=1000, bin_seconds=1.0):
    """levels의 각 값(0~1)으로 1초짜리 일정 진폭 PCM을 만든다."""
    out = bytearray()
    per_bin = int(rate * bin_seconds)
    for level in levels:
        sample = int(level * 32767)
        out += struct.pack(f"<{per_bin}h", *([sample] * per_bin))
    return bytes(out)


class RmsTest(unittest.TestCase):
    def test_constant_levels(self):
        pcm = make_pcm([0.0, 0.5, 1.0])
        bins = rms_per_bin(pcm, rate=1000, bin_seconds=1.0)
        self.assertEqual(len(bins), 3)
        self.assertAlmostEqual(bins[0], 0.0, places=3)
        self.assertAlmostEqual(bins[1], 0.5, places=2)
        self.assertAlmostEqual(bins[2], 1.0, places=2)

    def test_empty(self):
        self.assertEqual(rms_per_bin(b""), [])

    def test_odd_byte_dropped(self):
        bins = rms_per_bin(b"\x00\x00\x01", rate=1, bin_seconds=1.0)
        self.assertEqual(len(bins), 1)


class CandidateTest(unittest.TestCase):
    def test_long_scene_is_split(self):
        cands = build_candidates([0.0, 20.0], min_len=0.8, max_len=8.0)
        self.assertEqual(len(cands), 3)
        self.assertAlmostEqual(cands[0][0], 0.0)
        self.assertAlmostEqual(cands[-1][1], 20.0)
        for s, e in cands:
            self.assertLessEqual(e - s, 8.0 + 1e-6)

    def test_short_scene_dropped(self):
        self.assertEqual(build_candidates([0.0, 0.5, 5.0], min_len=0.8), [(0.5, 5.0)])


class ScoreTest(unittest.TestCase):
    def test_loud_beats_quiet(self):
        energies = [0.1, 0.1, 0.9, 0.9]  # 0.5초 bin
        scored = score_candidates([(0.0, 1.0), (1.0, 2.0)], energies, bin_seconds=0.5)
        self.assertLess(scored[0].score, scored[1].score)


class PickTest(unittest.TestCase):
    def test_picks_top_until_target_in_time_order(self):
        scored = [
            Segment(0, 2, score=0.1),
            Segment(10, 12, score=0.9),
            Segment(20, 22, score=0.8),
            Segment(30, 32, score=0.05),
        ]
        picked = pick_highlights(scored, target=4)
        self.assertEqual([(s.start, s.end) for s in picked], [(10, 12), (20, 22)])
        self.assertEqual(picked[0].label, "하이라이트 1")

    def test_adjacent_merged(self):
        scored = [Segment(0, 2, score=0.9), Segment(2.1, 4, score=0.8)]
        picked = pick_highlights(scored, target=10)
        self.assertEqual(len(picked), 1)
        self.assertAlmostEqual(picked[0].end, 4.0)

    def test_zero_scores_spread(self):
        scored = [Segment(i * 2, i * 2 + 2, score=0.0) for i in range(10)]  # 20초 분량
        picked = pick_highlights(scored, target=6)
        self.assertGreaterEqual(sum(s.duration for s in picked), 6)
        self.assertGreater(picked[-1].start, picked[0].start)

    def test_empty(self):
        self.assertEqual(pick_highlights([], 10), [])


if __name__ == "__main__":
    unittest.main()
