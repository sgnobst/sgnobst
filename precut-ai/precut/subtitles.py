"""AI 자막(받아쓰기)과 SRT 파일 처리."""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable, List, Optional, Sequence

from .ffmpeg import ToolError
from .plan import Interval, merge_intervals


@dataclass
class Caption:
    start: float
    end: float
    text: str


def format_srt_time(seconds: float) -> str:
    total_ms = max(0, int(round(seconds * 1000)))
    ms = total_ms % 1000
    total_s = total_ms // 1000
    s = total_s % 60
    m = (total_s // 60) % 60
    h = total_s // 3600
    return f"{h:02d}:{m:02d}:{s:02d},{ms:03d}"


def to_srt(captions: Sequence[Caption]) -> str:
    blocks = []
    for i, cap in enumerate(captions, start=1):
        blocks.append(
            f"{i}\n{format_srt_time(cap.start)} --> {format_srt_time(cap.end)}\n{cap.text.strip()}\n"
        )
    return "\n".join(blocks)


def write_srt(captions: Sequence[Caption], path: "str | Path") -> None:
    Path(path).write_text(to_srt(captions), encoding="utf-8")


def remap_captions(
    captions: Iterable[Caption],
    keeps: Sequence[Interval],
    min_len: float = 0.15,
) -> List[Caption]:
    """원본 타임라인 자막을 '잘라낸 편집본' 타임라인으로 옮긴다.

    잘려나간 구간과 겹치는 부분은 버려지고, 남은 부분만 새 시각으로 이동한다.
    """
    spans = []  # (원본 시작, 원본 끝, 편집본에서의 시작)
    acc = 0.0
    for a, b in merge_intervals(keeps):
        spans.append((a, b, acc))
        acc += b - a

    out: List[Caption] = []
    for cap in captions:
        pieces = []
        for a, b, offset in spans:
            lo = max(cap.start, a)
            hi = min(cap.end, b)
            if hi - lo > 1e-6:
                pieces.append((offset + (lo - a), offset + (hi - a)))
        if not pieces:
            continue
        start, end = pieces[0][0], pieces[-1][1]
        if end - start >= min_len:
            out.append(Caption(start=start, end=end, text=cap.text))
    return out


def transcribe(
    path: str,
    model_size: str = "small",
    language: Optional[str] = None,
    log: Optional[Callable[[str], None]] = None,
) -> List[Caption]:
    """faster-whisper로 음성을 받아써서 자막을 만든다(선택 기능)."""
    try:
        from faster_whisper import WhisperModel
    except ImportError:
        raise ToolError(
            "AI 자막 기능에는 faster-whisper가 필요합니다.\n"
            "터미널에서 다음을 실행해 주세요:  pip install faster-whisper\n"
            "(처음 한 번은 음성 인식 모델을 자동으로 내려받습니다. 수백 MB)"
        )
    if log:
        log(f"음성 인식 모델({model_size}) 로딩 중…")
    model = WhisperModel(model_size, device="auto", compute_type="auto")
    segments, info = model.transcribe(str(path), language=language, vad_filter=True)
    if log and getattr(info, "language", None):
        log(f"감지된 언어: {info.language}")
    captions: List[Caption] = []
    for seg in segments:
        text = (seg.text or "").strip()
        if text:
            captions.append(Caption(start=float(seg.start), end=float(seg.end), text=text))
        if log and captions and len(captions) % 20 == 0:
            log(f"자막 {len(captions)}줄 인식…")
    return captions
