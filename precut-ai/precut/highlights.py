"""소리 에너지 + 장면 경계 기반 하이라이트 자동 선정."""
from __future__ import annotations

import math
from array import array
from typing import List, Sequence, Tuple

from .ffmpeg import run_ffmpeg_bytes
from .plan import Segment

try:  # 파이썬 3.12까지는 C 구현으로 훨씬 빠르다
    import audioop  # type: ignore
except ImportError:  # 3.13+
    audioop = None  # type: ignore

ENERGY_RATE = 8000
ENERGY_BIN = 0.5  # 초


def extract_pcm(path: str, rate: int = ENERGY_RATE) -> bytes:
    """분석용 저해상도 모노 PCM(s16le)을 추출한다."""
    return run_ffmpeg_bytes(
        [
            "-loglevel", "error", "-i", path,
            "-map", "a:0", "-ac", "1", "-ar", str(rate),
            "-f", "s16le", "pipe:1",
        ]
    )


def rms_per_bin(pcm: bytes, rate: int = ENERGY_RATE, bin_seconds: float = ENERGY_BIN) -> List[float]:
    """bin_seconds 간격으로 0~1 사이의 소리 크기(RMS)를 계산한다."""
    usable = len(pcm) - (len(pcm) % 2)
    if usable <= 0:
        return []
    step = max(1, int(rate * bin_seconds)) * 2  # 바이트 단위
    out: List[float] = []
    for offset in range(0, usable, step):
        chunk = pcm[offset:min(offset + step, usable)]
        if len(chunk) < 2:
            break
        if audioop is not None:
            value = audioop.rms(chunk, 2) / 32768.0
        else:
            samples = array("h")
            samples.frombytes(chunk)
            value = math.sqrt(sum(v * v for v in samples) / len(samples)) / 32768.0
        out.append(value)
    return out


def audio_energy(path: str, bin_seconds: float = ENERGY_BIN) -> List[float]:
    return rms_per_bin(extract_pcm(path), ENERGY_RATE, bin_seconds)


def build_candidates(
    boundaries: Sequence[float],
    min_len: float = 0.8,
    max_len: float = 8.0,
) -> List[Tuple[float, float]]:
    """장면 경계 사이를 후보 구간으로 만들고, 긴 장면은 max_len 이하로 쪼갠다."""
    cands: List[Tuple[float, float]] = []
    for a, b in zip(boundaries, boundaries[1:]):
        length = b - a
        if length <= 0:
            continue
        pieces = max(1, math.ceil(length / max_len))
        step = length / pieces
        for i in range(pieces):
            s = a + i * step
            e = min(b, s + step)
            if e - s >= min_len:
                cands.append((s, e))
    return cands


def score_candidates(
    cands: Sequence[Tuple[float, float]],
    energies: Sequence[float],
    bin_seconds: float = ENERGY_BIN,
) -> List[Segment]:
    """후보마다 소리 크기 기반 점수를 매긴다(평균 80% + 피크 20%)."""
    out: List[Segment] = []
    for s, e in cands:
        i0 = max(0, int(s / bin_seconds))
        i1 = max(i0 + 1, math.ceil(e / bin_seconds))
        window = list(energies[i0:i1]) or [0.0]
        mean = sum(window) / len(window)
        peak = max(window)
        out.append(Segment(start=s, end=e, score=0.8 * mean + 0.2 * peak))
    return out


def _spread_pick(scored: List[Segment], target: float) -> List[Segment]:
    """점수가 전부 0에 가까울 때(무음/음성 없음) 영상 전체에서 고르게 뽑는다."""
    total = sum(seg.duration for seg in scored)
    if total <= target:
        return list(scored)
    stride = max(1, round(total / target))
    picked: List[Segment] = []
    acc = 0.0
    for i in range(0, len(scored), stride):
        picked.append(scored[i])
        acc += scored[i].duration
        if acc >= target:
            break
    return picked


def pick_highlights(scored: List[Segment], target: float, merge_gap: float = 0.3) -> List[Segment]:
    """목표 길이를 채울 때까지 점수 순으로 뽑고, 시간순으로 정렬해 이어붙인다."""
    if not scored or target <= 0:
        return []
    if max(seg.score for seg in scored) < 1e-6:
        chosen = _spread_pick(scored, target)
    else:
        chosen = []
        total = 0.0
        for seg in sorted(scored, key=lambda s: -s.score):
            if total >= target:
                break
            chosen.append(seg)
            total += seg.duration
    chosen.sort(key=lambda s: s.start)

    merged: List[Segment] = []
    for seg in chosen:
        if merged and seg.start - merged[-1].end <= merge_gap:
            prev = merged[-1]
            merged[-1] = Segment(
                start=prev.start,
                end=max(prev.end, seg.end),
                score=max(prev.score, seg.score),
            )
        else:
            merged.append(Segment(start=seg.start, end=seg.end, score=seg.score))
    for i, seg in enumerate(merged):
        seg.label = f"하이라이트 {i + 1}"
    return merged
