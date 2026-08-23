"""분석 → AI 결정 → 컷 계획 → 자막 → 렌더링 → 프리미어 내보내기 전체 흐름."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, List, Optional

from . import assistant, highlights, premiere, render, scenes, silence, subtitles
from .ffmpeg import ToolError, probe
from .plan import EditPlan, Segment, intersect_intervals, segments_from_intervals

Log = Callable[[str], None]


@dataclass
class PipelineOptions:
    instruction: str = ""          # AI에게 맡길 한국어 지시문(선택)
    use_assistant: bool = True
    remove_silence: bool = True
    noise_db: float = -35.0
    min_silence: float = 0.6
    pad: float = 0.15
    min_keep: float = 0.3
    target_duration: Optional[float] = None  # 지정하면 하이라이트 모드
    subtitles: bool = False
    whisper_model: str = "small"
    language: Optional[str] = None
    render: bool = True
    crf: int = 18
    preset: str = "veryfast"
    premiere: bool = True
    sequence_name: str = ""

    def apply_decision(self, decision: "assistant.AssistantDecision") -> None:
        self.target_duration = decision.target_duration
        self.remove_silence = decision.remove_silence
        self.noise_db = decision.silence_noise_db
        self.min_silence = decision.min_silence
        self.subtitles = self.subtitles or decision.subtitles
        if decision.language:
            self.language = decision.language
        self.render = decision.render


def _format_duration(seconds: float) -> str:
    total = int(round(seconds))
    if total >= 3600:
        return f"{total // 3600}시간 {(total // 60) % 60}분 {total % 60}초"
    if total >= 60:
        return f"{total // 60}분 {total % 60}초"
    return f"{total}초"


def run_pipeline(
    source: "str | Path",
    outdir: "str | Path",
    options: Optional[PipelineOptions] = None,
    log: Optional[Log] = None,
) -> dict:
    opt = options or PipelineOptions()
    say: Log = log or (lambda line: None)
    src = str(Path(source))
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)

    say("[1/6] 영상 정보를 읽는 중…")
    info = probe(src)
    say(
        f"  · {Path(src).name} — {_format_duration(info.duration)}, "
        f"{info.width}x{info.height}, {info.fps:.2f}fps, "
        f"오디오 {'있음' if info.has_audio else '없음'}"
    )

    decision_reply = ""
    used_claude = False
    if opt.instruction.strip():
        say("[2/6] AI가 지시문을 해석하는 중…")
        if opt.use_assistant:
            decision = assistant.interpret(
                opt.instruction,
                {
                    "길이_초": round(info.duration, 1),
                    "해상도": f"{info.width}x{info.height}",
                    "fps": round(info.fps, 2),
                    "오디오": info.has_audio,
                },
            )
        else:
            decision = assistant.heuristic_decision(opt.instruction)
        opt.apply_decision(decision)
        decision_reply = decision.reply
        used_claude = decision.used_claude
        say(f"  · AI: {decision.reply}")
        for note in decision.notes:
            say(f"  · 참고: {note}")
    else:
        say("[2/6] 지시문 없음 — 설정된 옵션대로 진행합니다.")

    say("[3/6] 무음/장면을 분석하는 중…")
    if info.has_audio and opt.remove_silence:
        silences = silence.detect_silences(src, opt.noise_db, opt.min_silence, info.duration)
        keeps = silence.keep_intervals(silences, info.duration, opt.pad, opt.min_keep)
        removed = info.duration - sum(b - a for a, b in keeps)
        say(f"  · 무음 {len(silences)}곳, 총 {_format_duration(max(0.0, removed))} 잘라냄")
        if not keeps:
            say("  · 경고: 전부 무음으로 판정되어 원본 전체를 사용합니다. (무음 기준을 조정해 보세요)")
            keeps = [(0.0, info.duration)]
    else:
        if not info.has_audio:
            say("  · 오디오가 없어 무음 컷은 건너뜁니다.")
        keeps = [(0.0, info.duration)]

    try:
        scene_times = scenes.detect_scenes(src)
        say(f"  · 장면 전환 {len(scene_times)}곳 감지")
    except ToolError as exc:
        scene_times = []
        say(f"  · 장면 감지 건너뜀: {exc}")
    boundaries = scenes.scene_boundaries(scene_times, info.duration)

    kept_total = sum(b - a for a, b in keeps)
    segments: List[Segment]
    if opt.target_duration and opt.target_duration < kept_total - 0.5:
        say(f"[4/6] 하이라이트 약 {_format_duration(opt.target_duration)} 선정 중…")
        if info.has_audio:
            energies = highlights.audio_energy(src)
        else:
            energies = []
            say("  · 오디오가 없어 영상 전체에서 고르게 뽑습니다.")
        pieces = []
        for cand in highlights.build_candidates(boundaries):
            pieces.extend(intersect_intervals([cand], keeps))
        pieces = [(a, b) for a, b in pieces if b - a >= 0.5] or keeps
        scored = highlights.score_candidates(pieces, energies)
        segments = highlights.pick_highlights(scored, opt.target_duration)
    else:
        if opt.target_duration:
            say("[4/6] 목표 길이가 현재 길이보다 길어 무음 컷 결과를 그대로 사용합니다.")
        else:
            say("[4/6] 무음 컷 결과로 타임라인을 구성합니다.")
        segments = segments_from_intervals(keeps)

    if not segments:
        raise ToolError("편집 구간을 만들지 못했습니다. 옵션을 바꿔 다시 시도해 주세요.")

    plan = EditPlan(
        source=str(Path(src).resolve()),
        duration=info.duration,
        fps=info.fps,
        width=info.width,
        height=info.height,
        has_audio=info.has_audio,
        segments=segments,
    )
    if decision_reply:
        plan.notes.append(f"AI 해석: {decision_reply}")
    say(
        f"  · 컷 {len(segments)}개, 편집본 {_format_duration(plan.edited_duration())} "
        f"(원본 {_format_duration(info.duration)})"
    )

    files = {}
    plan_path = out / "plan.json"
    plan.save(plan_path)
    files["plan"] = str(plan_path)

    if opt.subtitles:
        say("[5/6] AI 자막을 만드는 중… (첫 실행은 모델 다운로드로 오래 걸릴 수 있어요)")
        try:
            captions = subtitles.transcribe(
                src, model_size=opt.whisper_model, language=opt.language,
                log=lambda line: say(f"  · {line}"),
            )
            if captions:
                srt_source = out / "자막_원본.srt"
                subtitles.write_srt(captions, srt_source)
                files["srt_source"] = str(srt_source)
                remapped = subtitles.remap_captions(captions, plan.keep_tuples())
                srt_edited = out / "자막_편집본.srt"
                subtitles.write_srt(remapped, srt_edited)
                files["srt_edited"] = str(srt_edited)
                say(f"  · 자막 {len(captions)}줄 (편집본 기준 {len(remapped)}줄)")
            else:
                say("  · 인식된 음성이 없어 자막을 만들지 않았습니다.")
        except ToolError as exc:
            say(f"  · 자막 건너뜀: {exc}")
    else:
        say("[5/6] 자막 생성은 꺼져 있습니다.")

    if opt.premiere:
        seq_name = opt.sequence_name or f"{Path(src).stem} - PreCut"
        bundle = premiere.write_premiere_bundle(plan, out, seq_name)
        files.update(bundle)
        say("  · 프리미어용 XML/EDL/챕터 저장 완료")

    if opt.render:
        say("[6/6] 완성본 MP4 렌더링 중…")
        video_path = out / "완성본.mp4"
        last = {"pct": -10.0}

        def on_progress(pct: float) -> None:
            if pct - last["pct"] >= 10.0 or pct >= 100.0:
                last["pct"] = pct
                say(f"  · 렌더링 {pct:.0f}%")

        render.render_plan(plan, video_path, crf=opt.crf, preset=opt.preset, progress=on_progress)
        files["video"] = str(video_path)
    else:
        say("[6/6] 렌더링은 꺼져 있습니다. (프리미어 XML로 이어서 작업하세요)")

    summary = {
        "original_duration": round(info.duration, 2),
        "edited_duration": round(plan.edited_duration(), 2),
        "segment_count": len(segments),
        "decision_reply": decision_reply,
        "used_claude": used_claude,
    }
    say("완료! 결과가 저장됐습니다.")
    return {"plan": plan.to_dict(), "files": files, "summary": summary}
