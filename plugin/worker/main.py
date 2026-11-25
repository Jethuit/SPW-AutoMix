"""Pipe-only analysis and audition worker launched by the native SPW editor."""
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
from types import SimpleNamespace

from analyze import analyze_track, plan_from_analysis
from editor_server import waveform
from render import render


def track_data(path, cache):
    stat = path.stat()
    identity = (str(path.resolve()), stat.st_size, stat.st_mtime_ns, "analysis-wave-v3")
    key = hashlib.sha256(json.dumps(identity).encode()).hexdigest()
    cache.mkdir(parents=True, exist_ok=True)
    dest = cache / (key + ".json")
    try:
        data = json.loads(dest.read_bytes())
        if data["analysis"]["duration"] > 0 and data.get("peaks") and data.get("low"):
            return data
    except (OSError, ValueError, TypeError, KeyError):
        pass

    analysis = analyze_track(path)
    wf = waveform(str(path), stat.st_size, stat.st_mtime_ns)

    def downsample_20ms(arr):
        return [max(arr[i:i + 2]) for i in range(0, len(arr), 2)]

    data = {
        "analysis": analysis,
        "peaks": downsample_20ms(wf["peaks"]),
        "low": downsample_20ms(wf["low"]),
        "mid": downsample_20ms(wf["mid"]),
        "high": downsample_20ms(wf["high"]),
    }
    current = path.stat()
    if (current.st_size, current.st_mtime_ns) != identity[1:3]:
        raise ValueError("分析期间音频文件已改变")
    with tempfile.NamedTemporaryFile(dir=cache, delete=False, suffix=".tmp", mode="w", encoding="utf-8") as f:
        name = f.name
        try:
            json.dump(data, f, separators=(",", ":"), allow_nan=False)
            f.close()
            os.replace(name, dest)
        finally:
            f.close()
            Path(name).unlink(missing_ok=True)
    return data


def _map_to_spw_plan(raw_plan):
    """Map prototype auto_plan options onto capabilities natively supported by SPW Pisces."""
    p = dict(raw_plan)
    eq = p.get("eq", "bass-swap")
    if eq not in ("none", "bass-swap-start", "bass-swap", "bass-swap-end", "fast-bass-cut"):
        eq = "bass-swap"
    filt = p.get("filter", "none")
    if filt in ("high-out", "high-out-half", "both-high", "both-high-io"):
        filt = "low-out"
    elif filt == "high-in":
        filt = "low-in"
    elif filt not in ("none", "low-out", "low-in", "both-low"):
        filt = "none"
    vol = p.get("volume", "smooth")
    if vol not in ("smooth", "full-overlap", "overlap", "fade-cut", "cut-fade", "mid-cut", "linear"):
        vol = "smooth"
    p["eq"] = eq
    p["bass"] = eq != "none"
    p["filter"] = filt
    p["volume"] = vol
    return p


def _resolve_spw_db(explicit_path):
    if explicit_path:
        p = Path(explicit_path)
        if p.is_file():
            return p
    candidates = []
    if os.name == "nt":
        appdata = os.environ.get("APPDATA", "").strip()
        if appdata:
            candidates.append(Path(appdata) / "Salt Player for Windows" / "spw.db")
        candidates.append(Path.home() / "AppData" / "Roaming" / "Salt Player for Windows" / "spw.db")
    else:
        candidates.append(Path.home() / ".local" / "share" / "Salt Player" / "sp.db")
    for c in candidates:
        if c.is_file():
            return c
    return None


def _sort_key_for_track_sort(track_sort):
    if track_sort == 1:
        return lambda r: (r[3] or "").lower()
    if track_sort == 2:
        return lambda r: os.path.basename(r[1] or "").lower()
    if track_sort == 3:
        return lambda r: (r[5] or "").lower()
    if track_sort == 4:
        return lambda r: (r[4] or "").lower()
    if track_sort == 5:
        return lambda r: r[6] or 0
    if track_sort == 6:
        return lambda r: r[7] or 0
    if track_sort == 7:
        return lambda r: os.path.dirname(r[1] or "").lower()
    if track_sort == 8:
        return lambda r: r[8] or 0
    if track_sort == 9:
        return lambda r: r[9] or 0
    if track_sort == 10:
        return lambda r: r[10] or 0
    if track_sort == 11:
        return lambda r: r[11] or 0
    if track_sort == 12:
        return lambda r: r[13] or 0
    return lambda r: r[12] or 0


def load_playlists(db_path=None):
    path = _resolve_spw_db(db_path)
    if path is None:
        return {"playlists": [], "error": "未找到 SPW 歌单数据库"}
    con = None
    try:
        con = sqlite3.connect(f"file:{path}?mode=ro", uri=True, timeout=2.0)
        cur = con.cursor()
        rows = cur.execute(
            'SELECT id, title, "order", trackSort, trackSortDescending '
            'FROM Playlist ORDER BY "order" DESC, createdTime DESC'
        ).fetchall()
        playlists = []
        for pid, title, _order, track_sort, desc_flag in rows:
            trows = cur.execute(
                """
                SELECT t.id, t.path, pt.syncTrackId, t.title, t.artist, t.album,
                       t.size, t.year, t.playCount, t.duration, t.modifiedTime, t.addedTime,
                       pt."order", pt.addedTime
                FROM PlaylistTrack pt
                JOIN SyncTrack st ON st.id = pt.syncTrackId
                JOIN Track t ON t.id = COALESCE(NULLIF(st.playbackTrackId, ''), st.primaryTrackId)
                WHERE pt.playlistId = ? AND pt.isRemoved = 0
                """,
                (pid,),
            ).fetchall()
            trows.sort(key=_sort_key_for_track_sort(track_sort), reverse=bool(desc_flag))
            tracks = [{"id": r[0], "path": r[1], "syncTrackId": r[2]} for r in trows if r[0] and r[1] and r[2]]
            playlists.append({
                "id": pid,
                "title": title,
                "trackSort": int(track_sort or 0),
                "descending": bool(desc_flag),
                "tracks": tracks,
            })
        return {"playlists": playlists}
    except (sqlite3.Error, OSError) as exc:
        return {"playlists": [], "error": str(exc)}
    finally:
        if con is not None:
            con.close()


def main():
    job = json.load(sys.stdin)
    op = job.get("op")
    if op == "playlists":
        print(json.dumps(load_playlists(job.get("db")), allow_nan=False))
        return
    a, b = Path(job["a"]), Path(job["b"])
    cache = Path(job["cache"])
    if op == "analyze":
        first, second = track_data(a, cache), track_data(b, cache)
        result = plan_from_analysis(first["analysis"], second["analysis"])
        spw_plan = _map_to_spw_plan(result["plan"])
        out = dict(spw_plan)
        reasons = list(result.get("reasons", []))
        spw_filt = spw_plan["filter"]
        spw_eq = spw_plan["eq"]
        spw_vol = spw_plan["volume"]
        for idx, r in enumerate(reasons):
            if r.startswith("频段与半窗滤波：") or any(x in r for x in ("high-out", "high-in", "both-high")):
                prefix = r.split("。", 1)[0] if "。" in r else "频段与半窗滤波"
                filt_note = "（仅在交接半窗内扫频）" if spw_filt != "none" else "（无扫频滤波）"
                reasons[idx] = f"{prefix}。采用 {spw_vol} + {spw_eq} + {spw_filt}{filt_note}"
        out["reasons"] = reasons
        for source, suffix in ((first, "A"), (second, "B")):
            analysis = source["analysis"]
            out["wave" + suffix] = source["peaks"]
            out["low" + suffix] = source["low"]
            out["mid" + suffix] = source["mid"]
            out["high" + suffix] = source["high"]
            out["bpm" + suffix] = analysis["bpm"]
            out["lufs" + suffix] = analysis["lufs"]
            out["duration" + suffix] = analysis["duration"]
            out["beats" + suffix] = analysis["beats"]
            out["downbeats" + suffix] = analysis["downbeats"]
        print(json.dumps(out, allow_nan=False))
    elif job["op"] == "preview":
        p = job["plan"]
        window = job["window"]
        first, second = track_data(a, cache), track_data(b, cache)
        entry = window["entryMs"] / 1000.0
        available_b = (second["analysis"]["duration"] - entry) / window["speed"]
        args = SimpleNamespace(
            outgoing=a,
            incoming=b,
            output=Path(job["output"]),
            bpm=p["bpmA"],
            beats_per_bar=4,
            bars=p["bars"],
            exit_start=window["startMs"] / 1000.0,
            entry=entry,
            context=window["beforeMs"] / 1000.0,
            context_after=min(10.0, max(0.0, available_b - window["durationMs"] / 1000.0)),
            gain_a=0.0,
            gain_b=0.0,
            bass_a=-24.0,
            bass_b=-24.0,
        )
        filt = p.get("filter", "none")
        eq_mode = p.get("eq", "bass-swap" if p.get("bass", True) else "none")
        report = render(
            args,
            volume=p["volume"],
            eq=eq_mode,
            swap_at=p["swap"],
            filter_a="low-out" if filt in ("low-out", "both-low") else "none",
            filter_b="low-in" if filt in ("low-in", "both-low") else "none",
            effect="echo" if p.get("echo") else "none",
            speed_b=window["speed"],
            loudness_match=True,
            pcm16=True,
            lufs_a_ref=first["analysis"]["lufs"],
            lufs_b_ref=second["analysis"]["lufs"],
            window=window,
        )
        print(json.dumps({"context": args.context, "context_after": args.context_after, "report": report}, allow_nan=False))
    else:
        raise ValueError("Unknown operation")


if __name__ == "__main__":
    main()
