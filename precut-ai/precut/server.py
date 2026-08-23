"""브라우저 UI용 로컬 웹 서버 — 파이썬 표준 라이브러리만 사용한다."""
from __future__ import annotations

import json
import mimetypes
import os
import re
import shutil
import threading
import time
import traceback
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Dict, Optional
from urllib.parse import parse_qs, quote, unquote, urlparse

from . import __version__, assistant
from .ffmpeg import ToolError, probe
from .pipeline import PipelineOptions, run_pipeline

_STATIC_DIR = Path(__file__).parent / "static"


class Job:
    def __init__(self, job_id: str):
        self.id = job_id
        self.status = "running"  # running | done | error
        self.lines: list = []
        self.result: Optional[dict] = None
        self.error: Optional[str] = None
        self._lock = threading.Lock()

    def log(self, line: str) -> None:
        with self._lock:
            self.lines.append(line)

    def snapshot(self, since: int) -> dict:
        with self._lock:
            return {
                "id": self.id,
                "status": self.status,
                "lines": self.lines[since:],
                "total_lines": len(self.lines),
                "result": self.result,
                "error": self.error,
            }


_JOBS: Dict[str, Job] = {}
_JOBS_LOCK = threading.Lock()


def _safe_name(name: str) -> str:
    base = Path(unquote(name or "")).name
    base = re.sub(r"[^\w가-힣ㄱ-ㅎㅏ-ㅣ .()\[\]\-]", "_", base).strip(". ")
    return base or "영상.mp4"


def _pick_options(raw: dict) -> PipelineOptions:
    opt = PipelineOptions()
    opt.instruction = str(raw.get("instruction", "") or "")[:2000]
    opt.use_assistant = bool(raw.get("use_assistant", True))
    opt.remove_silence = bool(raw.get("remove_silence", True))
    opt.subtitles = bool(raw.get("subtitles", False))
    opt.render = bool(raw.get("render", True))
    opt.premiere = bool(raw.get("premiere", True))
    target = raw.get("target_duration")
    if target not in (None, "", 0, "0"):
        try:
            opt.target_duration = max(3.0, min(7200.0, float(target)))
        except (TypeError, ValueError):
            pass
    try:
        opt.noise_db = max(-80.0, min(-10.0, float(raw.get("noise_db", -35.0))))
    except (TypeError, ValueError):
        pass
    try:
        opt.min_silence = max(0.1, min(5.0, float(raw.get("min_silence", 0.6))))
    except (TypeError, ValueError):
        pass
    model = str(raw.get("whisper_model", "small"))
    if model in ("tiny", "base", "small", "medium", "large-v3"):
        opt.whisper_model = model
    lang = str(raw.get("language", "") or "").strip()
    if lang:
        opt.language = lang[:8]
    return opt


class Handler(BaseHTTPRequestHandler):
    workspace: Path = Path.cwd()
    protocol_version = "HTTP/1.1"
    server_version = f"PreCutAI/{__version__}"

    # ---------- 공통 응답 도우미 ----------
    def log_message(self, fmt, *args):  # 기본 액세스 로그는 조용히
        pass

    def _send_json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _send_bytes(self, code: int, data: bytes, ctype: str) -> None:
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length) if length > 0 else b""

    def _resolve_in_workspace(self, rel: str) -> Optional[Path]:
        try:
            target = (self.workspace / rel).resolve()
            target.relative_to(self.workspace.resolve())
        except (ValueError, OSError):
            return None
        return target

    # ---------- 라우팅 ----------
    def do_GET(self):  # noqa: N802
        try:
            url = urlparse(self.path)
            path = url.path
            if path in ("/", "/index.html"):
                page = (_STATIC_DIR / "index.html").read_bytes()
                self._send_bytes(200, page, "text/html; charset=utf-8")
            elif path == "/api/doctor":
                self._api_doctor()
            elif path.startswith("/api/jobs/"):
                self._api_job_status(path[len("/api/jobs/"):], url)
            elif path.startswith("/files/"):
                query = parse_qs(url.query)
                self._serve_file(unquote(path[len("/files/"):]), download="dl" in query)
            else:
                self._send_json(404, {"error": "없는 주소입니다"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            traceback.print_exc()
            try:
                self._send_json(500, {"error": "서버 내부 오류"})
            except Exception:
                pass

    def do_POST(self):  # noqa: N802
        try:
            url = urlparse(self.path)
            if url.path == "/api/upload":
                self._api_upload(url)
            elif url.path == "/api/jobs":
                self._api_start_job()
            else:
                self._send_json(404, {"error": "없는 주소입니다"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            traceback.print_exc()
            try:
                self._send_json(500, {"error": "서버 내부 오류"})
            except Exception:
                pass

    # ---------- API 구현 ----------
    def _api_doctor(self) -> None:
        try:
            import faster_whisper  # noqa: F401
            whisper_ok = True
        except ImportError:
            whisper_ok = False
        self._send_json(200, {
            "version": __version__,
            "ffmpeg": bool(shutil.which(os.environ.get("PRECUT_FFMPEG", "") or "ffmpeg")),
            "whisper": whisper_ok,
            "claude": assistant.claude_ready(),
            "workspace": str(self.workspace),
        })

    def _api_upload(self, url) -> None:
        query = parse_qs(url.query)
        name = _safe_name((query.get("name") or [""])[0])
        uploads = self.workspace / "업로드"
        uploads.mkdir(parents=True, exist_ok=True)

        target = uploads / name
        stem, suffix = target.stem, target.suffix
        counter = 1
        while target.exists():
            counter += 1
            target = uploads / f"{stem}({counter}){suffix}"

        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            self._send_json(400, {"error": "빈 업로드입니다"})
            return
        remaining = length
        with open(target, "wb") as f:
            while remaining > 0:
                chunk = self.rfile.read(min(1024 * 1024, remaining))
                if not chunk:
                    break
                f.write(chunk)
                remaining -= len(chunk)
        if remaining > 0:
            target.unlink(missing_ok=True)
            self._send_json(400, {"error": "업로드가 중간에 끊겼습니다"})
            return

        try:
            info = probe(target)
        except ToolError as exc:
            target.unlink(missing_ok=True)
            self._send_json(400, {"error": f"영상 파일이 아니거나 열 수 없습니다.\n{exc}"})
            return

        rel = target.relative_to(self.workspace).as_posix()
        self._send_json(200, {
            "path": rel,
            "name": target.name,
            "info": {
                "duration": round(info.duration, 2),
                "width": info.width,
                "height": info.height,
                "fps": round(info.fps, 2),
                "has_audio": info.has_audio,
            },
        })

    def _api_start_job(self) -> None:
        try:
            payload = json.loads(self._read_body().decode("utf-8") or "{}")
        except json.JSONDecodeError:
            self._send_json(400, {"error": "요청 형식이 잘못됐습니다"})
            return
        rel = str(payload.get("path", ""))
        video = self._resolve_in_workspace(rel)
        if not video or not video.is_file():
            self._send_json(400, {"error": "먼저 영상을 올려 주세요"})
            return
        opt = _pick_options(payload.get("options") or {})

        job_id = time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:6]
        job = Job(job_id)
        with _JOBS_LOCK:
            _JOBS[job_id] = job
        outdir = self.workspace / "결과" / f"작업-{job_id}"
        workspace = self.workspace

        def runner() -> None:
            try:
                result = run_pipeline(video, outdir, opt, log=job.log)
                rel_files = {}
                for key, value in result["files"].items():
                    try:
                        rel_files[key] = Path(value).resolve().relative_to(
                            workspace.resolve()).as_posix()
                    except ValueError:
                        rel_files[key] = value
                result["files"] = rel_files
                job.result = result
                job.status = "done"
            except ToolError as exc:
                job.error = str(exc)
                job.status = "error"
                job.log(f"오류: {exc}")
            except Exception as exc:  # 예상 못한 오류도 사용자에게 보여준다
                job.error = f"예상하지 못한 오류: {exc}"
                job.status = "error"
                job.log(job.error)
                traceback.print_exc()

        threading.Thread(target=runner, daemon=True).start()
        self._send_json(200, {"job": job_id})

    def _api_job_status(self, job_id: str, url) -> None:
        query = parse_qs(url.query)
        try:
            since = int((query.get("since") or ["0"])[0])
        except ValueError:
            since = 0
        with _JOBS_LOCK:
            job = _JOBS.get(job_id.strip("/"))
        if not job:
            self._send_json(404, {"error": "작업을 찾을 수 없습니다"})
            return
        self._send_json(200, job.snapshot(max(0, since)))

    # ---------- 파일 서빙(Range 지원) ----------
    def _serve_file(self, rel: str, download: bool) -> None:
        target = self._resolve_in_workspace(rel)
        if not target or not target.is_file():
            self._send_json(404, {"error": "파일이 없습니다"})
            return
        size = target.stat().st_size
        suffix = target.suffix.lower()
        if suffix in (".srt", ".edl", ".txt"):
            ctype = "text/plain; charset=utf-8"
        elif suffix == ".xml":
            ctype = "application/xml; charset=utf-8"
        elif suffix == ".json":
            ctype = "application/json; charset=utf-8"
        else:
            ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"

        start, end, status = 0, size - 1, 200
        range_header = (self.headers.get("Range") or "").strip()
        m = re.match(r"bytes=(\d*)-(\d*)$", range_header)
        if m and (m.group(1) or m.group(2)):
            if m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = int(m.group(2))
            else:  # bytes=-N (마지막 N바이트)
                start = max(0, size - int(m.group(2)))
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, size - 1)
            status = 206

        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        if download:
            self.send_header(
                "Content-Disposition",
                f"attachment; filename*=UTF-8''{quote(target.name)}",
            )
        self.end_headers()

        with open(target, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


def serve(
    host: str = "127.0.0.1",
    port: int = 8765,
    workspace: Optional[str] = None,
    open_browser: bool = True,
) -> None:
    ws = Path(workspace).expanduser() if workspace else Path.home() / "PreCutAI"
    (ws / "업로드").mkdir(parents=True, exist_ok=True)
    (ws / "결과").mkdir(parents=True, exist_ok=True)
    Handler.workspace = ws

    httpd = ThreadingHTTPServer((host, port), Handler)
    url = f"http://{host}:{port}"
    print()
    print("=" * 52)
    print("  PreCut AI - 프리미어 액기스 + AI 자동 컷 편집")
    print(f"  브라우저에서 열기 : {url}")
    print(f"  작업 폴더        : {ws}")
    print("  끝내려면 Ctrl+C")
    print("=" * 52)
    print()
    if open_browser:
        threading.Timer(0.8, webbrowser.open, [url]).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n서버를 종료합니다.")
    finally:
        httpd.server_close()
