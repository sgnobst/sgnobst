"""편집 계획(EditPlan)과 구간(interval) 계산 유틸리티.

시간 단위는 전부 '초'(float)이며, 구간은 원본 영상 기준 (시작, 끝) 튜플이다.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterable, List, Sequence, Tuple

Interval = Tuple[float, float]


def merge_intervals(intervals: Iterable[Sequence[float]], gap: float = 0.0) -> List[Interval]:
    """겹치거나 gap초 이내로 붙어 있는 구간을 하나로 합친다."""
    rows = sorted((float(a), float(b)) for a, b in intervals if float(b) > float(a))
    out: List[List[float]] = []
    for a, b in rows:
        if out and a - out[-1][1] <= gap:
            out[-1][1] = max(out[-1][1], b)
        else:
            out.append([a, b])
    return [(a, b) for a, b in out]


def invert_intervals(intervals: Iterable[Sequence[float]], total: float) -> List[Interval]:
    """[0, total] 안에서 주어진 구간의 여집합을 돌려준다."""
    out: List[Interval] = []
    cursor = 0.0
    for a, b in merge_intervals(intervals):
        a, b = max(0.0, a), min(float(total), b)
        if b <= cursor:
            continue
        if a > cursor:
            out.append((cursor, min(a, total)))
        cursor = max(cursor, b)
        if cursor >= total:
            break
    if cursor < total:
        out.append((cursor, float(total)))
    return [(a, b) for a, b in out if b - a > 1e-9]


def pad_intervals(intervals: Iterable[Sequence[float]], pad: float, total: float) -> List[Interval]:
    """각 구간을 양쪽으로 pad초 늘린 뒤 겹침을 정리한다."""
    grown = [(max(0.0, float(a) - pad), min(float(total), float(b) + pad)) for a, b in intervals]
    return merge_intervals(grown)


def intersect_intervals(a: Iterable[Sequence[float]], b: Iterable[Sequence[float]]) -> List[Interval]:
    """두 구간 목록의 교집합."""
    left, right = merge_intervals(a), merge_intervals(b)
    out: List[Interval] = []
    i = j = 0
    while i < len(left) and j < len(right):
        lo = max(left[i][0], right[j][0])
        hi = min(left[i][1], right[j][1])
        if hi - lo > 1e-9:
            out.append((lo, hi))
        if left[i][1] < right[j][1]:
            i += 1
        else:
            j += 1
    return out


@dataclass
class Segment:
    """원본 영상에서 잘라 쓸 한 구간."""

    start: float
    end: float
    label: str = ""
    score: float = 0.0

    @property
    def duration(self) -> float:
        return max(0.0, self.end - self.start)

    def to_dict(self) -> dict:
        return {"start": round(self.start, 4), "end": round(self.end, 4),
                "label": self.label, "score": round(self.score, 4)}

    @classmethod
    def from_dict(cls, data: dict) -> "Segment":
        return cls(
            start=float(data.get("start", 0.0)),
            end=float(data.get("end", 0.0)),
            label=str(data.get("label", "")),
            score=float(data.get("score", 0.0)),
        )


@dataclass
class EditPlan:
    """어떤 원본에서 어떤 구간을 어떤 순서로 이어붙일지에 대한 전체 계획."""

    source: str
    duration: float
    fps: float
    width: int
    height: int
    has_audio: bool
    segments: List[Segment] = field(default_factory=list)
    notes: List[str] = field(default_factory=list)

    def edited_duration(self) -> float:
        return sum(seg.duration for seg in self.segments)

    def offsets(self) -> List[float]:
        """각 세그먼트가 편집본 타임라인에서 시작하는 시각."""
        out: List[float] = []
        acc = 0.0
        for seg in self.segments:
            out.append(acc)
            acc += seg.duration
        return out

    def keep_tuples(self) -> List[Interval]:
        return [(seg.start, seg.end) for seg in self.segments]

    def to_dict(self) -> dict:
        return {
            "version": 1,
            "source": self.source,
            "duration": round(self.duration, 4),
            "fps": round(self.fps, 4),
            "width": self.width,
            "height": self.height,
            "has_audio": self.has_audio,
            "segments": [seg.to_dict() for seg in self.segments],
            "notes": list(self.notes),
        }

    @classmethod
    def from_dict(cls, data: dict) -> "EditPlan":
        return cls(
            source=str(data.get("source", "")),
            duration=float(data.get("duration", 0.0)),
            fps=float(data.get("fps", 30.0)),
            width=int(data.get("width", 0)),
            height=int(data.get("height", 0)),
            has_audio=bool(data.get("has_audio", True)),
            segments=[Segment.from_dict(s) for s in data.get("segments", [])],
            notes=[str(n) for n in data.get("notes", [])],
        )

    def save(self, path: "str | Path") -> None:
        Path(path).write_text(
            json.dumps(self.to_dict(), ensure_ascii=False, indent=2), encoding="utf-8"
        )

    @classmethod
    def load(cls, path: "str | Path") -> "EditPlan":
        return cls.from_dict(json.loads(Path(path).read_text(encoding="utf-8")))


def segments_from_intervals(intervals: Iterable[Sequence[float]], label_prefix: str = "장면") -> List[Segment]:
    return [
        Segment(start=float(a), end=float(b), label=f"{label_prefix} {i + 1}")
        for i, (a, b) in enumerate(intervals)
    ]
