"""Local two-deck AutoMix demo workbench server. Run with: py -3 prototype/editor_server.py"""

import argparse
from functools import lru_cache
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
from threading import BoundedSemaphore
from types import SimpleNamespace
from urllib.parse import parse_qs, urlparse

import numpy as np
from scipy import signal

from analyze import analyze_track, auto_plan
from render import render


AUDIO_EXTS = {".flac", ".wav", ".mp3", ".m4a", ".ogg"}

# Curated Spotify reference pairs: (title, artist, bpm, key, Bar-1 downbeat-locked cue_s)
PAIRS = [
    (("夏夜", "四季音色", 100.0, "3B", 166.818), ("Inspire", "Capo Productions", 100.0, "2B", 14.646), 4, "smooth", "none"),
    (("V", "Marcin Przybyłowicz", 90.0, "11B", 131.654), ("The Rebel Path", "P.T. Adamczyk", 90.0, "11B", 0.499), 8, "fade-cut", "high-out"),
    (("Akane", "DJ Okawari", 94.0, "8A", 268.300), ("Moon Flow", "Nomak", 93.0, "8A", 0.377), 8, "fade-cut", "low-out"),
    (("Her Eyes", "Narvent", 96.0, "12A", 232.432), ("Duality", "Neon Nox Powernerd", 105.0, "10A", 13.155), 4, "smooth", "none"),
    (("Without Me", "Eminem", 112.27, "5B", 272.643), ("Billie Jean", "Michael Jackson", 117.13, "11A", 17.674), 4, "overlap", "both-high-io"),
]
WEB = Path(__file__).with_name("editor")
SLOTS = BoundedSemaphore(1)

_WAVE_LOW_SOS = signal.butter(2, 220.0, btype="low", fs=16000, output="sos")
_WAVE_MID_SOS = signal.butter(2, [220.0, 2500.0], btype="bandpass", fs=16000, output="sos")
_WAVE_HIGH_SOS = signal.butter(2, 2500.0, btype="high", fs=16000, output="sos")


@lru_cache(maxsize=24)
def waveform(file_str, size, mtime):
    raw = subprocess.check_output([
        "ffmpeg", "-nostdin", "-v", "error", "-i", file_str, "-map", "0:a:0",
        "-ac", "1", "-ar", "16000", "-f", "f32le", "pipe:1",
    ])
    samples = np.frombuffer(raw, "<f4").astype(np.float64)
    bucket = 160  # 10 ms at 16 kHz
    pad = (-len(samples)) % bucket

    def bucket_u8(sig):
        padded = np.pad(np.abs(sig), (0, pad))
        vals = np.sqrt(padded.reshape(-1, bucket).max(axis=1)) * 255.0
        return np.minimum(255, vals).astype(np.uint8).tolist()

    return {
        "duration": len(samples) / 16000.0,
        "peaks": bucket_u8(samples),
        "low": bucket_u8(signal.sosfilt(_WAVE_LOW_SOS, samples)),
        "mid": bucket_u8(signal.sosfilt(_WAVE_MID_SOS, samples)),
        "high": bucket_u8(signal.sosfilt(_WAVE_HIGH_SOS, samples)),
    }


def number(data, key, low, high):
    value = float(data[key])
    if not math.isfinite(value) or not low <= value <= high:
        raise ValueError(f"{key} 超出范围")
    return value


def choice(data, key, allowed):
    value = data[key]
    if value not in allowed:
        raise ValueError(f"无效参数 {key}")
    return value


def build_index(music_dir):
    files = sorted(
        (p for p in music_dir.iterdir() if p.is_file() and p.suffix.lower() in AUDIO_EXTS),
        key=lambda p: p.name.casefold(),
    )
    library = []
    by_norm_stem = {}
    for fid, path in enumerate(files):
        stem = " ".join(path.stem.split())
        parts = stem.split(" - ", 1)
        artist = parts[0] if len(parts) == 2 else "本地曲目"
        title = parts[1] if len(parts) == 2 else stem
        library.append({
            "id": fid,
            "title": title,
            "artist": artist,
            "filename": path.name,
            "path": path,
        })
        by_norm_stem[stem.casefold()] = fid

    pair_files = {}
    for i, pair in enumerate(PAIRS):
        for j, (title, artist, *_) in enumerate(pair[:2]):
            key = f"{artist} - {title}".casefold()
            if key in by_norm_stem:
                pair_files[i, j] = by_norm_stem[key]
    return library, pair_files


class Editor(BaseHTTPRequestHandler):
    def respond(self, payload, mime, status=200, headers=None):
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(payload)))
        for k, v in (headers or {}).items():
            self.send_header(k, str(v))
        self.end_headers()
        self.wfile.write(payload)

    def json(self, data, status=200):
        self.respond(json.dumps(data, ensure_ascii=False).encode(), "application/json; charset=utf-8", status)

    def _resolve_file_id(self, fid):
        if not 0 <= fid < len(self.server.library):
            raise KeyError("无效曲目编号")
        return self.server.library[fid]["path"]

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        qs = parse_qs(parsed.query)
        try:
            if path == "/api/pairs":
                payload = []
                for i, pair in enumerate(PAIRS):
                    a, b, bars, volume, filt = pair
                    payload.append({
                        "index": i,
                        "a": list(a) + [self.server.pair_files.get((i, 0))],
                        "b": list(b) + [self.server.pair_files.get((i, 1))],
                        "bars": bars,
                        "volume": volume,
                        "filter": filt,
                    })
                return self.json(payload)

            if path == "/api/library":
                return self.json([
                    {"id": item["id"], "title": item["title"], "artist": item["artist"], "filename": item["filename"]}
                    for item in self.server.library
                ])

            if path.startswith("/api/wave/"):
                parts = [p for p in path.split("/") if p]
                if len(parts) == 3:
                    file = self._resolve_file_id(int(parts[2]))
                elif len(parts) == 4:
                    fid = self.server.pair_files[int(parts[2]), int(parts[3])]
                    file = self._resolve_file_id(fid)
                else:
                    raise KeyError("无效波形路径")
                stat = file.stat()
                return self.json(waveform(str(file.resolve()), stat.st_size, stat.st_mtime_ns))

            if path.startswith("/api/analyze/"):
                parts = [p for p in path.split("/") if p]
                known_bpm = float(qs["bpm"][0]) if "bpm" in qs else None
                if len(parts) == 3:
                    file = self._resolve_file_id(int(parts[2]))
                elif len(parts) == 4:
                    i, j = int(parts[2]), int(parts[3])
                    fid = self.server.pair_files[i, j]
                    file = self._resolve_file_id(fid)
                    if known_bpm is None:
                        known_bpm = float(PAIRS[i][j][2])
                else:
                    raise KeyError("无效分析路径")
                ana = analyze_track(file, known_bpm=known_bpm)
                return self.json({
                    "duration": ana["duration"],
                    "bpm": ana["bpm"],
                    "bpm_confidence": ana["bpm_confidence"],
                    "beats": ana["beats"],
                    "downbeats": ana["downbeats"],
                    "lufs": ana["lufs"],
                })

            pages = {
                "/": ("index.html", "text/html"),
                "/editor.js": ("editor.js", "text/javascript"),
                "/editor.css": ("editor.css", "text/css"),
            }
            filename, mime = pages[path]
            self.respond((WEB / filename).read_bytes(), mime + "; charset=utf-8")
        except (ValueError, KeyError, OSError, subprocess.SubprocessError) as error:
            self.json({"error": str(error)}, 404)

    def _resolve_pair_inputs(self, data):
        if "fileA" in data and "fileB" in data and data["fileA"] is not None and data["fileB"] is not None:
            fid_a = int(data["fileA"])
            fid_b = int(data["fileB"])
            path_a = self._resolve_file_id(fid_a)
            path_b = self._resolve_file_id(fid_b)
            bpm_a = float(data["bpmA"]) if data.get("bpmA") else None
            bpm_b = float(data["bpmB"]) if data.get("bpmB") else None
            return path_a, path_b, bpm_a, bpm_b
        i = choice(data, "pair", range(len(PAIRS)))
        a, b, *_ = PAIRS[i]
        path_a = self._resolve_file_id(self.server.pair_files[i, 0])
        path_b = self._resolve_file_id(self.server.pair_files[i, 1])
        return path_a, path_b, float(a[2]), float(b[2])

    def do_POST(self):
        if self.path not in ("/api/render", "/api/autoplan"):
            return self.send_error(404)
        if self.headers.get_content_type() != "application/json":
            return self.send_error(415)
        try:
            length = int(self.headers.get("Content-Length", 0))
            if not 0 < length <= 8192:
                raise ValueError("无效请求长度")
            data = json.loads(self.rfile.read(length))
            path_a, path_b, bpm_a, bpm_b = self._resolve_pair_inputs(data)

            if self.path == "/api/autoplan":
                res = auto_plan(path_a, path_b, bpm_a=bpm_a, bpm_b=bpm_b)
                return self.json({
                    "plan": res["plan"],
                    "reasons": res["reasons"],
                    "bpmA": res["analysisA"]["bpm"],
                    "bpmB": res["analysisB"]["bpm"],
                })

            if bpm_a is None:
                bpm_a = analyze_track(path_a)["bpm"]
            if bpm_b is None:
                bpm_b = analyze_track(path_b)["bpm"]

            lufs_a_ref = None
            lufs_b_ref = None
            loudness_match = bool(data.get("loudnessMatch", False))
            if loudness_match:
                lufs_a_ref = analyze_track(path_a, known_bpm=bpm_a)["lufs"]
                lufs_b_ref = analyze_track(path_b, known_bpm=bpm_b)["lufs"]

            cue_a = number(data, "cueA", 0, 86400)
            cue_b = number(data, "cueB", 0, 86400)
            volume = choice(data, "volume", ["smooth", "full-overlap", "overlap", "fade-cut", "cut-fade", "mid-cut", "linear", "none"])
            eq = choice(data, "eq", ["bass-swap", "bass-swap-start", "bass-swap-end", "3band", "fast-bass-cut", "none"])
            filt = choice(data, "filter", ["none", "high-out", "high-out-half", "low-out", "high-in", "low-in", "both-low", "both-high", "both-high-io"])
            effect = choice(data, "effect", ["none", "echo", "reverb-mid", "reverb-end-cut", "reverb-end-fade"])
            loop = choice(data, "loop", [0, 1, 2, 4, 8])
            sync = bool(choice(data, "sync", [True, False, 0, 1]))
            pcm16 = bool(data.get("pcm16", False))
            speed = (bpm_a / bpm_b) if sync else 1.0
            swap = number(data, "swap", 0.05, 0.95)

            with SLOTS, TemporaryDirectory() as tmp:
                params = SimpleNamespace(
                    outgoing=path_a,
                    incoming=path_b,
                    output=Path(tmp) / "mix.wav",
                    bpm=bpm_a,
                    beats_per_bar=4,
                    context=4.0,
                    bars=choice(data, "bars", [2, 4, 8]),
                    exit_start=cue_a,
                    entry=cue_b,
                    gain_a=number(data, "gainA", -24, 6),
                    gain_b=number(data, "gainB", -24, 6),
                    bass_a=number(data, "bassA", -36, 0),
                    bass_b=number(data, "bassB", -36, 0),
                )
                filt_map = {
                    "none": ("none", "none"),
                    "high-out": ("high-out", "none"),
                    "high-out-half": ("high-out-half", "none"),
                    "low-out": ("low-out", "none"),
                    "high-in": ("none", "high-in"),
                    "low-in": ("none", "low-in"),
                    "both-low": ("low-out", "low-in"),
                    "both-high": ("high-out", "low-in"),
                    "both-high-io": ("high-out", "high-in"),
                }
                f_a, f_b = filt_map[filt]
                report = render(
                    params,
                    volume=volume,
                    eq=eq,
                    swap_at=swap,
                    filter_a=f_a,
                    filter_b=f_b,
                    effect=effect,
                    loop_beats=loop,
                    speed_b=speed,
                    loudness_match=loudness_match,
                    pcm16=pcm16,
                    lufs_a_ref=lufs_a_ref,
                    lufs_b_ref=lufs_b_ref,
                )
                content = params.output.read_bytes()

            self.respond(
                content,
                "audio/wav",
                headers={
                    "X-Overlap-Start": report["preview_mix_start_s"],
                    "X-Render-Report": json.dumps(report, ensure_ascii=True, separators=(",", ":")),
                },
            )
        except (ValueError, TypeError, KeyError, OSError, subprocess.SubprocessError) as error:
            self.json({"error": str(error)}, 400)


def create_server(music_dir=Path("E:/music"), port=18763):
    library, pair_files = build_index(music_dir)
    server = ThreadingHTTPServer(("127.0.0.1", port), Editor)
    server.library = library
    server.pair_files = pair_files
    return server


if __name__ == "__main__":
    cli = argparse.ArgumentParser(description=__doc__)
    cli.add_argument("--music", type=Path, default=Path("E:/music"))
    cli.add_argument("--port", type=int, default=18763)
    args = cli.parse_args()
    srv = create_server(args.music, args.port)
    print(f"http://127.0.0.1:{args.port}/ (indexed {len(srv.library)} tracks)", flush=True)
    srv.serve_forever()
