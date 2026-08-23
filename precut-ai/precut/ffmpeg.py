"""ffmpeg / ffprobe 실행 도우미."""
from __future__ import annotations

import json
import os
import shutil
import subprocess
from dataclasses import asdict, dataclass
from fractions import Fraction
from pathlib import Path
from typing import Optional, Sequence


class ToolError(RuntimeError):
    """사용자에게 그대로 보여줄 수 있는 한국어 오류 메시지."""


_INSTALL_HINT = (
    "ffmpeg(무료 영상 처리 엔진)를 찾을 수 없습니다.\n"
    "  · 윈도우: https://www.gyan.dev/ffmpeg/builds/ 에서 release zip을 받아 압축을 풀고\n"
    "    bin 폴더를 PATH에 추가하세요. (winget install ffmpeg 도 가능)\n"
    "  · 맥: 터미널에서  brew install ffmpeg\n"
    "  · 이미 설치했다면 환경변수 PRECUT_FFMPEG / PRECUT_FFPROBE 에 전체 경로를 지정해도 됩니다."
)


def _resolve(name: str, env_var: str) -> str:
    override = os.environ.get(env_var)
    if override:
        if Path(override).exists() or shutil.which(override):
            return override
        raise ToolError(f"{env_var}={override} 경로에 실행 파일이 없습니다.\n{_INSTALL_HINT}")
    found = shutil.which(name)
    if found:
        return found
    raise ToolError(_INSTALL_HINT)


def ffmpeg_path() -> str:
    return _resolve("ffmpeg", "PRECUT_FFMPEG")


def ffprobe_path() -> str:
    return _resolve("ffprobe", "PRECUT_FFPROBE")


def _tail(text: str, lines: int = 25) -> str:
    rows = (text or "").strip().splitlines()
    return "\n".join(rows[-lines:])


def run_ffmpeg(args: Sequence[str], check: bool = True) -> "subprocess.CompletedProcess[str]":
    cmd = [ffmpeg_path(), "-hide_banner", *[str(a) for a in args]]
    proc = subprocess.run(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace",
    )
    if check and proc.returncode != 0:
        raise ToolError("ffmpeg 실행에 실패했습니다:\n" + _tail(proc.stderr))
    return proc


def run_ffmpeg_bytes(args: Sequence[str], check: bool = True) -> bytes:
    """stdout을 원시 바이트로 받는다(PCM 추출 등)."""
    cmd = [ffmpeg_path(), "-hide_banner", *[str(a) for a in args]]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and proc.returncode != 0:
        err = proc.stderr.decode("utf-8", errors="replace")
        raise ToolError("ffmpeg 실행에 실패했습니다:\n" + _tail(err))
    return proc.stdout


@dataclass
class MediaInfo:
    path: str
    duration: float
    width: int
    height: int
    fps: float
    has_audio: bool
    vcodec: str = ""
    acodec: str = ""
    sample_rate: int = 48000

    def to_dict(self) -> dict:
        return asdict(self)


def _parse_fps(stream: dict) -> float:
    for key in ("avg_frame_rate", "r_frame_rate"):
        raw = stream.get(key) or ""
        if raw and raw != "0/0":
            try:
                value = float(Fraction(raw))
                if value > 0:
                    return value
            except (ValueError, ZeroDivisionError):
                continue
    return 30.0


def probe(path: "str | os.PathLike") -> MediaInfo:
    p = Path(path)
    if not p.exists():
        raise ToolError(f"파일을 찾을 수 없습니다: {p}")
    cmd = [
        ffprobe_path(), "-v", "error", "-print_format", "json",
        "-show_format", "-show_streams", str(p),
    ]
    proc = subprocess.run(
        cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        text=True, encoding="utf-8", errors="replace",
    )
    if proc.returncode != 0:
        raise ToolError(f"영상 정보를 읽지 못했습니다 ({p.name}):\n" + _tail(proc.stderr))
    data = json.loads(proc.stdout or "{}")

    video: Optional[dict] = None
    audio: Optional[dict] = None
    for stream in data.get("streams", []):
        kind = stream.get("codec_type")
        if kind == "video" and video is None:
            if stream.get("disposition", {}).get("attached_pic"):
                continue
            video = stream
        elif kind == "audio" and audio is None:
            audio = stream
    if video is None:
        raise ToolError(f"영상 트랙이 없는 파일입니다: {p.name}")

    duration = 0.0
    for source in (data.get("format", {}), video):
        try:
            duration = float(source.get("duration") or 0.0)
        except (TypeError, ValueError):
            duration = 0.0
        if duration > 0:
            break
    if duration <= 0:
        raise ToolError(f"영상 길이를 알 수 없습니다: {p.name}")

    sample_rate = 48000
    if audio is not None:
        try:
            sample_rate = int(audio.get("sample_rate") or 48000)
        except (TypeError, ValueError):
            sample_rate = 48000

    return MediaInfo(
        path=str(p),
        duration=duration,
        width=int(video.get("width") or 0),
        height=int(video.get("height") or 0),
        fps=_parse_fps(video),
        has_audio=audio is not None,
        vcodec=video.get("codec_name") or "",
        acodec=(audio or {}).get("codec_name") or "",
        sample_rate=sample_rate,
    )
