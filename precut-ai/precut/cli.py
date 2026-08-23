"""precut 명령줄 인터페이스."""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from typing import List, Optional

from . import __version__
from .ffmpeg import ToolError


def _default_outdir(video: str) -> str:
    p = Path(video)
    return str(p.parent / f"{p.stem}_precut")


def _cmd_auto(args: argparse.Namespace) -> int:
    from .pipeline import PipelineOptions, run_pipeline

    opt = PipelineOptions(
        instruction=args.prompt or "",
        use_assistant=not args.no_ai,
        remove_silence=not args.keep_silence,
        noise_db=args.noise_db,
        min_silence=args.min_silence,
        target_duration=args.target,
        subtitles=args.subtitles,
        whisper_model=args.whisper_model,
        language=args.language,
        render=not args.no_render,
        premiere=not args.no_premiere,
    )
    outdir = args.output or _default_outdir(args.video)
    result = run_pipeline(args.video, outdir, opt, log=print)
    print()
    print("결과 파일:")
    for key, label in [
        ("video", "완성본 MP4"), ("xml", "프리미어 XML"), ("edl", "프리미어 EDL"),
        ("srt_source", "자막(원본 기준)"), ("srt_edited", "자막(편집본 기준)"),
        ("chapters", "유튜브 챕터"), ("plan", "편집 계획(plan.json)"),
    ]:
        if key in result["files"]:
            print(f"  - {label}: {result['files'][key]}")
    print()
    print("프리미어에서 이어서 다듬기: Premiere Pro > 파일 > 가져오기 > '프리미어.xml' 선택")
    return 0


def _cmd_analyze(args: argparse.Namespace) -> int:
    from .ffmpeg import probe
    from .scenes import detect_scenes
    from .silence import detect_silences, keep_intervals

    info = probe(args.video)
    print(f"파일      : {info.path}")
    print(f"길이      : {info.duration:.1f}초")
    print(f"해상도    : {info.width}x{info.height} @ {info.fps:.2f}fps")
    print(f"코덱      : 영상 {info.vcodec or '?'} / 오디오 {info.acodec or '없음'}")
    if info.has_audio:
        silences = detect_silences(args.video, args.noise_db, args.min_silence, info.duration)
        keeps = keep_intervals(silences, info.duration)
        removed = info.duration - sum(b - a for a, b in keeps)
        print(f"무음      : {len(silences)}곳, 총 {removed:.1f}초 (기준 {args.noise_db}dB / {args.min_silence}초)")
        print(f"           무음 컷만 해도 {info.duration:.1f}초 -> {info.duration - removed:.1f}초")
    else:
        print("무음      : 오디오 트랙 없음")
    times = detect_scenes(args.video)
    print(f"장면 전환 : {len(times)}곳")
    return 0


def _cmd_subtitles(args: argparse.Namespace) -> int:
    from .subtitles import transcribe, write_srt

    captions = transcribe(args.video, model_size=args.whisper_model,
                          language=args.language, log=print)
    if not captions:
        print("인식된 음성이 없습니다.")
        return 1
    out = args.output or str(Path(args.video).with_suffix(".srt"))
    write_srt(captions, out)
    print(f"자막 {len(captions)}줄 저장: {out}")
    print("프리미어에서: 파일 > 가져오기로 SRT를 불러온 뒤 타임라인의 캡션 트랙에 올리세요.")
    return 0


def _cmd_premiere(args: argparse.Namespace) -> int:
    from .plan import EditPlan
    from .premiere import write_premiere_bundle

    plan = EditPlan.load(args.plan)
    outdir = args.output or str(Path(args.plan).parent)
    files = write_premiere_bundle(plan, outdir)
    for key, path in files.items():
        print(f"  - {key}: {path}")
    return 0


def _cmd_web(args: argparse.Namespace) -> int:
    from .server import serve

    serve(host=args.host, port=args.port, workspace=args.workspace,
          open_browser=not args.no_browser)
    return 0


def _cmd_doctor(args: argparse.Namespace) -> int:
    import shutil

    print(f"PreCut AI v{__version__} 환경 점검")
    print("-" * 46)

    ffmpeg = shutil.which(os.environ.get("PRECUT_FFMPEG", "") or "ffmpeg")
    ffprobe = shutil.which(os.environ.get("PRECUT_FFPROBE", "") or "ffprobe")
    print(f"[{'OK' if ffmpeg else '!!'}] ffmpeg  : {ffmpeg or '없음 - 설치 필요 (필수)'}")
    print(f"[{'OK' if ffprobe else '!!'}] ffprobe : {ffprobe or '없음 - ffmpeg와 함께 설치됨 (필수)'}")

    try:
        import faster_whisper  # noqa: F401
        print("[OK] AI 자막  : faster-whisper 설치됨")
    except ImportError:
        print("[--] AI 자막  : 선택 기능. 쓰려면  pip install faster-whisper")

    try:
        import anthropic  # noqa: F401
        has_pkg = True
    except ImportError:
        has_pkg = False
    has_key = bool(os.environ.get("ANTHROPIC_API_KEY") or os.environ.get("ANTHROPIC_AUTH_TOKEN"))
    if has_pkg and has_key:
        print("[OK] AI 지시문: Claude 사용 가능")
    elif has_pkg:
        print("[--] AI 지시문: anthropic 설치됨, ANTHROPIC_API_KEY 없음 -> 규칙 기반으로 동작")
    else:
        print("[--] AI 지시문: 선택 기능. 쓰려면  pip install anthropic  + API 키 설정")
        print("               (키가 없어도 규칙 기반 해석으로 동작합니다)")

    print("-" * 46)
    print("필수 항목이 [OK]이면 바로 쓸 수 있습니다.  precut web  으로 시작하세요.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="precut",
        description="PreCut AI - 프리미어의 액기스만 뽑은 AI 자동 컷 편집기",
    )
    parser.add_argument("--version", action="version", version=f"precut-ai {__version__}")
    sub = parser.add_subparsers(dest="command")

    def add_common_detect(p: argparse.ArgumentParser) -> None:
        p.add_argument("--noise-db", type=float, default=-35.0,
                       help="무음 판정 기준 dB (기본 -35, 낮출수록 덜 잘림)")
        p.add_argument("--min-silence", type=float, default=0.6,
                       help="이 길이(초) 이상 무음일 때만 컷 (기본 0.6)")

    p = sub.add_parser("auto", help="영상 하나를 통째로 자동 편집 (무음컷/하이라이트/자막/렌더/프리미어)")
    p.add_argument("video", help="원본 영상 파일")
    p.add_argument("-o", "--output", help="결과 폴더 (기본: <영상이름>_precut)")
    p.add_argument("-p", "--prompt", "--instruction", dest="prompt",
                   help='AI에게 맡길 지시문. 예: "1분 하이라이트로 만들고 자막 넣어줘"')
    p.add_argument("--target", type=float, help="목표 길이(초). 지정하면 하이라이트 모드")
    p.add_argument("--subtitles", action="store_true", help="AI 자막 생성(faster-whisper 필요)")
    p.add_argument("--whisper-model", default="small",
                   help="자막 모델 크기: tiny/base/small/medium/large-v3 (기본 small)")
    p.add_argument("--language", help="자막 언어 코드(ko, en 등. 기본 자동 감지)")
    p.add_argument("--keep-silence", action="store_true", help="무음 컷 끄기")
    p.add_argument("--no-render", action="store_true", help="완성본 MP4를 만들지 않음(프리미어 파일만)")
    p.add_argument("--no-premiere", action="store_true", help="프리미어 XML/EDL을 만들지 않음")
    p.add_argument("--no-ai", action="store_true", help="지시문을 Claude 없이 규칙 기반으로만 해석")
    add_common_detect(p)
    p.set_defaults(func=_cmd_auto)

    p = sub.add_parser("analyze", help="자르지 않고 분석 결과만 보기")
    p.add_argument("video")
    add_common_detect(p)
    p.set_defaults(func=_cmd_analyze)

    p = sub.add_parser("subtitles", help="AI 자막(SRT)만 만들기")
    p.add_argument("video")
    p.add_argument("-o", "--output", help="저장할 SRT 경로")
    p.add_argument("--whisper-model", default="small")
    p.add_argument("--language", help="언어 코드(기본 자동 감지)")
    p.set_defaults(func=_cmd_subtitles)

    p = sub.add_parser("premiere", help="저장된 plan.json에서 프리미어 XML/EDL 다시 만들기")
    p.add_argument("plan", help="plan.json 경로")
    p.add_argument("-o", "--output", help="저장 폴더")
    p.set_defaults(func=_cmd_premiere)

    p = sub.add_parser("web", help="브라우저 화면 켜기 (드래그&드롭으로 편집)")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--workspace", help="작업 폴더 (기본: 내 문서 옆 PreCutAI)")
    p.add_argument("--no-browser", action="store_true", help="브라우저 자동 열기 끄기")
    p.set_defaults(func=_cmd_web)

    p = sub.add_parser("doctor", help="실행 환경 점검")
    p.set_defaults(func=_cmd_doctor)

    return parser


def main(argv: Optional[List[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "func", None):
        parser.print_help()
        return 0
    try:
        return args.func(args)
    except ToolError as exc:
        print(f"\n문제가 생겼어요:\n{exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n중단했습니다.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
