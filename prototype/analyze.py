"""Accurate beat, Bar-1 downbeat, BS.1770 LUFS analyzer and musical auto-planner."""

import argparse
from functools import lru_cache
import json
import math
from pathlib import Path
import subprocess
import sys

import numpy as np
from scipy import signal


ANA_RATE = 22050
HOP = 128
HOP_S = HOP / ANA_RATE


def _kweight_22050():
    """ITU-R BS.1770-4 K-weighting coefficients adapted for 22050 Hz."""
    fc_s = 1681.974450955533
    a_lin = 10.0 ** (3.999843853973347 / 40.0)
    w0 = 2.0 * math.pi * fc_s / ANA_RATE
    cw, sw = math.cos(w0), math.sin(w0)
    alpha = sw / math.sqrt(2.0)
    sqrt_a = math.sqrt(a_lin)
    a0 = (a_lin + 1.0) - (a_lin - 1.0) * cw + 2.0 * sqrt_a * alpha
    b0 = a_lin * ((a_lin + 1.0) + (a_lin - 1.0) * cw + 2.0 * sqrt_a * alpha) / a0
    b1 = -2.0 * a_lin * ((a_lin - 1.0) + (a_lin + 1.0) * cw) / a0
    b2 = a_lin * ((a_lin + 1.0) + (a_lin - 1.0) * cw - 2.0 * sqrt_a * alpha) / a0
    a1 = 2.0 * ((a_lin - 1.0) - (a_lin + 1.0) * cw) / a0
    a2 = ((a_lin + 1.0) - (a_lin - 1.0) * cw - 2.0 * sqrt_a * alpha) / a0
    shelf = np.array([[b0, b1, b2, 1.0, a1, a2]], dtype=np.float64)
    hp = signal.butter(2, 38.13547087602444, btype="high", fs=ANA_RATE, output="sos")
    return np.vstack([shelf, hp])


_KW_22K = _kweight_22050()


def _decode_mono_22k(path):
    cmd = [
        "ffmpeg", "-nostdin", "-v", "error", "-i", str(path), "-map", "0:a:0",
        "-ac", "1", "-ar", str(ANA_RATE), "-f", "f32le", "pipe:1",
    ]
    raw = subprocess.check_output(cmd)
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def _compute_features(mono):
    freqs, stft_times, Z = signal.stft(
        mono, fs=ANA_RATE, nperseg=1024, noverlap=1024 - HOP, boundary=None
    )
    mag = np.abs(Z)
    log_mag = np.log1p(15.0 * mag)
    diff = np.maximum(0.0, np.diff(log_mag, axis=1, prepend=log_mag[:, :1]))

    kick_mask = (freqs >= 35.0) & (freqs <= 140.0)
    snare_mask = (freqs >= 900.0) & (freqs <= 4500.0)
    low_mask = (freqs >= 35.0) & (freqs <= 200.0)
    full_mask = (freqs >= 35.0) & (freqs <= 8000.0)

    def norm(v):
        v = np.maximum(0.0, v - np.convolve(v, np.ones(33) / 33.0, mode="same") * 0.72)
        s = float(np.percentile(v, 99)) or float(np.max(v)) or 1.0
        return np.clip(v / s, 0.0, 2.5)

    kick_env = norm(np.mean(diff[kick_mask], axis=0))
    snare_env = norm(np.mean(diff[snare_mask], axis=0))
    onset = 0.65 * kick_env + 0.35 * snare_env

    low_energy = np.sum(mag[low_mask] ** 2, axis=0)
    tot_energy = np.sum(mag[full_mask] ** 2, axis=0) + 1e-12
    low_ratio_frames = np.clip(low_energy / tot_energy, 0.0, 1.0)
    return stft_times, kick_env, snare_env, onset, low_ratio_frames


def _strongest_rhythmic_window(onset, times, window_s=36.0):
    """Pick the most rhythmically active window in the track for tempo/phase estimation."""
    if len(times) == 0 or times[-1] <= window_s + 2.0:
        return 0, len(times)
    win_f = int(round(window_s / HOP_S))
    step_f = max(1, int(round(2.0 / HOP_S)))
    best_i = 0
    best_score = -1.0
    for start in range(0, len(onset) - win_f + 1, step_f):
        seg = onset[start:start + win_f]
        score = float(np.std(seg) * np.mean(seg))
        if score > best_score:
            best_score = score
            best_i = start
    return best_i, min(len(onset), best_i + win_f)


def _comb_score_continuous(times, sig, start_t, end_t, bpm):
    period = 60.0 / bpm
    n_ph = max(36, int(round(period / 0.004)))
    phases = np.linspace(0.0, period, n_ph, endpoint=False)
    best_score = -1.0
    best_ph = 0.0
    for ph in phases:
        grid = np.arange(start_t + ph, end_t - 0.05, period)
        if len(grid) < 4:
            continue
        val = float(np.mean(np.interp(grid, times, sig)))
        if val > best_score:
            best_score = val
            best_ph = float(start_t + ph)
    return best_score, best_ph


def _estimate_tempo_and_grid(times, kick_env, snare_env, onset, duration_s, known_bpm=None):
    i0, i1 = _strongest_rhythmic_window(onset, times, window_s=36.0)
    w_t0, w_t1 = float(times[i0]), float(times[i1 - 1])
    comb_sig = kick_env + 0.55 * snare_env

    if known_bpm is not None and 50.0 <= known_bpm <= 200.0:
        kb = float(known_bpm)
        candidates = np.linspace(kb - 0.50, kb + 0.50, 51)  # 0.02 BPM step
        scores = np.array([_comb_score_continuous(times, comb_sig, w_t0, w_t1, b)[0] for b in candidates])
        best_idx = int(np.argmax(scores))
        best_bpm = round(float(candidates[best_idx]), 2)
        known_score = _comb_score_continuous(times, comb_sig, w_t0, w_t1, kb)[0]
        if abs(best_bpm - kb) <= 0.10 or scores[best_idx] <= known_score * 1.02:
            best_bpm = kb
        conf = float(np.clip((float(scores[best_idx]) - float(np.min(scores)) + max(0.0, float(scores[best_idx]) - 0.25)) / 0.65, 0.0, 1.0))
        # Compute confidence against autocorrelation sharpness too.
        seg = onset[i0:i1] - np.mean(onset[i0:i1])
        ac_peak = 0.0
        if len(seg) > 200 and float(np.std(seg)) > 1e-6:
            lag = int(round((60.0 / best_bpm) / HOP_S))
            if lag < len(seg):
                ac_peak = float(np.dot(seg[:-lag], seg[lag:]) / max(float(np.dot(seg, seg)), 1e-12))
        conf = round(float(np.clip(max(conf, ac_peak * 1.8), 0.0, 1.0)), 3)
    else:
        # Blind estimation: interpolated lag autocorrelation over [68, 165] BPM, then fine continuous refinement.
        seg = onset[i0:i1] - np.mean(onset[i0:i1])
        n = len(seg)
        if n < 200 or float(np.std(seg)) < 1e-6:
            return 120.0, 0.0, [], []
        fft_len = 1 << (2 * n - 1).bit_length()
        ac = np.fft.irfft(np.abs(np.fft.rfft(seg, fft_len)) ** 2, fft_len)[:n]
        ac /= max(float(ac[0]), 1e-12)
        lag_x = np.arange(n, dtype=np.float64)

        coarse_bpms = np.linspace(68.0, 165.0, 389)  # 0.25 BPM steps
        c_scores = []
        for b in coarse_bpms:
            lag = 60.0 / b / HOP_S
            s = 0.0
            for mult, wt in ((1.0, 1.0), (2.0, 0.65), (4.0, 0.45), (0.5, 0.30)):
                pos = lag * mult
                if pos < n - 1:
                    s += wt * float(np.interp(pos, lag_x, ac))
            if 85.0 <= b <= 135.0:
                s *= 1.04
            c_scores.append(s)
        c_scores = np.array(c_scores, dtype=np.float64)
        top_bpm = float(coarse_bpms[int(np.argmax(c_scores))])

        # Fine continuous comb search +-1.2 BPM at 0.04 BPM precision.
        fine_bpms = np.linspace(top_bpm - 1.2, top_bpm + 1.2, 61)
        f_scores = np.array([_comb_score_continuous(times, comb_sig, w_t0, w_t1, b)[0] for b in fine_bpms])
        best_idx = int(np.argmax(f_scores))
        best_bpm = round(float(fine_bpms[best_idx]), 2)
        # Snap near-integer electronic BPMs (within +-0.08 BPM of integer).
        if abs(best_bpm - round(best_bpm)) <= 0.08:
            best_bpm = float(round(best_bpm))
        med = float(np.median(c_scores))
        conf = round(float(np.clip((float(np.max(c_scores)) - med) / max(float(np.max(c_scores)), 1e-6), 0.0, 1.0)), 3)

    # High-resolution (2 ms) beat phase lock on the active window.
    period = 60.0 / best_bpm
    n_ph = max(120, int(round(period / 0.002)))
    phases = np.linspace(0.0, period, n_ph, endpoint=False)
    best_val = -1.0
    anchor = w_t0
    for ph in phases:
        grid = np.arange(w_t0 + ph, w_t1 - 0.05, period)
        val = float(np.mean(np.interp(grid, times, comb_sig)))
        if val > best_val:
            best_val = val
            anchor = float(w_t0 + ph)

    # Identify Bar-1 downbeat phase (0..3) using kick on Beat 1 + snare backbeats on 2 & 4.
    win_beats = np.arange(anchor, w_t1 - 0.05, period)
    if len(win_beats) < 4:
        return best_bpm, 0.0, [], []
    kv = np.interp(win_beats, times, kick_env)
    sv = np.interp(win_beats, times, snare_env)
    bar_scores = []
    for p in range(4):
        sc = float(np.mean(kv[p::4]) * 1.4 + np.mean(sv[(p + 1) % 4::4]) + np.mean(sv[(p + 3) % 4::4]) - 0.5 * np.mean(sv[p::4]))
        bar_scores.append(sc)
    bar_phase = int(np.argmax(bar_scores))
    bar1_anchor = float(win_beats[bar_phase])

    # Project global periodic grid, then align regional phase for head (0..38s) & tail (last 50s)
    # so analog tape recordings (e.g. Billie Jean) stay phase-locked in intro/outro regions.
    def best_local_phase(t_lo, t_hi, fallback_anchor):
        if t_hi - t_lo < 8.0 * period:
            return fallback_anchor
        n_loc = max(90, int(round(period / 0.003)))
        phs = np.linspace(0.0, period, n_loc, endpoint=False)
        best_v, best_a = -1.0, fallback_anchor
        for ph in phs:
            g = np.arange(t_lo + ph, t_hi - 0.05, period)
            if len(g) < 4:
                continue
            v = float(np.mean(np.interp(g, times, comb_sig)))
            if v > best_v:
                best_v, best_a = v, float(t_lo + ph)
        # Keep the 4-beat bar phase nearest to global bar1_anchor.
        k_bar = round((best_a - bar1_anchor) / period)
        bar_shift = (4 - (k_bar % 4)) % 4
        return best_a + bar_shift * period

    head_end = min(duration_s, 38.0)
    tail_start = max(head_end, duration_s - 50.0)
    head_anchor = best_local_phase(0.0, head_end, bar1_anchor) if duration_s > 75.0 else bar1_anchor
    tail_anchor = best_local_phase(tail_start, duration_s, bar1_anchor) if duration_s - tail_start > 16.0 else bar1_anchor

    def collect_region(anchor, t_lo, t_hi):
        i_start = math.ceil((t_lo - anchor) / period)
        i_end = math.floor((t_hi - anchor) / period)
        b_list, db_list = [], []
        for idx in range(i_start, i_end + 1):
            t_val = anchor + idx * period
            if t_lo <= t_val < t_hi:
                b_list.append(round(float(t_val), 3))
                if idx % 4 == 0:
                    db_list.append(round(float(t_val), 3))
        return b_list, db_list

    b_head, db_head = collect_region(head_anchor, 0.0, head_end)
    b_mid, db_mid = collect_region(bar1_anchor, head_end, tail_start)
    b_tail, db_tail = collect_region(tail_anchor, tail_start, duration_s - 0.03)
    beats = b_head + b_mid + b_tail
    downbeats = db_head + db_mid + db_tail
    return best_bpm, conf, beats, downbeats


def _gated_lufs_22k(mono):
    """BS.1770-4 400 ms gated integrated LUFS at 22050 Hz (dual-mono stereo energy)."""
    kw = signal.sosfilt(_KW_22K, mono)
    blk = round(0.4 * ANA_RATE)
    hop = round(0.1 * ANA_RATE)
    if len(kw) < blk:
        return round(-0.691 + 10.0 * math.log10(2.0 * float(np.mean(kw ** 2)) + 1e-12), 2)
    n_blk = (len(kw) - blk) // hop + 1
    sq = 2.0 * (kw ** 2)  # dual-mono L+R sum
    cs = np.concatenate([[0.0], np.cumsum(sq)])
    starts = np.arange(n_blk) * hop
    powers = (cs[starts + blk] - cs[starts]) / blk
    l_blk = -0.691 + 10.0 * np.log10(powers + 1e-12)
    keep1 = l_blk > -70.0
    if not np.any(keep1):
        return -120.0
    rel_thresh = -0.691 + 10.0 * math.log10(float(np.mean(powers[keep1])) + 1e-12) - 10.0
    keep2 = keep1 & (l_blk > rel_thresh)
    final_pw = float(np.mean(powers[keep2])) if np.any(keep2) else float(np.mean(powers[keep1]))
    return round(-0.691 + 10.0 * math.log10(final_pw + 1e-12), 2), kw


@lru_cache(maxsize=24)
def _analyze_cached(path_str, size, mtime_ns, known_bpm):
    mono = _decode_mono_22k(Path(path_str))
    duration_s = len(mono) / ANA_RATE
    if duration_s < 1.0:
        return {
            "duration": round(duration_s, 3),
            "bpm": float(known_bpm or 120.0),
            "bpm_confidence": 0.0,
            "beats": [],
            "downbeats": [],
            "lufs": -60.0,
            "lufs_2s": [],
            "low_ratio_1s": [],
            "low_ratio_head": 0.0,
            "low_ratio_tail": 0.0,
        }

    lufs_res = _gated_lufs_22k(mono)
    if isinstance(lufs_res, tuple):
        lufs, kw = lufs_res
    else:
        lufs = lufs_res
        kw = signal.sosfilt(_KW_22K, mono)

    # 2-second LUFS profile for phrase/outro-fade/intro-drop detection.
    win2 = 2 * ANA_RATE
    n_2s = len(kw) // win2
    kw_2s = kw[:n_2s * win2].reshape(n_2s, win2) if n_2s else kw[None, :]
    lufs_2s = [round(-0.691 + 10.0 * math.log10(2.0 * float(np.mean(row ** 2)) + 1e-12), 2) for row in kw_2s]

    times, kick_env, snare_env, onset, low_ratio_frames = _compute_features(mono)
    bpm, conf, beats, downbeats = _estimate_tempo_and_grid(
        times, kick_env, snare_env, onset, duration_s, known_bpm=known_bpm
    )

    fps = max(1, int(round(1.0 / HOP_S)))
    n_lr = len(low_ratio_frames) // fps
    lr_blocks = low_ratio_frames[:n_lr * fps].reshape(n_lr, fps) if n_lr else low_ratio_frames[None, :]
    lr_sec = np.mean(lr_blocks, axis=1)
    low_ratio_1s = [round(float(v), 3) for v in lr_sec]
    head_slice = low_ratio_1s[:min(20, len(low_ratio_1s))]
    tail_slice = low_ratio_1s[max(0, len(low_ratio_1s) - 25):]

    return {
        "duration": round(duration_s, 3),
        "bpm": bpm,
        "bpm_confidence": conf,
        "beats": beats,
        "downbeats": downbeats,
        "lufs": lufs,
        "lufs_2s": lufs_2s,
        "low_ratio_1s": low_ratio_1s,
        "low_ratio_head": round(float(np.mean(head_slice)) if head_slice else 0.0, 3),
        "low_ratio_tail": round(float(np.mean(tail_slice)) if tail_slice else 0.0, 3),
    }


def analyze_track(path, known_bpm=None):
    """Analyze beat grid, Bar-1 downbeats, gated LUFS, and energy profile."""
    p = Path(path)
    st = p.stat()
    kb = round(float(known_bpm), 3) if known_bpm is not None else None
    return _analyze_cached(str(p.resolve()), st.st_size, st.st_mtime_ns, kb)


def snap_to_beat(analysis, time_s, max_dist_s=0.35, prefer_downbeat=True):
    """Snap a candidate timestamp to the nearest detected beat (or downbeat)."""
    beats = analysis.get("beats") or []
    downbeats = analysis.get("downbeats") or []
    if prefer_downbeat and downbeats:
        nearest_db = min(downbeats, key=lambda b: abs(b - time_s))
        if abs(nearest_db - time_s) <= min(max_dist_s, 0.30):
            return round(float(nearest_db), 3)
    if beats:
        nearest = min(beats, key=lambda b: abs(b - time_s))
        if abs(nearest - time_s) <= max_dist_s:
            return round(float(nearest), 3)
    return round(float(time_s), 3)


def _window_low_ratio(analysis, start_s, dur_s):
    lr = analysis["low_ratio_1s"]
    if not lr:
        return 0.0
    i0 = max(0, min(len(lr) - 1, int(math.floor(start_s))))
    i1 = max(i0 + 1, min(len(lr), int(math.ceil(start_s + dur_s))))
    return float(np.mean(lr[i0:i1]))


def _find_outro_fade_time(analysis):
    """Locate (outro_start_s, fade_end_s) where A exits main body and fades to quiet tail."""
    lufs_2s = analysis["lufs_2s"]
    dur = analysis["duration"]
    if not lufs_2s:
        return max(0.0, dur - 12.0), max(0.0, dur - 6.0)
    ref = analysis["lufs"]
    fade_thresh = max(-30.0, ref - 16.0)
    body_thresh = max(-24.0, ref - 4.5)
    fade_end = max(0.0, dur - 6.0)
    for idx in range(len(lufs_2s) - 1, -1, -1):
        if lufs_2s[idx] >= fade_thresh:
            fade_end = min(dur, float(idx * 2 + 2))
            break
    outro_start = fade_end
    for idx in range(len(lufs_2s) - 1, -1, -1):
        if lufs_2s[idx] >= body_thresh:
            outro_start = min(fade_end, float(idx * 2 + 2))
            break
    return outro_start, fade_end


def _find_entry_and_drop(analysis):
    """Find first audible Bar-1 downbeat in B and the downbeat where main drums/drop arrive."""
    lufs_2s = analysis["lufs_2s"]
    db_b = analysis["downbeats"] or analysis["beats"] or [0.0]
    ref = analysis["lufs"]
    audible_thresh = max(-42.0, ref - 26.0)

    cue_b = db_b[0]
    for t in db_b:
        if t > 12.0:
            break
        idx = min(len(lufs_2s) - 1, max(0, int(t // 2))) if lufs_2s else 0
        if not lufs_2s or lufs_2s[idx] >= audible_thresh:
            cue_b = t
            break

    drop_t = None
    if len(lufs_2s) >= 12:
        start_idx = max(0, int(cue_b // 2))
        base_head = float(np.mean(lufs_2s[start_idx:min(len(lufs_2s), start_idx + 4)]))
        for idx in range(start_idx + 2, min(len(lufs_2s), 18)):
            if lufs_2s[idx] >= base_head + 3.0 and lufs_2s[idx] >= ref - 5.5:
                t_raw = float(idx * 2)
                cand_dbs = [d for d in db_b if 4.0 <= d <= 32.0]
                drop_t = min(cand_dbs, key=lambda d: abs(d - t_raw)) if cand_dbs else t_raw
                break
        if drop_t is None and analysis["low_ratio_1s"]:
            lr = analysis["low_ratio_1s"]
            base_lr = float(np.mean(lr[:min(6, len(lr))]))
            for sec in range(4, min(32, len(lr))):
                if lr[sec] >= max(0.28, base_lr + 0.14):
                    cand_dbs = [d for d in db_b if 4.0 <= d <= 32.0]
                    drop_t = min(cand_dbs, key=lambda d: abs(d - float(sec))) if cand_dbs else float(sec)
                    break
    return round(float(cue_b), 3), (round(float(drop_t), 3) if drop_t is not None else None)


def auto_plan(path_a, path_b, bpm_a=None, bpm_b=None):
    """Plan drop-at-midpoint cues, bar count, EQ, filter, and sync from phrase structure."""
    ana_a = analyze_track(path_a, known_bpm=bpm_a)
    ana_b = analyze_track(path_b, known_bpm=bpm_b)
    return plan_from_analysis(ana_a, ana_b, bpm_a, bpm_b)


def plan_from_analysis(ana_a, ana_b, bpm_a=None, bpm_b=None):

    eff_bpm_a = float(bpm_a or ana_a["bpm"])
    eff_bpm_b = float(bpm_b or ana_b["bpm"])
    ratio = eff_bpm_a / max(eff_bpm_b, 1.0)
    tempo_diff_pct = (ratio - 1.0) * 100.0
    reliable_grid = all(analysis["bpm_confidence"] >= 0.35 and analysis["downbeats"]
                        for analysis in (ana_a, ana_b))
    sync = reliable_grid and 0.895 <= ratio <= 1.105

    first_b, drop_b = _find_entry_and_drop(ana_b)
    outro_start_a, fade_end_a = _find_outro_fade_time(ana_a)

    bar_s_a = 4.0 * 60.0 / eff_bpm_a
    bar_s_b = 4.0 * 60.0 / eff_bpm_b
    tail_span = max(0.0, fade_end_a - outro_start_a)
    intro_bars_b = ((drop_b - first_b) / bar_s_b) if (drop_b is not None and drop_b > first_b) else 0.0

    if sync and intro_bars_b >= 4.0 and (tail_span >= 3.5 * bar_s_a or (abs(ratio - 1.0) <= 0.025 and ana_a["duration"] >= 150 and ana_b["duration"] >= 200)):
        bars = 8
    else:
        bars = 4

    overlap_s = bars * bar_s_a
    overlap_b_src = overlap_s * (ratio if sync else 1.0)

    db_b = ana_b["downbeats"] or ana_b["beats"] or [0.0]
    if drop_b is not None and drop_b >= 2.0 * bar_s_b:
        # Align B's main drop with the midpoint bass swap!
        target_cue_b = max(first_b, drop_b - overlap_b_src / 2.0)
        cue_b = min(db_b, key=lambda t: abs(t - target_cue_b))
    else:
        cue_b = first_b

    # Anchor A so A's body transition / outro handoff lands cleanly at midpoint or completes before fade end.
    db_a = [t for t in (ana_a["downbeats"] or ana_a["beats"]) if t <= ana_a["duration"] - overlap_s - 0.3]
    if tail_span >= 2.0 * bar_s_a:
        target_cue_a = max(0.0, min(ana_a["duration"] - overlap_s - 0.5, outro_start_a - overlap_s / 2.0))
    else:
        target_cue_a = max(0.0, min(ana_a["duration"] - overlap_s - 0.5, fade_end_a - overlap_s))
    cue_a = min(db_a, key=lambda t: abs(t - target_cue_a)) if db_a else round(target_cue_a, 3)

    low_a = _window_low_ratio(ana_a, cue_a, overlap_s)
    low_b = _window_low_ratio(ana_b, cue_b, overlap_b_src)

    # Choose complementary curves matching Spotify AutoMix strategies.
    if low_a >= 0.30 and low_b >= 0.30 and bars == 4:
        volume, eq, filt = "overlap", "bass-swap", "both-high-io"
    elif bars == 8 and low_a >= 0.32 and low_b >= 0.20:
        volume, eq, filt = "fade-cut", "bass-swap", "high-out"
    elif bars == 8 and low_b >= 0.25:
        volume, eq, filt = "fade-cut", "bass-swap", "low-out"
    elif bars == 8:
        volume, eq, filt = "overlap", "3band", "none"
    else:
        volume, eq, filt = "smooth", "bass-swap", "none"

    lufs_diff = ana_a["lufs"] - ana_b["lufs"]
    plan = {
        "cueA": round(float(cue_a), 3),
        "cueB": round(float(cue_b), 3),
        "bars": bars,
        "volume": volume,
        "eq": eq,
        "filter": filt,
        "effect": "none",
        "loop": 0,
        "sync": sync,
        "snap": bool(ana_a["beats"] and ana_b["beats"]),
        "loudnessMatch": True,
        "swap": 0.5,
        "gainA": 0.0,
        "gainB": 0.0,
        "bassA": -24.0,
        "bassB": -24.0,
    }

    if not reliable_grid:
        tempo_reason = "至少一轨的拍网置信度不足，保持原速交接"
    elif sync:
        tempo_reason = "启用保人声共振峰节拍匹配"
    else:
        tempo_reason = "差值超 ±10.5%，保持原速交接"
    reasons = [
        (f"节奏与拍网：A {eff_bpm_a:.2f} BPM（置信度 {ana_a['bpm_confidence']:.2f}）→ "
         f"B {eff_bpm_b:.2f} BPM（偏差 {tempo_diff_pct:+.1f}%），"
         + tempo_reason),
        (f"中点落鼓对齐：A 尾段收束 {outro_start_a:.1f}s / 淡出 {fade_end_a:.1f}s，"
         + (f"B 主节奏落点 {drop_b:.3f}s 对齐混音中点，" if drop_b else "B 从起始强拍进入，")
         + f"选取 A={cue_a:.3f}s → B={cue_b:.3f}s（{bars} 小节，{overlap_s:.1f}s 重叠）"),
        (f"频段与半窗滤波：A 退出低频占比 {low_a:.0%} / B 进入低频占比 {low_b:.0%}，"
         f"采用 {volume} + {eq}" + (f" + {filt}（仅在交接半窗内扫频）" if filt != "none" else "")),
        (f"全曲门控响度：A {ana_a['lufs']:.1f} LUFS / B {ana_b['lufs']:.1f} LUFS"
         f"（差值 {lufs_diff:+.1f} dB），归一化至 -14 LUFS 留足动态余量"),
    ]
    return {"plan": plan, "reasons": reasons, "analysisA": ana_a, "analysisB": ana_b}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("outgoing", type=Path)
    parser.add_argument("incoming", type=Path, nargs="?")
    parser.add_argument("--bpm-a", type=float, default=None)
    parser.add_argument("--bpm-b", type=float, default=None)
    args = parser.parse_args()
    try:
        if args.incoming is None:
            ana = analyze_track(args.outgoing, known_bpm=args.bpm_a)
            summary = {k: v for k, v in ana.items() if k not in ("beats", "downbeats", "lufs_2s", "low_ratio_1s")}
            summary["num_beats"] = len(ana["beats"])
            summary["first_downbeats"] = ana["downbeats"][:8]
            summary["last_downbeats"] = ana["downbeats"][-8:]
            print(json.dumps(summary, ensure_ascii=False, indent=2))
        else:
            res = auto_plan(args.outgoing, args.incoming, bpm_a=args.bpm_a, bpm_b=args.bpm_b)
            print(json.dumps({"plan": res["plan"], "reasons": res["reasons"]}, ensure_ascii=False, indent=2))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
