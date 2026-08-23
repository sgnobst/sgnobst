"""장면 전환(컷 포인트) 감지."""
from __future__ import annotations

import re
from typing import List

from .ffmpeg import run_ffmpeg

_PTS_RE = re.compile(r"pts_time:(\d+(?:\.\d+)?)")


def parse_showinfo_times(text: str, min_gap: float = 0.05) -> List[float]:
    """showinfo 필터 stderr 출력에서 프레임 시각(pts_time)을 뽑는다."""
    times: List[float] = []
    for m in _PTS_RE.finditer(text or ""):
        t = float(m.group(1))
        if not times or t - times[-1] > min_gap:
            times.append(t)
    return times


def detect_scenes(path: str, threshold: float = 0.35) -> List[float]:
    """장면이 바뀌는 시각 목록을 돌려준다(초)."""
    proc = run_ffmpeg(
        [
            "-nostats", "-i", path,
            "-vf", f"select='gt(scene,{threshold})',showinfo",
            "-an", "-f", "null", "-",
        ],
        check=False,
    )
    if proc.returncode != 0:
        from .ffmpeg import ToolError, _tail
        raise ToolError("장면 감지에 실패했습니다:\n" + _tail(proc.stderr))
    return parse_showinfo_times(proc.stderr)


def scene_boundaries(times: List[float], duration: float) -> List[float]:
    """0과 영상 끝을 포함한 장면 경계 목록."""
    bounds = [0.0]
    for t in sorted(times):
        if 0.1 < t < duration - 0.05 and t - bounds[-1] > 0.05:
            bounds.append(t)
    bounds.append(float(duration))
    return bounds
