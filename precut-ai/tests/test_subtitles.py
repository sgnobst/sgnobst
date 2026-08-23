import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.subtitles import Caption, format_srt_time, remap_captions, to_srt  # noqa: E402


class TimeFormatTest(unittest.TestCase):
    def test_basic(self):
        self.assertEqual(format_srt_time(0), "00:00:00,000")
        self.assertEqual(format_srt_time(61.5), "00:01:01,500")
        self.assertEqual(format_srt_time(3661.007), "01:01:01,007")

    def test_rounding_carries(self):
        self.assertEqual(format_srt_time(59.9996), "00:01:00,000")

    def test_negative_clamped(self):
        self.assertEqual(format_srt_time(-1), "00:00:00,000")


class SrtTest(unittest.TestCase):
    def test_block_format(self):
        srt = to_srt([Caption(0, 1.5, "안녕하세요"), Caption(2, 3, "반갑습니다")])
        blocks = srt.strip().split("\n\n")
        self.assertEqual(len(blocks), 2)
        self.assertEqual(
            blocks[0].splitlines(),
            ["1", "00:00:00,000 --> 00:00:01,500", "안녕하세요"],
        )
        self.assertTrue(blocks[1].startswith("2\n"))


class RemapTest(unittest.TestCase):
    # 편집: 원본 (2,4)와 (6,8)만 남김 → 편집본 0~2가 원본 2~4, 2~4가 원본 6~8
    KEEPS = [(2, 4), (6, 8)]

    def test_caption_inside_keep(self):
        out = remap_captions([Caption(2.5, 3.5, "가")], self.KEEPS)
        self.assertEqual(len(out), 1)
        self.assertAlmostEqual(out[0].start, 0.5)
        self.assertAlmostEqual(out[0].end, 1.5)

    def test_caption_in_removed_region_dropped(self):
        self.assertEqual(remap_captions([Caption(4.5, 5.5, "가")], self.KEEPS), [])

    def test_caption_spanning_cut_is_bridged(self):
        out = remap_captions([Caption(3.5, 6.5, "가")], self.KEEPS)
        self.assertEqual(len(out), 1)
        self.assertAlmostEqual(out[0].start, 1.5)  # 원본 3.5 → 편집본 1.5
        self.assertAlmostEqual(out[0].end, 2.5)    # 원본 6.5 → 편집본 2.5

    def test_second_keep_offset(self):
        out = remap_captions([Caption(6.0, 8.0, "가")], self.KEEPS)
        self.assertAlmostEqual(out[0].start, 2.0)
        self.assertAlmostEqual(out[0].end, 4.0)

    def test_tiny_sliver_dropped(self):
        out = remap_captions([Caption(3.95, 4.5, "가")], self.KEEPS, min_len=0.15)
        self.assertEqual(out, [])


if __name__ == "__main__":
    unittest.main()
