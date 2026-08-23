"""ffmpeg가 있는 환경에서만 도는 전체 파이프라인 통합 테스트.

12초짜리 합성 영상(2초 소리 + 2초 무음 반복)을 만들어
무음 컷 → 렌더링 → 프리미어 XML까지 실제로 돌려본다.
"""
import shutil
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

HAS_FFMPEG = bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))


def make_test_video(path: Path, duration: int = 12) -> None:
    audio_expr = "if(lt(mod(t,4),2),0.5*sin(880*2*PI*t),0.0001)"
    cmd = [
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
        "-f", "lavfi", "-i", f"testsrc2=size=640x360:rate=30:duration={duration}",
        "-f", "lavfi", "-i", f"aevalsrc='{audio_expr}':s=48000:d={duration}",
        "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28",
        "-c:a", "aac", "-shortest", str(path),
    ]
    subprocess.run(cmd, check=True)


@unittest.skipUnless(HAS_FFMPEG, "ffmpeg가 설치된 환경에서만 실행")
class PipelineIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="precut-it-")
        cls.root = Path(cls.tmp.name)
        cls.video = cls.root / "테스트 영상.mp4"
        make_test_video(cls.video)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_silence_cut_pipeline(self):
        from precut.ffmpeg import probe
        from precut.pipeline import PipelineOptions, run_pipeline

        outdir = self.root / "결과-무음컷"
        logs = []
        opt = PipelineOptions(subtitles=False, render=True, premiere=True)
        result = run_pipeline(self.video, outdir, opt, log=logs.append)

        # 소리 구간은 0-2, 4-6, 8-10초 → 컷 3개, 총 6초 언저리
        plan = result["plan"]
        self.assertEqual(len(plan["segments"]), 3)
        edited = result["summary"]["edited_duration"]
        self.assertGreater(edited, 5.0)
        self.assertLess(edited, 8.5)

        files = result["files"]
        for key in ("video", "xml", "edl", "chapters", "plan"):
            self.assertIn(key, files)
            self.assertTrue(Path(files[key]).exists(), key)

        rendered = probe(files["video"])
        self.assertAlmostEqual(rendered.duration, edited, delta=1.0)
        self.assertTrue(rendered.has_audio)

        root = ET.parse(files["xml"]).getroot()
        self.assertEqual(len(root.findall(".//video/track/clipitem")), 3)
        self.assertTrue(any("완료" in line for line in logs))

    def test_highlight_pipeline(self):
        from precut.ffmpeg import probe
        from precut.pipeline import PipelineOptions, run_pipeline

        outdir = self.root / "결과-하이라이트"
        opt = PipelineOptions(target_duration=3.0, render=True, premiere=False)
        result = run_pipeline(self.video, outdir, opt, log=lambda s: None)

        edited = result["summary"]["edited_duration"]
        self.assertGreater(edited, 1.5)
        self.assertLess(edited, 7.0)
        rendered = probe(result["files"]["video"])
        self.assertAlmostEqual(rendered.duration, edited, delta=1.0)

    def test_instruction_heuristic_pipeline(self):
        from precut.pipeline import PipelineOptions, run_pipeline

        outdir = self.root / "결과-지시문"
        opt = PipelineOptions(
            instruction="렌더링 없이 프리미어 파일만 만들어줘",
            use_assistant=False,  # 네트워크 없이 규칙 기반 경로 검증
        )
        result = run_pipeline(self.video, outdir, opt, log=lambda s: None)
        self.assertNotIn("video", result["files"])
        self.assertIn("xml", result["files"])


if __name__ == "__main__":
    unittest.main()
