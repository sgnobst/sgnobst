"""PreCut AI — 프리미어의 액기스만 뽑은 AI 자동 컷 편집기.

무음 컷 · 장면 감지 · 하이라이트 · AI 자막을 자동으로 처리하고,
결과를 Premiere Pro(2025)로 가져갈 수 있는 XML/EDL/SRT로 내보낸다.
"""

__version__ = "0.1.0"

from .plan import EditPlan, Segment  # noqa: F401
