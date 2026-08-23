"""Premiere Pro로 가져갈 수 있는 파일 생성 — 여기가 '프리미어 액기스'다.

- FCP7 XML(xmeml v4): 프리미어의  파일 > 가져오기 로 시퀀스째 불러온다. (권장)
- EDL(CMX3600): 다른 편집 프로그램과도 호환되는 컷 목록.
- 챕터 텍스트: 유튜브 설명란에 붙여넣는 타임스탬프.
"""
from __future__ import annotations

from pathlib import Path
from typing import List, Tuple
from xml.sax.saxutils import escape

from .plan import EditPlan


def timebase_for_fps(fps: float) -> Tuple[int, bool]:
    """fps를 프리미어 타임베이스(정수)와 NTSC 여부로 바꾼다."""
    table = [
        (23.976, 24, True), (24.0, 24, False), (25.0, 25, False),
        (29.97, 30, True), (30.0, 30, False), (50.0, 50, False),
        (59.94, 60, True), (60.0, 60, False),
    ]
    for value, tb, ntsc in table:
        if abs(fps - value) < 0.02:
            return tb, ntsc
    return max(1, int(round(fps))), False


def to_frames(seconds: float, fps: float) -> int:
    return int(round(seconds * fps))


def frames_to_timecode(frames: int, timebase: int) -> str:
    ff = frames % timebase
    total_s = frames // timebase
    s = total_s % 60
    m = (total_s // 60) % 60
    h = total_s // 3600
    return f"{h:02d}:{m:02d}:{s:02d}:{ff:02d}"


def _rate_block(timebase: int, ntsc: bool, indent: str) -> str:
    flag = "TRUE" if ntsc else "FALSE"
    return (
        f"{indent}<rate>\n"
        f"{indent}\t<timebase>{timebase}</timebase>\n"
        f"{indent}\t<ntsc>{flag}</ntsc>\n"
        f"{indent}</rate>"
    )


def build_fcp7_xml(plan: EditPlan, sequence_name: str = "PreCut 편집본") -> str:
    """프리미어가 읽는 FCP7 XML(xmeml) 시퀀스를 만든다."""
    tb, ntsc = timebase_for_fps(plan.fps)
    fps = plan.fps
    src = Path(plan.source).resolve()
    pathurl = escape(src.as_uri())
    file_name = escape(src.name)
    seq_name = escape(sequence_name)
    total_frames = to_frames(plan.edited_duration(), fps)
    src_frames = to_frames(plan.duration, fps)

    def clipitems(kind: str) -> str:
        rows: List[str] = []
        timeline = 0
        for i, seg in enumerate(plan.segments):
            cid = f"clipitem-{kind}-{i + 1}"
            in_f = to_frames(seg.start, fps)
            out_f = to_frames(seg.end, fps)
            length = out_f - in_f
            if length <= 0:
                continue
            start_f = timeline
            end_f = timeline + length
            timeline = end_f
            name = escape(seg.label or f"{file_name} {i + 1}")
            if kind == "v" and i == 0:
                file_el = (
                    f"\t\t\t\t\t\t<file id=\"file-1\">\n"
                    f"\t\t\t\t\t\t\t<name>{file_name}</name>\n"
                    f"\t\t\t\t\t\t\t<pathurl>{pathurl}</pathurl>\n"
                    + _rate_block(tb, ntsc, "\t\t\t\t\t\t\t") + "\n"
                    f"\t\t\t\t\t\t\t<duration>{src_frames}</duration>\n"
                    f"\t\t\t\t\t\t\t<media>\n"
                    f"\t\t\t\t\t\t\t\t<video>\n"
                    f"\t\t\t\t\t\t\t\t\t<samplecharacteristics>\n"
                    f"\t\t\t\t\t\t\t\t\t\t<width>{plan.width}</width>\n"
                    f"\t\t\t\t\t\t\t\t\t\t<height>{plan.height}</height>\n"
                    f"\t\t\t\t\t\t\t\t\t</samplecharacteristics>\n"
                    f"\t\t\t\t\t\t\t\t</video>\n"
                    + (
                        "\t\t\t\t\t\t\t\t<audio>\n"
                        "\t\t\t\t\t\t\t\t\t<samplecharacteristics>\n"
                        "\t\t\t\t\t\t\t\t\t\t<depth>16</depth>\n"
                        "\t\t\t\t\t\t\t\t\t\t<samplerate>48000</samplerate>\n"
                        "\t\t\t\t\t\t\t\t\t</samplecharacteristics>\n"
                        "\t\t\t\t\t\t\t\t\t<channelcount>2</channelcount>\n"
                        "\t\t\t\t\t\t\t\t</audio>\n"
                        if plan.has_audio else ""
                    )
                    + "\t\t\t\t\t\t\t</media>\n"
                    f"\t\t\t\t\t\t</file>"
                )
            else:
                file_el = "\t\t\t\t\t\t<file id=\"file-1\"/>"
            sourcetrack = (
                "\t\t\t\t\t\t<sourcetrack>\n"
                f"\t\t\t\t\t\t\t<mediatype>{'video' if kind == 'v' else 'audio'}</mediatype>\n"
                "\t\t\t\t\t\t\t<trackindex>1</trackindex>\n"
                "\t\t\t\t\t\t</sourcetrack>"
            )
            link = (
                "\t\t\t\t\t\t<link>\n"
                f"\t\t\t\t\t\t\t<linkclipref>clipitem-v-{i + 1}</linkclipref>\n"
                "\t\t\t\t\t\t</link>\n"
                "\t\t\t\t\t\t<link>\n"
                f"\t\t\t\t\t\t\t<linkclipref>clipitem-a-{i + 1}</linkclipref>\n"
                "\t\t\t\t\t\t</link>"
            ) if plan.has_audio else ""
            rows.append(
                f"\t\t\t\t\t<clipitem id=\"{cid}\">\n"
                f"\t\t\t\t\t\t<name>{name}</name>\n"
                f"\t\t\t\t\t\t<enabled>TRUE</enabled>\n"
                f"\t\t\t\t\t\t<duration>{length}</duration>\n"
                + _rate_block(tb, ntsc, "\t\t\t\t\t\t") + "\n"
                f"\t\t\t\t\t\t<start>{start_f}</start>\n"
                f"\t\t\t\t\t\t<end>{end_f}</end>\n"
                f"\t\t\t\t\t\t<in>{in_f}</in>\n"
                f"\t\t\t\t\t\t<out>{out_f}</out>\n"
                f"{file_el}\n"
                f"{sourcetrack}\n"
                + (f"{link}\n" if link else "")
                + "\t\t\t\t\t</clipitem>"
            )
        return "\n".join(rows)

    audio_section = (
        "\t\t\t<audio>\n"
        "\t\t\t\t<numOutputChannels>2</numOutputChannels>\n"
        "\t\t\t\t<track>\n"
        f"{clipitems('a')}\n"
        "\t\t\t\t</track>\n"
        "\t\t\t</audio>\n"
        if plan.has_audio else ""
    )

    return (
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        "<!DOCTYPE xmeml>\n"
        "<xmeml version=\"4\">\n"
        "\t<sequence id=\"sequence-1\">\n"
        f"\t\t<name>{seq_name}</name>\n"
        f"\t\t<duration>{total_frames}</duration>\n"
        + _rate_block(tb, ntsc, "\t\t") + "\n"
        "\t\t<media>\n"
        "\t\t\t<video>\n"
        "\t\t\t\t<format>\n"
        "\t\t\t\t\t<samplecharacteristics>\n"
        + _rate_block(tb, ntsc, "\t\t\t\t\t\t") + "\n"
        f"\t\t\t\t\t\t<width>{plan.width}</width>\n"
        f"\t\t\t\t\t\t<height>{plan.height}</height>\n"
        "\t\t\t\t\t</samplecharacteristics>\n"
        "\t\t\t\t</format>\n"
        "\t\t\t\t<track>\n"
        f"{clipitems('v')}\n"
        "\t\t\t\t</track>\n"
        "\t\t\t</video>\n"
        f"{audio_section}"
        "\t\t</media>\n"
        "\t\t<timecode>\n"
        + _rate_block(tb, ntsc, "\t\t\t") + "\n"
        "\t\t\t<frame>0</frame>\n"
        "\t\t\t<displayformat>NDF</displayformat>\n"
        "\t\t</timecode>\n"
        "\t</sequence>\n"
        "</xmeml>\n"
    )


def build_edl(plan: EditPlan, title: str = "PRECUT") -> str:
    """CMX3600 EDL — 프리미어의  파일 > 가져오기 로 읽을 수 있다."""
    tb, _ = timebase_for_fps(plan.fps)
    fps = plan.fps
    lines = [f"TITLE: {title[:60]}", "FCM: NON-DROP FRAME", ""]
    record = 0
    clip_name = Path(plan.source).name
    channels = "AA/V" if plan.has_audio else "V"
    idx = 0
    for seg in plan.segments:
        src_in = to_frames(seg.start, fps)
        src_out = to_frames(seg.end, fps)
        if src_out <= src_in:
            continue
        idx += 1
        rec_in = record
        rec_out = record + (src_out - src_in)
        record = rec_out
        lines.append(
            f"{idx:03d}  AX       {channels:<5} C        "
            f"{frames_to_timecode(src_in, tb)} {frames_to_timecode(src_out, tb)} "
            f"{frames_to_timecode(rec_in, tb)} {frames_to_timecode(rec_out, tb)}"
        )
        lines.append(f"* FROM CLIP NAME: {clip_name}")
        lines.append("")
    return "\n".join(lines)


def build_chapters(plan: EditPlan) -> str:
    """유튜브 설명란용 챕터 타임스탬프."""
    rows: List[str] = []
    for seg, offset in zip(plan.segments, plan.offsets()):
        total_s = int(offset)
        if plan.edited_duration() >= 3600:
            stamp = f"{total_s // 3600}:{(total_s // 60) % 60:02d}:{total_s % 60:02d}"
        else:
            stamp = f"{total_s // 60:02d}:{total_s % 60:02d}"
        label = seg.label or "장면"
        rows.append(f"{stamp} {label} (원본 {int(seg.start // 60)}:{int(seg.start % 60):02d}~)")
    return "\n".join(rows) + ("\n" if rows else "")


def write_premiere_bundle(plan: EditPlan, outdir: "str | Path", sequence_name: str = "PreCut 편집본") -> dict:
    """XML/EDL/챕터를 한꺼번에 저장하고 경로를 돌려준다."""
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    xml_path = out / "프리미어.xml"
    edl_path = out / "프리미어.edl"
    chapters_path = out / "챕터.txt"
    xml_path.write_text(build_fcp7_xml(plan, sequence_name), encoding="utf-8")
    edl_path.write_text(build_edl(plan, sequence_name), encoding="utf-8")
    chapters_path.write_text(build_chapters(plan), encoding="utf-8")
    return {"xml": str(xml_path), "edl": str(edl_path), "chapters": str(chapters_path)}
