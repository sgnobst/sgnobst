import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from precut.assistant import (  # noqa: E402
    decision_from_dict, extract_json, heuristic_decision, parse_duration_korean,
)


class ExtractJsonTest(unittest.TestCase):
    def test_plain(self):
        self.assertEqual(extract_json('{"a": 1}'), {"a": 1})

    def test_with_prefix_text_and_fence(self):
        text = '알겠습니다!\n```json\n{"reply": "네", "subtitles": true}\n```\n끝'
        self.assertEqual(extract_json(text), {"reply": "네", "subtitles": True})

    def test_nested_braces_and_strings(self):
        text = '{"reply": "중괄호 } 포함 \\" 문자열", "inner": {"x": 1}} 뒤에 잡담 {"b": 2}'
        data = extract_json(text)
        self.assertEqual(data["inner"], {"x": 1})

    def test_no_json_raises(self):
        with self.assertRaises(ValueError):
            extract_json("JSON 없음")

    def test_truncated_raises(self):
        with self.assertRaises(ValueError):
            extract_json('{"a": {"b": 1}')


class DecisionDictTest(unittest.TestCase):
    def test_defaults(self):
        d = decision_from_dict({})
        self.assertIsNone(d.target_duration)
        self.assertTrue(d.remove_silence)
        self.assertTrue(d.render)
        self.assertFalse(d.subtitles)

    def test_clamps(self):
        d = decision_from_dict({
            "target_duration": "999999", "silence_noise_db": -300,
            "min_silence": 100, "language": "korean-language-x",
        })
        self.assertEqual(d.target_duration, 7200.0)
        self.assertEqual(d.silence_noise_db, -80.0)
        self.assertEqual(d.min_silence, 5.0)
        self.assertEqual(len(d.language), 8)

    def test_bad_target_ignored(self):
        self.assertIsNone(decision_from_dict({"target_duration": "abc"}).target_duration)


class KoreanDurationTest(unittest.TestCase):
    def test_patterns(self):
        self.assertEqual(parse_duration_korean("1분 30초로"), 90)
        self.assertEqual(parse_duration_korean("3분짜리"), 180)
        self.assertEqual(parse_duration_korean("45초 쇼츠"), 45)
        self.assertIsNone(parse_duration_korean("적당히 짧게"))


class HeuristicTest(unittest.TestCase):
    def test_highlight_with_duration_and_subtitles(self):
        d = heuristic_decision("1분 30초 하이라이트로 만들고 자막도 넣어줘")
        self.assertEqual(d.target_duration, 90)
        self.assertTrue(d.subtitles)
        self.assertTrue(d.remove_silence)
        self.assertFalse(d.used_claude)

    def test_highlight_without_duration_defaults_60(self):
        self.assertEqual(heuristic_decision("하이라이트 뽑아줘").target_duration, 60.0)

    def test_silence_only(self):
        d = heuristic_decision("무음만 잘라줘")
        self.assertIsNone(d.target_duration)
        self.assertTrue(d.remove_silence)

    def test_keep_silence(self):
        self.assertFalse(heuristic_decision("무음은 자르지 마").remove_silence)

    def test_subtitle_negation(self):
        self.assertFalse(heuristic_decision("무음만 잘라줘 자막은 빼고").subtitles)
        self.assertFalse(heuristic_decision("자막 없이 1분 하이라이트").subtitles)
        self.assertTrue(heuristic_decision("자막 넣어줘").subtitles)

    def test_no_render(self):
        d = heuristic_decision("렌더링 없이 프리미어 파일만 만들어줘")
        self.assertFalse(d.render)


if __name__ == "__main__":
    unittest.main()
