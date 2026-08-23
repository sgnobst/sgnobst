import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.silence import keep_intervals, parse_silencedetect  # noqa: E402

SAMPLE = """
[silencedetect @ 0x55d] silence_start: 2.0102
[silencedetect @ 0x55d] silence_end: 4.51 | silence_duration: 2.49978
[silencedetect @ 0x55d] silence_start: 7
[silencedetect @ 0x55d] silence_end: 8.25 | silence_duration: 1.25
size=N/A time=00:00:12.00 bitrate=N/A speed= 512x
"""


class ParseTest(unittest.TestCase):
    def test_pairs(self):
        silences = parse_silencedetect(SAMPLE)
        self.assertEqual(len(silences), 2)
        self.assertAlmostEqual(silences[0][0], 2.0102)
        self.assertAlmostEqual(silences[0][1], 4.51)
        self.assertAlmostEqual(silences[1][0], 7.0)

    def test_trailing_open_silence_closed_by_duration(self):
        text = SAMPLE + "[silencedetect @ 0x55d] silence_start: 10.5\n"
        silences = parse_silencedetect(text, duration=12.0)
        self.assertEqual(len(silences), 3)
        self.assertEqual(silences[-1], (10.5, 12.0))

    def test_trailing_open_without_duration_dropped(self):
        text = "[silencedetect] silence_start: 3.0\n"
        self.assertEqual(parse_silencedetect(text), [])

    def test_negative_start_clamped(self):
        text = "[x] silence_start: -0.01\n[x] silence_end: 1.5 | silence_duration: 1.51\n"
        self.assertEqual(parse_silencedetect(text), [(0.0, 1.5)])

    def test_empty(self):
        self.assertEqual(parse_silencedetect(""), [])


class KeepTest(unittest.TestCase):
    def test_keeps_with_padding(self):
        keeps = keep_intervals([(2, 4), (6, 8)], duration=10, pad=0.2, min_keep=0.3)
        self.assertEqual(len(keeps), 3)
        self.assertAlmostEqual(keeps[0][0], 0.0)
        self.assertAlmostEqual(keeps[0][1], 2.2)
        self.assertAlmostEqual(keeps[1][0], 3.8)
        self.assertAlmostEqual(keeps[1][1], 6.2)
        self.assertAlmostEqual(keeps[2][0], 7.8)

    def test_short_pieces_dropped(self):
        keeps = keep_intervals([(0.0, 4.9), (5.05, 10.0)], duration=10, pad=0.0, min_keep=0.3)
        self.assertEqual(keeps, [])

    def test_all_silent(self):
        self.assertEqual(keep_intervals([(0, 10)], duration=10, pad=0.1), [])

    def test_no_silence(self):
        self.assertEqual(keep_intervals([], duration=10, pad=0.1), [(0, 10)])


if __name__ == "__main__":
    unittest.main()
