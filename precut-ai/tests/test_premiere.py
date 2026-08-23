import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.plan import EditPlan, Segment  # noqa: E402
from precut.premiere import (  # noqa: E402
    build_chapters, build_edl, build_fcp7_xml, frames_to_timecode,
    timebase_for_fps, to_frames,
)


def make_plan(has_audio=True, fps=30.0):
    return EditPlan(
        source="/tmp/영상 <테스트> & 원본.mp4",
        duration=120.0, fps=fps, width=1920, height=1080, has_audio=has_audio,
        segments=[Segment(10.0, 20.0, "장면 1"), Segment(40.0, 45.5, "장면 2")],
    )


class TimebaseTest(unittest.TestCase):
    def test_common_rates(self):
        self.assertEqual(timebase_for_fps(29.97), (30, True))
        self.assertEqual(timebase_for_fps(30000 / 1001), (30, True))
        self.assertEqual(timebase_for_fps(23.976), (24, True))
        self.assertEqual(timebase_for_fps(25.0), (25, False))
        self.assertEqual(timebase_for_fps(60.0), (60, False))
        self.assertEqual(timebase_for_fps(59.94), (60, True))

    def test_unusual_rate_rounded(self):
        self.assertEqual(timebase_for_fps(14.9), (15, False))


class FrameTest(unittest.TestCase):
    def test_to_frames(self):
        self.assertEqual(to_frames(1.0, 30.0), 30)
        self.assertEqual(to_frames(1.0, 29.97), 30)

    def test_timecode(self):
        self.assertEqual(frames_to_timecode(0, 30), "00:00:00:00")
        self.assertEqual(frames_to_timecode(30, 30), "00:00:01:00")
        self.assertEqual(frames_to_timecode(30 * 3600 + 31, 30), "01:00:01:01")


class XmlTest(unittest.TestCase):
    def test_wellformed_and_structure(self):
        xml = build_fcp7_xml(make_plan(), "테스트 시퀀스")
        root = ET.fromstring(xml)
        self.assertEqual(root.tag, "xmeml")
        seq = root.find("sequence")
        self.assertEqual(seq.findtext("name"), "테스트 시퀀스")
        video_items = seq.findall("./media/video/track/clipitem")
        audio_items = seq.findall("./media/audio/track/clipitem")
        self.assertEqual(len(video_items), 2)
        self.assertEqual(len(audio_items), 2)
        # 첫 클립: 원본 10~20초 → 프레임 300~600, 타임라인 0~300
        first = video_items[0]
        self.assertEqual(first.findtext("in"), "300")
        self.assertEqual(first.findtext("out"), "600")
        self.assertEqual(first.findtext("start"), "0")
        self.assertEqual(first.findtext("end"), "300")
        # 두 번째 클립은 타임라인에서 이어붙는다
        second = video_items[1]
        self.assertEqual(second.findtext("start"), "300")
        self.assertEqual(second.findtext("end"), "465")
        # 파일 참조는 한 번만 전체 정의
        self.assertIsNotNone(first.find("file/pathurl"))
        self.assertIsNone(second.find("file/pathurl"))
        self.assertEqual(second.find("file").get("id"), "file-1")
        # 시퀀스 길이 = 300 + 165
        self.assertEqual(seq.findtext("duration"), "465")

    def test_no_audio(self):
        xml = build_fcp7_xml(make_plan(has_audio=False))
        root = ET.fromstring(xml)
        self.assertIsNone(root.find("sequence/media/audio"))
        self.assertEqual(len(root.findall(".//video/track/clipitem")), 2)

    def test_special_chars_escaped(self):
        xml = build_fcp7_xml(make_plan())
        ET.fromstring(xml)  # &, <, > 가 이스케이프되어야 파싱된다
        self.assertIn("&amp;", xml)

    def test_ntsc_rate(self):
        xml = build_fcp7_xml(make_plan(fps=29.97))
        root = ET.fromstring(xml)
        rate = root.find("sequence/rate")
        self.assertEqual(rate.findtext("timebase"), "30")
        self.assertEqual(rate.findtext("ntsc"), "TRUE")


class EdlTest(unittest.TestCase):
    def test_events(self):
        edl = build_edl(make_plan(), "MYCUT")
        lines = edl.splitlines()
        self.assertEqual(lines[0], "TITLE: MYCUT")
        self.assertEqual(lines[1], "FCM: NON-DROP FRAME")
        events = [l for l in lines if l[:3].isdigit()]
        self.assertEqual(len(events), 2)
        self.assertIn("AA/V", events[0])
        self.assertIn("00:00:10:00 00:00:20:00 00:00:00:00 00:00:10:00", events[0])
        self.assertIn("00:00:40:00 00:00:45:15 00:00:10:00 00:00:15:15", events[1])
        self.assertTrue(any("FROM CLIP NAME" in l for l in lines))

    def test_video_only_channel(self):
        edl = build_edl(make_plan(has_audio=False))
        event = [l for l in edl.splitlines() if l[:3].isdigit()][0]
        self.assertIn(" V ", event)
        self.assertNotIn("AA/V", event)


class ChaptersTest(unittest.TestCase):
    def test_offsets_are_edited_timeline(self):
        text = build_chapters(make_plan())
        lines = text.strip().splitlines()
        self.assertEqual(len(lines), 2)
        self.assertTrue(lines[0].startswith("00:00 장면 1"))
        self.assertTrue(lines[1].startswith("00:10 장면 2"))


if __name__ == "__main__":
    unittest.main()
