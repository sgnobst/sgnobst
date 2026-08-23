"""AI 편집 어시스턴트 — 한국어 지시문을 편집 설정으로 바꾼다.

Claude API 키(ANTHROPIC_API_KEY)가 있으면 Claude가 지시문을 해석하고,
없으면 키워드 기반 휴리스틱으로 대신한다(오프라인에서도 동작).
"""
from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass, field
from typing import List, Optional

DEFAULT_MODEL = "claude-opus-5"


class AssistantUnavailable(RuntimeError):
    """API 키/패키지가 없어 Claude를 쓸 수 없는 상태."""


@dataclass
class AssistantDecision:
    """지시문에서 뽑아낸 편집 설정."""

    target_duration: Optional[float] = None  # 초. None이면 길이 제한 없음(무음 컷만)
    remove_silence: bool = True
    silence_noise_db: float = -35.0
    min_silence: float = 0.6
    subtitles: bool = False
    language: Optional[str] = None
    render: bool = True
    reply: str = ""
    used_claude: bool = False
    notes: List[str] = field(default_factory=list)


def extract_json(text: str) -> dict:
    """모델 응답에서 첫 번째 JSON 오브젝트를 찾아 파싱한다(코드펜스 허용)."""
    cleaned = re.sub(r"```(?:json)?", "", text or "")
    start = cleaned.find("{")
    if start < 0:
        raise ValueError("응답에서 JSON을 찾지 못했습니다")
    depth = 0
    in_string = False
    escaped = False
    for i in range(start, len(cleaned)):
        ch = cleaned[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return json.loads(cleaned[start:i + 1])
    raise ValueError("JSON이 중간에 끊겼습니다")


def _clamp(value: float, lo: float, hi: float) -> float:
    return max(lo, min(hi, value))


def decision_from_dict(data: dict) -> AssistantDecision:
    d = AssistantDecision()
    raw_target = data.get("target_duration")
    if isinstance(raw_target, (int, float, str)) and str(raw_target).strip():
        try:
            d.target_duration = _clamp(float(raw_target), 3.0, 7200.0)
        except ValueError:
            d.target_duration = None
    if "remove_silence" in data:
        d.remove_silence = bool(data["remove_silence"])
    try:
        d.silence_noise_db = _clamp(float(data.get("silence_noise_db", -35.0)), -80.0, -10.0)
    except (TypeError, ValueError):
        pass
    try:
        d.min_silence = _clamp(float(data.get("min_silence", 0.6)), 0.1, 5.0)
    except (TypeError, ValueError):
        pass
    d.subtitles = bool(data.get("subtitles", False))
    lang = data.get("language")
    if isinstance(lang, str) and lang.strip():
        d.language = lang.strip()[:8]
    if "render" in data:
        d.render = bool(data["render"])
    reply = data.get("reply", "")
    if isinstance(reply, str):
        d.reply = reply.strip()[:500]
    return d


_MIN_SEC_RE = re.compile(r"(\d+)\s*분\s*(\d+)\s*초")
_MIN_RE = re.compile(r"(\d+)\s*분")
_SEC_RE = re.compile(r"(\d+)\s*초")

_HIGHLIGHT_WORDS = ("하이라이트", "쇼츠", "숏츠", "짧게", "요약", "베스트", "명장면")
_SUBTITLE_WORDS = ("자막", "캡션", "받아쓰", "subtitle")
_NO_SUBTITLE_WORDS = ("자막 빼", "자막은 빼", "자막빼", "자막 없이", "자막없이",
                      "자막 안", "자막은 안", "자막은 됐", "캡션 빼", "캡션 없이")
_KEEP_SILENCE_WORDS = ("무음 유지", "무음은 남", "자르지 마", "자르지마", "무음 그대로")
_NO_RENDER_WORDS = ("렌더링 없이", "렌더 없이", "xml만", "XML만", "프리미어만")


def parse_duration_korean(text: str) -> Optional[float]:
    m = _MIN_SEC_RE.search(text)
    if m:
        return int(m.group(1)) * 60 + int(m.group(2))
    m = _MIN_RE.search(text)
    if m:
        return int(m.group(1)) * 60
    m = _SEC_RE.search(text)
    if m:
        return float(m.group(1))
    return None


def heuristic_decision(instruction: str) -> AssistantDecision:
    """키를 안 넣어도 동작하는 규칙 기반 지시문 해석."""
    text = instruction or ""
    d = AssistantDecision()
    duration = parse_duration_korean(text)
    wants_highlight = any(word in text for word in _HIGHLIGHT_WORDS)
    if wants_highlight and duration is None:
        duration = 60.0
    if wants_highlight or duration is not None:
        d.target_duration = duration
    if any(word in text for word in _NO_SUBTITLE_WORDS):
        d.subtitles = False
    else:
        d.subtitles = any(word in text for word in _SUBTITLE_WORDS)
    if any(word in text for word in _KEEP_SILENCE_WORDS):
        d.remove_silence = False
    if any(word in text for word in _NO_RENDER_WORDS):
        d.render = False

    parts = []
    if d.target_duration:
        parts.append(f"약 {int(d.target_duration)}초 하이라이트로 만들게요")
    elif d.remove_silence:
        parts.append("무음 구간만 잘라낼게요")
    if d.subtitles:
        parts.append("자막도 만들게요")
    if not d.remove_silence:
        parts.append("무음은 그대로 둘게요")
    d.reply = ", ".join(parts) + ". (규칙 기반 해석)" if parts else "기본 설정으로 진행할게요. (규칙 기반 해석)"
    return d


_SYSTEM_PROMPT = """너는 'PreCut AI'라는 영상 자동 컷편집 프로그램의 편집 플래너다.
사용자의 한국어 지시문과 영상 분석 정보를 읽고, 아래 JSON 스키마로만 답하라.
설명 문장은 reply 필드 안에만 쓰고, JSON 바깥에는 아무것도 쓰지 마라.

{
  "reply": "사용자에게 보여줄 한 두 문장의 한국어 요약",
  "target_duration": 목표 길이(초, 숫자) 또는 null(길이 제한 없이 무음만 컷),
  "remove_silence": true/false (무음 구간을 잘라낼지),
  "silence_noise_db": 무음 판정 기준 dB (기본 -35, 시끄러운 영상은 -30, 조용한 영상은 -45),
  "min_silence": 이 길이(초) 이상 무음일 때만 컷 (기본 0.6),
  "subtitles": true/false (AI 자막 생성 여부),
  "language": 자막 언어 코드("ko","en" 등) 또는 null(자동 감지),
  "render": true/false (완성본 MP4를 만들지. 프리미어에서만 다듬겠다면 false)
}

규칙:
- "쇼츠/숏츠"라면 60초 이하로 잡아라.
- 지시문에 길이가 있으면 그대로 따르고, "하이라이트/요약"인데 길이가 없으면 60초로 잡아라.
- 지시문이 무음 컷을 금지하지 않는 한 remove_silence는 true로 두어라.
- 확실하지 않은 값은 기본값을 유지하라."""


def _text_from_response(response) -> str:
    parts = []
    for block in getattr(response, "content", []) or []:
        if getattr(block, "type", "") == "text":
            parts.append(block.text)
    return "\n".join(parts)


def plan_from_instruction(
    instruction: str,
    analysis: Optional[dict] = None,
    model: Optional[str] = None,
) -> AssistantDecision:
    """Claude에게 지시문 해석을 맡긴다. 실패 조건이면 AssistantUnavailable을 던진다."""
    try:
        import anthropic
    except ImportError:
        raise AssistantUnavailable(
            "anthropic 패키지가 없습니다. AI 해석을 쓰려면:  pip install anthropic"
        )

    model = model or os.environ.get("PRECUT_CLAUDE_MODEL", DEFAULT_MODEL)
    client = anthropic.Anthropic()

    user_message = f"지시문: {instruction}\n\n영상 분석 정보:\n" + json.dumps(
        analysis or {}, ensure_ascii=False, indent=2
    )
    request = dict(
        model=model,
        max_tokens=2000,
        system=_SYSTEM_PROMPT,
        thinking={"type": "adaptive"},
        messages=[{"role": "user", "content": user_message}],
    )

    try:
        try:
            # 안전 거절 시 서버가 자동으로 대체 모델로 이어주는 폴백을 기본 활성화
            response = client.beta.messages.create(
                betas=["server-side-fallback-2026-07-01"], fallbacks="default", **request
            )
        except (TypeError, anthropic.BadRequestError, anthropic.NotFoundError):
            response = client.messages.create(**request)
    except anthropic.AuthenticationError:
        raise AssistantUnavailable(
            "Claude API 키가 없거나 잘못됐습니다. ANTHROPIC_API_KEY 환경변수를 확인해 주세요."
        )
    except anthropic.APIConnectionError:
        raise AssistantUnavailable("인터넷 연결이 없어 Claude를 사용할 수 없습니다.")

    if getattr(response, "stop_reason", "") == "refusal":
        raise AssistantUnavailable("요청이 정책상 거절되었습니다. 지시문을 바꿔서 다시 시도해 주세요.")

    text = _text_from_response(response)
    try:
        decision = decision_from_dict(extract_json(text))
    except (ValueError, json.JSONDecodeError):
        raise AssistantUnavailable("Claude 응답을 해석하지 못했습니다. 다시 시도해 주세요.")
    decision.used_claude = True
    if not decision.reply:
        decision.reply = "지시문대로 편집 설정을 잡았어요."
    return decision


def interpret(instruction: str, analysis: Optional[dict] = None) -> AssistantDecision:
    """Claude 우선, 실패하면 휴리스틱으로 자동 전환."""
    instruction = (instruction or "").strip()
    if not instruction:
        d = AssistantDecision()
        d.reply = "기본 설정(무음 컷)으로 진행할게요."
        return d
    try:
        return plan_from_instruction(instruction, analysis)
    except AssistantUnavailable as exc:
        d = heuristic_decision(instruction)
        d.notes.append(f"Claude 대신 규칙 기반 해석 사용: {exc}")
        return d


def claude_ready() -> bool:
    """UI 배지용 — 패키지와 키가 모두 준비됐는지."""
    try:
        import anthropic  # noqa: F401
    except ImportError:
        return False
    return bool(
        os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN")
    )
