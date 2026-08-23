"""무음 구간 감지와 '살릴 구간' 계산."""
from __future__ import annotations

import re
from typing import List, Optional

from .ffmpeg import run_ffmpeg
from .plan import Interval, invert_intervals, pad_intervals

_START_RE = re.compile(r"silence_start:\s*(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)")
_END_RE = re.compile(r"silence_end:\s*(-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?)")


def parse_silencedetect(text: str, duration: Optional[float] = None) -> List[Interval]:
    """ffmpeg silencedetect 필터의 stderr 출력에서 (시작, 끝) 무음 구간을 뽑는다.

    영상이 무음으로 끝나면 silence_end 없이 끝나므로 duration으로 닫아준다.
    """
    silences: List[Interval] = []
    open_start: Optional[float] = None
    for line in (text or "").splitlines():
        m = _START_RE.search(line)
        if m:
            open_start = max(0.0, float(m.group(1)))
            continue
        m = _END_RE.search(line)
        if m:
            end = float(m.group(1))
            start = open_start if open_start is not None else 0.0
            if end > start:
                silences.append((start, end))
            open_start = None
    if open_start is not None and duration is not None and duration > open_start:
        silences.append((open_start, float(duration)))
    return silences


def detect_silences(
    path: str,
    noise_db: float = -35.0,
    min_silence: float = 0.6,
    duration: Optional[float] = None,
) -> List[Interval]:
    """원본에서 무음 구간을 감지한다. 디코드만 하고 파일은 만들지 않는다."""
    proc = run_ffmpeg(
        [
            "-nostats", "-i", path, "-vn",
            "-af", f"silencedetect=noise={noise_db}dB:d={min_silence}",
            "-f", "null", "-",
        ],
        check=False,
    )
    if proc.returncode != 0:
        from .ffmpeg import ToolError, _tail
        raise ToolError("무음 감지에 실패했습니다:\n" + _tail(proc.stderr))
    return parse_silencedetect(proc.stderr, duration)


def keep_intervals(
    silences: List[Interval],
    duration: float,
    pad: float = 0.15,
    min_keep: float = 0.3,
) -> List[Interval]:
    """무음을 뺀 나머지(살릴 구간)를 계산한다.

    pad: 소리 구간 앞뒤에 남겨줄 여유(초). 너무 딱 잘리면 부자연스럽다.
    min_keep: 이보다 짧은 조각은 버린다(딸깍 소리 같은 잡음 방지).
    """
    keeps = invert_intervals(silences, duration)
    keeps = pad_intervals(keeps, pad, duration)
    return [(a, b) for a, b in keeps if b - a >= min_keep]
