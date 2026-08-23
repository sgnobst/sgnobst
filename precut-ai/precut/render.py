"""편집 계획(EditPlan)대로 완성본 MP4를 만든다."""
from __future__ import annotations

import re
import subprocess
import tempfile
from pathlib import Path
from typing import Callable, List, Optional

from .ffmpeg import ToolError, _tail, ffmpeg_path
from .plan import EditPlan

ProgressCb = Optional[Callable[[float], None]]


def build_filter_script(plan: EditPlan) -> str:
    """trim + concat 필터 그래프. 프레임 단위로 정확하게 자른다."""
    lines: List[str] = []
    pairs: List[str] = []
    for i, seg in enumerate(plan.segments):
        lines.append(
            f"[0:v]trim=start={seg.start:.6f}:end={seg.end:.6f},setpts=PTS-STARTPTS[v{i}];"
        )
        if plan.has_audio:
            lines.append(
                f"[0:a]atrim=start={seg.start:.6f}:end={seg.end:.6f},asetpts=PTS-STARTPTS[a{i}];"
            )
            pairs.append(f"[v{i}][a{i}]")
        else:
            pairs.append(f"[v{i}]")
    n = len(plan.segments)
    if plan.has_audio:
        lines.append(f"{''.join(pairs)}concat=n={n}:v=1:a=1[outv][outa]")
    else:
        lines.append(f"{''.join(pairs)}concat=n={n}:v=1:a=0[outv]")
    return "\n".join(lines)


_TIME_MS_RE = re.compile(r"out_time_ms=(\d+)")
_TIME_RE = re.compile(r"out_time=(\d+):(\d+):(\d+(?:\.\d+)?)")


def _parse_progress_seconds(line: str) -> Optional[float]:
    m = _TIME_MS_RE.search(line)
    if m:
        return int(m.group(1)) / 1_000_000.0  # 이름과 달리 마이크로초 단위
    m = _TIME_RE.search(line)
    if m:
        return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))
    return None


def render_plan(
    plan: EditPlan,
    out_path: "str | Path",
    crf: int = 18,
    preset: str = "veryfast",
    progress: ProgressCb = None,
) -> str:
    """재인코딩으로 정확하게 잘라 붙인 MP4를 만든다."""
    if not plan.segments:
        raise ToolError("살릴 구간이 하나도 없습니다. 무음 기준(dB)을 낮춰서 다시 시도해 보세요.")
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    total = max(0.01, plan.edited_duration())

    with tempfile.TemporaryDirectory(prefix="precut-") as tmp:
        script = Path(tmp) / "filter.txt"
        script.write_text(build_filter_script(plan), encoding="utf-8")
        stderr_log = Path(tmp) / "stderr.log"

        cmd = [
            ffmpeg_path(), "-hide_banner", "-y", "-nostats",
            "-progress", "pipe:1",
            "-i", plan.source,
            "-filter_complex_script", str(script),
            "-map", "[outv]",
        ]
        if plan.has_audio:
            cmd += ["-map", "[outa]", "-c:a", "aac", "-b:a", "192k"]
        cmd += [
            "-c:v", "libx264", "-preset", preset, "-crf", str(crf),
            "-pix_fmt", "yuv420p", "-movflags", "+faststart",
            str(out),
        ]

        with open(stderr_log, "w", encoding="utf-8", errors="replace") as errf:
            proc = subprocess.Popen(
                cmd, stdout=subprocess.PIPE, stderr=errf,
                text=True, encoding="utf-8", errors="replace",
            )
            assert proc.stdout is not None
            for line in proc.stdout:
                if progress is None:
                    continue
                seconds = _parse_progress_seconds(line)
                if seconds is not None:
                    progress(min(99.0, seconds / total * 100.0))
            proc.wait()
        if proc.returncode != 0:
            raise ToolError(
                "완성본 렌더링에 실패했습니다:\n"
                + _tail(stderr_log.read_text(encoding="utf-8", errors="replace"))
            )
    if progress:
        progress(100.0)
    return str(out)


def extract_thumbnail(source: str, at_seconds: float, out_path: "str | Path", width: int = 640) -> str:
    """미리보기용 썸네일 한 장을 뽑는다."""
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    cmd = [
        ffmpeg_path(), "-hide_banner", "-loglevel", "error", "-y",
        "-ss", f"{max(0.0, at_seconds):.3f}", "-i", source,
        "-frames:v", "1", "-vf", f"scale={width}:-2", str(out),
    ]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if proc.returncode != 0:
        raise ToolError("썸네일 추출에 실패했습니다:\n" + _tail(proc.stderr))
    return str(out)
