"""Render beat-aligned transitions between two local audio files."""

import argparse
from functools import lru_cache
import json
import math
from pathlib import Path
import subprocess
import sys

import numpy as np
from scipy import ndimage, signal
from scipy.io import wavfile


RATE = 48000
PREROLL_S = 1.0


def decode(path, start_frame, frames, speed=1.0, allow_pad=False):
    """Decode stereo float32 audio at RATE, applying pre-rolled Rubberband when speed != 1."""
    if frames <= 0:
        return np.zeros((0, 2), dtype=np.float32)
    if start_frame < 0:
        if not allow_pad:
            raise ValueError(f"{Path(path).name}: requested interval starts before 0")
        lead_out = min(frames, max(0, round(-start_frame / speed)))
        tail = decode(path, 0, frames - lead_out, speed=speed, allow_pad=True)
        return np.vstack([np.zeros((lead_out, 2), dtype="<f4"), tail])
    if abs(speed - 1.0) < 1e-6:
        command = [
            "ffmpeg", "-nostdin", "-v", "error", "-ss", f"{start_frame / RATE:.9f}",
            "-i", str(path), "-map", "0:a:0", "-t", f"{frames / RATE:.9f}",
            "-ac", "2", "-ar", str(RATE), "-f", "f32le", "pipe:1",
        ]
        raw = subprocess.check_output(command)
        audio = np.frombuffer(raw, dtype="<f4").reshape(-1, 2)
        if len(audio) < frames:
            if not allow_pad:
                raise ValueError(f"{Path(path).name}: requested interval extends past decoded audio")
            audio = np.vstack([audio, np.zeros((frames - len(audio), 2), dtype="<f4")])
        return audio[:frames]

    preroll = round(PREROLL_S * RATE)
    decode_start = max(0, start_frame - preroll)
    pad_frames = max(0, preroll - start_frame)
    need_src = math.ceil((frames + preroll - pad_frames) * speed) + RATE
    command = [
        "ffmpeg", "-nostdin", "-v", "error", "-ss", f"{decode_start / RATE:.9f}",
        "-i", str(path), "-map", "0:a:0", "-t", f"{need_src / RATE:.9f}",
        "-ac", "2", "-ar", str(RATE), "-f", "f32le", "pipe:1",
    ]
    raw = subprocess.check_output(command)
    src = np.frombuffer(raw, dtype="<f4").reshape(-1, 2)
    min_required = math.ceil(frames * speed) - RATE // 10
    if pad_frames + len(src) < preroll + min_required:
        if not allow_pad:
            raise ValueError(f"{Path(path).name}: requested interval extends past decoded audio")
    if pad_frames > 0:
        src = np.vstack([np.zeros((pad_frames, 2), dtype="<f4"), src])
    target_src = preroll + math.ceil(frames * speed) + RATE // 2
    if allow_pad and len(src) < target_src:
        src = np.vstack([src, np.zeros((target_src - len(src), 2), dtype="<f4")])

    stretch_cmd = [
        "ffmpeg", "-nostdin", "-v", "error",
        "-f", "f32le", "-ar", str(RATE), "-ac", "2", "-i", "pipe:0",
        "-af", f"rubberband=tempo={speed:.9f}:channels=together:transients=crisp:pitchq=quality:formant=preserved",
        "-f", "f32le", "pipe:1",
    ]
    stretched_raw = subprocess.check_output(stretch_cmd, input=src.astype("<f4", copy=False).tobytes())
    out = np.frombuffer(stretched_raw, dtype="<f4").reshape(-1, 2)
    drop = round(preroll / speed)
    if len(out) < drop + frames:
        if not allow_pad:
            raise ValueError(f"{Path(path).name}: stretched interval extends past decoded audio")
        out = np.vstack([out, np.zeros((drop + frames - len(out), 2), dtype="<f4")])
    return out[drop:drop + frames]


def _lr4_split(audio, fc):
    """Split audio into phase-aligned low and high bands using causal LR4 filters."""
    lp = signal.butter(2, fc, btype="low", fs=RATE, output="sos")
    hp = signal.butter(2, fc, btype="high", fs=RATE, output="sos")
    low = signal.sosfilt(lp, signal.sosfilt(lp, audio, axis=0), axis=0)
    high = signal.sosfilt(hp, signal.sosfilt(hp, audio, axis=0), axis=0)
    return low, high


def lr4_3band(audio):
    """Causal 3-band Linkwitz-Riley 4th-order crossover (200 Hz, 2500 Hz)."""
    x = np.asarray(audio, dtype=np.float64)
    if len(x) == 0:
        empty = np.zeros((0, 2), dtype=np.float64)
        return empty, empty, empty
    low_mid, high = _lr4_split(x, 2500.0)
    low, mid = _lr4_split(low_mid, 200.0)
    # All-pass match high band to the 200 Hz crossover phase response.
    hi_l, hi_h = _lr4_split(high, 200.0)
    return low, mid, hi_l + hi_h


def _smoothstep(x):
    t = np.clip(x, 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def apply_eq(bands_a, bands_b, phase, overlap, beats_total, *, eq="bass-swap",
             swap_at=0.5, bass_a=-18.0, bass_b=-18.0):
    """Apply continuous-boundary 3-band EQ automation and return (overlap_a, overlap_b)."""
    low_a, mid_a, high_a = bands_a
    low_b, mid_b, high_b = bands_b
    gain_low_a = np.ones((overlap, 1), dtype=np.float64)
    gain_mid_a = np.ones((overlap, 1), dtype=np.float64)
    gain_high_a = np.ones((overlap, 1), dtype=np.float64)
    gain_low_b = np.ones((overlap, 1), dtype=np.float64)
    gain_mid_b = np.ones((overlap, 1), dtype=np.float64)
    gain_high_b = np.ones((overlap, 1), dtype=np.float64)

    cut_a = 1.0 - 10.0 ** (bass_a / 20.0)
    cut_b = 1.0 - 10.0 ** (bass_b / 20.0)

    if eq in ("bass-swap", "bass-swap-start", "bass-swap-end", "fast-bass-cut"):
        beats_width = 0.125 if eq == "fast-bass-cut" else 0.25
        center = 0.02 if eq == "bass-swap-start" else (0.98 if eq == "bass-swap-end" else swap_at)
        width = max(1e-4, beats_width / max(1.0, beats_total))
        swap = _smoothstep((phase - center) / width + 0.5)
        gain_low_a = 1.0 - cut_a * swap
        gain_low_b = 1.0 - cut_b * (1.0 - swap)
    elif eq == "3band":
        mid_hi_cut = 1.0 - 10.0 ** (-24.0 / 20.0)
        swap_w = max(1e-4, 0.25 / max(1.0, beats_total))
        swap_low = _smoothstep((phase - swap_at) / swap_w + 0.5)
        swap_mid = np.clip(phase, 0.0, 1.0)
        swap_high_a = np.clip(phase / 0.85, 0.0, 1.0)
        swap_high_b = np.clip((phase - 0.15) / 0.85, 0.0, 1.0)
        gain_low_a = 1.0 - cut_a * swap_low
        gain_low_b = 1.0 - cut_b * (1.0 - swap_low)
        gain_mid_a = 1.0 - mid_hi_cut * swap_mid
        gain_mid_b = 1.0 - mid_hi_cut * (1.0 - swap_mid)
        gain_high_a = 1.0 - mid_hi_cut * swap_high_a
        gain_high_b = 1.0 - mid_hi_cut * (1.0 - swap_high_b)
    elif eq != "none":
        raise ValueError(f"Unsupported EQ mode: {eq}")

    out_a = low_a * gain_low_a + mid_a * gain_mid_a + high_a * gain_high_a
    out_b = low_b * gain_low_b + mid_b * gain_mid_b + high_b * gain_high_b
    return out_a, out_b


def swept_filter(audio, phase, mode, swap_at=0.5):
    """Sweep a 100%-wet 2nd-order Butterworth RBJ filter across the active half-window."""
    if mode == "none" or len(audio) == 0:
        return audio
    if mode not in ("high-out", "high-out-half", "low-out", "high-in", "low-in"):
        raise ValueError(f"Unsupported filter mode: {mode}")

    n = len(audio)
    x = np.asarray(audio, dtype=np.float64)
    swap_idx = int(np.searchsorted(phase, swap_at))
    xfade = min(n // 4, round(0.025 * RATE))

    if mode in ("high-out", "high-out-half", "low-out"):
        if phase[-1] < swap_at:
            return x
        act_start = max(0, swap_idx - xfade)
        act_slice = slice(act_start, n)
        u = np.clip((phase[act_slice] - swap_at) / max(1e-4, 1.0 - swap_at), 0.0, 1.0)
        highpass = mode in ("high-out", "high-out-half")
        if mode == "high-out":
            fc_sub = 25.0 * (7000.0 / 25.0) ** (u ** 1.20)
        elif mode == "high-out-half":
            fc_sub = 25.0 * (1200.0 / 25.0) ** (u ** 1.15)
        else:
            fc_sub = 130.0 * (18000.0 / 130.0) ** ((1.0 - u) ** 1.15)
    else:
        if phase[0] > swap_at:
            return x
        act_end = min(n, swap_idx + xfade)
        act_slice = slice(0, act_end)
        u = np.clip(phase[act_slice] / max(1e-4, swap_at), 0.0, 1.0)
        highpass = mode == "high-in"
        fc_sub = (25.0 * (4500.0 / 25.0) ** ((1.0 - u) ** 1.20)) if highpass else (180.0 * (18000.0 / 180.0) ** (u ** 0.90))

    sub_x = x[act_slice]
    sub_n = len(sub_x)
    if sub_n == 0:
        return x
    block_size = 128
    starts = np.arange(0, sub_n, block_size)
    fc = np.clip(fc_sub[starts], 15.0, RATE * 0.45)

    w0 = 2.0 * np.pi * fc / RATE
    cw = np.cos(w0)
    sw = np.sin(w0)
    alpha = sw / math.sqrt(2.0)
    a0 = 1.0 + alpha
    if highpass:
        b0 = (1.0 + cw) / (2.0 * a0)
        b1 = -(1.0 + cw) / a0
        b2 = b0
    else:
        b0 = (1.0 - cw) / (2.0 * a0)
        b1 = (1.0 - cw) / a0
        b2 = b0
    a1 = -2.0 * cw / a0
    a2 = (1.0 - alpha) / a0
    sos_all = np.stack([b0, b1, b2, np.ones_like(a0), a1, a2], axis=1)

    wet_sub = np.empty_like(sub_x)
    state = np.zeros((1, 2, sub_x.shape[1]), dtype=np.float64)
    for idx, start in enumerate(starts):
        end = min(sub_n, start + block_size)
        wet_sub[start:end], state = signal.sosfilt(sos_all[idx:idx + 1], sub_x[start:end], axis=0, zi=state)

    result = x.copy()
    result[act_slice] = wet_sub
    if xfade > 1 and sub_n >= xfade:
        ramp = np.linspace(0.0, 1.0, xfade)[:, None]
        if mode in ("high-out", "high-out-half", "low-out") and phase[0] <= swap_at:
            k0 = act_start
            result[k0:k0 + xfade] = x[k0:k0 + xfade] * (1.0 - ramp) + wet_sub[:xfade] * ramp
        elif mode in ("high-in", "low-in") and phase[-1] >= swap_at:
            k1 = act_end - xfade
            result[k1:act_end] = wet_sub[-xfade:] * (1.0 - ramp) + x[k1:act_end] * ramp
    return result


@lru_cache(maxsize=1)
def _reverb_ir():
    """Generate deterministic damped stereo reverb IR high-passed above 180 Hz."""
    ir_len = round(2.4 * RATE)
    rng = np.random.default_rng(20261124)
    t = np.arange(ir_len, dtype=np.float64) / RATE
    env = np.exp(-t * 3.1) * (1.0 - np.exp(-t * 45.0))
    noise = rng.standard_normal((ir_len, 2))
    ir = noise * env[:, None]
    lp = signal.butter(2, 4800.0, btype="low", fs=RATE, output="sos")
    hp = signal.butter(2, 180.0, btype="high", fs=RATE, output="sos")
    ir = signal.sosfilt(lp, ir, axis=0)
    ir = signal.sosfilt(hp, ir, axis=0)
    norm = np.sqrt(np.mean(np.sum(ir ** 2, axis=1)) * ir_len / 2.0)
    return ir / max(norm, 1e-12) * 1.45


def _apply_effect(audio, track_a_prefader, phase, before, overlap, beat_s, effect):
    """Add post-send pre-fader delay or convolution reverb tails onto the output bus."""
    if effect == "none":
        return
    if effect == "echo":
        delay = max(1, round(RATE * beat_s / 2.0))
        send_env = np.clip((phase[:, 0] - 0.65) / 0.25, 0.0, 1.0)
        send = track_a_prefader * send_env[:, None]
        lpf = signal.butter(2, 4200.0, btype="low", fs=RATE, output="sos")
        tap = send
        for repeat in range(1, 9):
            tap = signal.sosfilt(lpf, tap, axis=0) * 0.48
            start = before + delay * repeat
            count = min(overlap, len(audio) - start)
            if count > 0:
                audio[start:start + count] += tap[:count]
        return

    reverb_specs = {
        "reverb-mid": (0.35, 0.75, 0.30),
        "reverb-end-cut": (0.75, 1.00, 0.65),
        "reverb-end-fade": (0.60, 1.00, 0.30),
    }
    if effect not in reverb_specs:
        raise ValueError(f"Unsupported effect mode: {effect}")
    p_start, p_end, wet_gain = reverb_specs[effect]
    p = phase[:, 0]
    mask = (p >= p_start) & (p <= p_end)
    send_env = np.zeros_like(p)
    if np.any(mask):
        u = (p[mask] - p_start) / max(1e-6, p_end - p_start)
        send_env[mask] = np.sin(np.pi * u) ** 2
    send = track_a_prefader * send_env[:, None]
    ir = _reverb_ir()
    wet = np.column_stack([
        signal.fftconvolve(send[:, ch], ir[:, ch], mode="full")
        for ch in range(2)
    ]) * wet_gain
    count = min(len(wet), len(audio) - before)
    if count > 0:
        audio[before:before + count] += wet[:count]


# ITU-R BS.1770-4 K-weighting 48 kHz biquad SOS coefficients.
_KW_SOS = np.array([
    [1.53512485958697, -2.69169618940638, 1.19839281085285, 1.0, -1.69065929318241, 0.73248077421585],
    [1.0, -2.0, 1.0, 1.0, -1.99004745483398, 0.99007225036621],
], dtype=np.float64)


def measure_lufs(audio, gated=False):
    """Measure BS.1770-4 K-weighted loudness at 48 kHz (supports 400 ms BS.1770 gating)."""
    x = np.asarray(audio, dtype=np.float64)
    if len(x) == 0:
        return -120.0
    if x.ndim == 1:
        x = np.column_stack([x, x])
    filtered = signal.sosfilt(_KW_SOS, x, axis=0)
    if not gated or len(filtered) < round(0.8 * RATE):
        energy = float(np.sum(np.mean(filtered ** 2, axis=0)))
        return -0.691 + 10.0 * math.log10(energy + 1e-12)

    blk = round(0.4 * RATE)
    hop = round(0.1 * RATE)
    n_blk = (len(filtered) - blk) // hop + 1
    if n_blk <= 0:
        energy = float(np.sum(np.mean(filtered ** 2, axis=0)))
        return -0.691 + 10.0 * math.log10(energy + 1e-12)
    sq = np.sum(filtered ** 2, axis=1)
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
    return -0.691 + 10.0 * math.log10(final_pw + 1e-12)


@lru_cache(maxsize=24)
def _track_lufs_cached(path_str, size, mtime_ns):
    cmd = [
        "ffmpeg", "-nostdin", "-v", "error", "-i", path_str, "-map", "0:a:0",
        "-ac", "2", "-ar", str(RATE), "-f", "f32le", "pipe:1",
    ]
    raw = subprocess.check_output(cmd)
    audio = np.frombuffer(raw, dtype="<f4").reshape(-1, 2)
    return round(measure_lufs(audio, gated=True), 2)


def measure_track_lufs(path):
    """Measure and cache full-track BS.1770-4 gated integrated LUFS."""
    p = Path(path)
    st = p.stat()
    return _track_lufs_cached(str(p.resolve()), st.st_size, st.st_mtime_ns)


def _true_peak_4x_env(audio):
    """Compute 4x oversampled inter-sample true peak envelope mapped back to RATE."""
    if len(audio) == 0:
        return np.zeros(0, dtype=np.float64)
    up = signal.resample_poly(np.asarray(audio, dtype=np.float64), 4, 1, axis=0)
    n = len(audio) * 4
    if len(up) < n:
        up = np.pad(up, ((0, n - len(up)), (0, 0)))
    return np.max(np.abs(up[:n].reshape(len(audio), 4, 2)), axis=(1, 2))


def limit_true_peak(audio, ceiling_db=-1.0):
    """Lookahead attack + 120 ms exponential release 4x true-peak limiter."""
    x = np.asarray(audio, dtype=np.float64)
    if len(x) == 0:
        return x, 0.0, None
    ceiling = 10.0 ** (ceiling_db / 20.0)
    tp_env = _true_peak_4x_env(x)
    raw_peak = float(np.max(tp_env))
    if raw_peak <= 1e-12:
        return x, 0.0, None

    needed = np.minimum(1.0, ceiling / np.maximum(tp_env, 1e-12))
    if float(np.min(needed)) >= 1.0:
        return x, 0.0, round(20.0 * math.log10(raw_peak), 3)

    # 1 ms control frames -> 5 ms lookahead hold -> 120 ms exponential release -> smooth attack.
    step = max(1, round(0.001 * RATE))  # 48 samples = 1 ms
    n = len(x)
    n_ctrl = math.ceil(n / step)
    pad_tail = n_ctrl * step - n
    needed_padded = np.pad(needed, (0, pad_tail), mode="constant", constant_values=1.0)
    ctrl_min = np.min(needed_padded.reshape(n_ctrl, step), axis=1)

    # 5 ms centered lookahead + 2 ms pre-dilation margin before 3 ms attack smoothing.
    held = ndimage.minimum_filter1d(ctrl_min, size=11, mode="nearest")

    alpha_rel = math.exp(-0.001 / 0.120)
    rel = np.empty_like(held)
    g = float(held[0])
    rel[0] = g
    for idx in range(1, n_ctrl):
        g = min(float(held[idx]), 1.0 - alpha_rel * (1.0 - g))
        rel[idx] = g

    # Gentle 3 ms Hann smoothing on control gain so attack transitions are click-free.
    rel_dilated = ndimage.minimum_filter1d(rel, size=3, mode="nearest")
    win = np.hanning(5)
    win /= np.sum(win)
    rel_smooth = np.convolve(np.pad(rel_dilated, 2, mode="constant", constant_values=1.0), win, mode="valid")[:n_ctrl]

    ctrl_x = np.arange(n_ctrl, dtype=np.float64) * step
    gain = np.interp(np.arange(n, dtype=np.float64), ctrl_x, rel_smooth)
    limited = x * gain[:, None]

    final_peak = float(np.max(_true_peak_4x_env(limited)))
    extra_scale = min(1.0, ceiling / final_peak) if final_peak > ceiling else 1.0
    if extra_scale < 1.0:
        limited *= extra_scale
        final_peak = float(np.max(_true_peak_4x_env(limited)))
    min_gain = float(np.min(gain)) * extra_scale
    gr_db = round(20.0 * math.log10(max(min_gain, 1e-12)), 3)
    return limited, gr_db, round(20.0 * math.log10(max(final_peak, 1e-12)), 3)


def _write_wav(path, audio, pcm16=False):
    path.parent.mkdir(exist_ok=True, parents=True)
    if pcm16:
        rng = np.random.default_rng(0)
        dither = (rng.uniform(-0.5, 0.5, audio.shape) + rng.uniform(-0.5, 0.5, audio.shape)) / 32768.0
        pcm = np.clip(np.round((audio + dither) * 32767.0), -32768, 32767).astype("<i2")
        wavfile.write(path, RATE, pcm)
    else:
        wavfile.write(path, RATE, audio.astype("<f4"))


def render(args, *, volume="smooth", eq="bass-swap", swap_at=0.5, filter_a="none", filter_b="none",
           speed_b=1.0, loop_beats=0, effect="none", loudness_match=False, pcm16=False,
           lufs_a_ref=None, lufs_b_ref=None, window=None):
    """Render an automix transition WAV and return measurement telemetry."""
    beat = 60.0 / args.bpm
    overlap_s = beat * args.beats_per_bar * args.bars if window is None else window["durationMs"] / 1000.0
    overlap = round(RATE * overlap_s)
    cue_a, cue_b = (round(value * RATE) for value in (args.exit_start, args.entry))
    context_after = float(getattr(args, "context_after", args.context))
    before = min(round(RATE * args.context), max(0, cue_a))
    after = round(RATE * context_after)
    if overlap < 64:
        raise ValueError("Transition is too short to render")

    outgoing = decode(args.outgoing, cue_a - before, before + overlap, allow_pad=True).astype(np.float64)
    incoming = decode(args.incoming, cue_b, overlap + after, speed_b, allow_pad=True).astype(np.float64)

    if loop_beats:
        period = min(overlap, round(RATE * beat * loop_beats))
        cycle = outgoing[before:before + period].copy()
        fade = min(before, period // 4, round(0.006 * RATE))
        if fade:
            ramp = np.linspace(0.0, 1.0, fade)[:, None]
            cycle[-fade:] += ramp * (outgoing[before - fade:before] - cycle[-fade:])
        outgoing = np.concatenate((outgoing[:before], cycle[np.arange(overlap) % period]))

    ctx_a = outgoing[:before] if before >= round(0.5 * RATE) else outgoing[before:before + overlap]
    ctx_b = incoming[overlap:] if after >= round(0.5 * RATE) else incoming[:overlap]
    ctx_lufs_a = measure_lufs(ctx_a)
    ctx_lufs_b = measure_lufs(ctx_b)

    track_lufs_a = float(lufs_a_ref) if lufs_a_ref is not None else ctx_lufs_a
    track_lufs_b = float(lufs_b_ref) if lufs_b_ref is not None else ctx_lufs_b
    if loudness_match and lufs_a_ref is None and lufs_b_ref is None:
        # Full-track integrated LUFS avoids boosting quiet intros of louder songs.
        track_lufs_a = measure_track_lufs(args.outgoing)
        track_lufs_b = measure_track_lufs(args.incoming)

    auto_gain_a_db = 0.0
    auto_gain_b_db = 0.0
    if loudness_match and track_lufs_a > -60.0 and track_lufs_b > -60.0:
        target_lufs = -14.0
        auto_gain_a_db = max(-12.0, min(6.0, target_lufs - track_lufs_a))
        auto_gain_b_db = max(-12.0, min(6.0, target_lufs - track_lufs_b))
        pk_a_db = 20.0 * math.log10(max(float(np.max(np.abs(outgoing))), 1e-12))
        pk_b_db = 20.0 * math.log10(max(float(np.max(np.abs(incoming))), 1e-12))
        excess = max(0.0, (pk_a_db + auto_gain_a_db) - (-2.5), (pk_b_db + auto_gain_b_db) - (-2.5))
        if excess > 0.0:
            auto_gain_a_db -= excess
            auto_gain_b_db -= excess

    low_a_full, mid_a_full, high_a_full = lr4_3band(outgoing)
    low_b_full, mid_b_full, high_b_full = lr4_3band(incoming)

    phase_start, phase_end = (0.0, 1.0) if window is None else (window["phaseStart"], window["phaseEnd"])
    phase = np.linspace(phase_start, phase_end, overlap)[:, None]
    beats_total = args.bars * args.beats_per_bar
    eq_a, eq_b = apply_eq(
        (low_a_full[before:], mid_a_full[before:], high_a_full[before:]),
        (low_b_full[:overlap], mid_b_full[:overlap], high_b_full[:overlap]),
        phase, overlap, beats_total,
        eq=eq, swap_at=swap_at, bass_a=args.bass_a, bass_b=args.bass_b,
    )

    gain_a = 10.0 ** ((args.gain_a + auto_gain_a_db) / 20.0)
    gain_b = 10.0 ** ((args.gain_b + auto_gain_b_db) / 20.0)

    pivot = 0.5 if eq in ("bass-swap-start", "bass-swap-end") else swap_at
    if window is not None:
        gains = np.asarray(window["gains"], dtype=np.float64)
        positions = np.linspace(0.0, 1.0, overlap)
        knots = np.linspace(0.0, 1.0, len(gains))
        env_a = np.interp(positions, knots, gains[:, 0])[:, None]
        env_b = np.interp(positions, knots, gains[:, 1])[:, None]
    elif volume == "smooth":
        env_a, env_b = np.cos(phase * np.pi / 2.0), np.sin(phase * np.pi / 2.0)
    elif volume == "linear":
        env_a, env_b = 1.0 - phase, phase
    elif volume == "full-overlap":
        fade_in = max(1e-4, 0.015 * RATE / overlap)
        fade_out = max(1e-4, 0.025 * RATE / overlap)
        env_b = 0.5 - 0.5 * np.cos(np.pi * np.minimum(phase / fade_in, 1.0))
        env_a = np.where(phase <= 1.0 - fade_out, 1.0, 0.5 + 0.5 * np.cos(np.pi * (phase - (1.0 - fade_out)) / fade_out))
    elif volume == "fade-cut":
        env_b = np.minimum(phase / max(0.05, pivot), 1.0)
        env_a = np.where(phase <= 0.96, 1.0, 0.5 + 0.5 * np.cos(np.pi * (phase - 0.96) / 0.04))
    elif volume == "cut-fade":
        env_b = np.sin(0.5 * np.pi * np.minimum(phase / 0.04, 1.0))
        env_a = np.minimum((1.0 - phase) / max(0.05, 1.0 - pivot), 1.0)
    elif volume == "overlap":
        env_b = np.minimum(phase / max(0.05, pivot), 1.0)
        env_a = np.minimum((1.0 - phase) / max(0.05, 1.0 - pivot), 1.0)
    elif volume in ("mid-cut", "none"):
        cut_c = 0.5 if volume == "none" else pivot
        w_cut = max(1e-4, (0.015 if volume == "none" else 0.020) * RATE / overlap)
        s = _smoothstep((phase - cut_c) / w_cut + 0.5)
        env_a, env_b = 1.0 - s, s
    else:
        raise ValueError(f"Unsupported volume curve: {volume}")

    track_a_prefader = swept_filter(eq_a, phase[:, 0], filter_a, swap_at=pivot) * gain_a
    track_b_prefader = swept_filter(eq_b, phase[:, 0], filter_b, swap_at=pivot) * gain_b
    mixed = track_a_prefader * env_a + track_b_prefader * env_b

    pre_a = (low_a_full[:before] + mid_a_full[:before] + high_a_full[:before]) * gain_a
    post_b = (low_b_full[overlap:] + mid_b_full[overlap:] + high_b_full[overlap:]) * gain_b
    audio = np.concatenate((pre_a, mixed, post_b))

    _apply_effect(audio, track_a_prefader, phase, before, overlap, beat, effect)

    limited, gr_db, peak_dbtp = limit_true_peak(audio, ceiling_db=-1.0)
    if not np.all(np.isfinite(limited)):
        raise ValueError("Non-finite audio sample")
    _write_wav(args.output, limited, pcm16=pcm16)

    lufs_mix = measure_lufs(limited[before:before + overlap])
    return {
        "a_cue_s": round(cue_a / RATE, 6),
        "b_cue_s": round(cue_b / RATE, 6),
        "preview_mix_start_s": round(before / RATE, 6),
        "preview_mix_end_s": round((before + overlap) / RATE, 6),
        "bpm": args.bpm,
        "beats_per_bar": args.beats_per_bar,
        "bars": args.bars,
        "speed_b": round(speed_b, 6),
        "volume": volume,
        "eq": eq,
        "filter_a": filter_a,
        "filter_b": filter_b,
        "effect": effect,
        "gain_db": [round(args.gain_a + auto_gain_a_db, 3), round(args.gain_b + auto_gain_b_db, 3)],
        "bass_cut_db": [args.bass_a, args.bass_b],
        "auto_gain_a_db": round(auto_gain_a_db, 3),
        "auto_gain_b_db": round(auto_gain_b_db, 3),
        "track_lufs_a": round(track_lufs_a, 2),
        "track_lufs_b": round(track_lufs_b, 2),
        "lufs_a": round(track_lufs_a if loudness_match else ctx_lufs_a, 2),
        "lufs_b": round(track_lufs_b if loudness_match else ctx_lufs_b, 2),
        "lufs_mix": round(lufs_mix, 2),
        "limiter_gr_db": gr_db,
        "output_gain_db": gr_db,
        "peak_4x_dbtp": peak_dbtp,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("outgoing", "incoming", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--exit-start", type=float, required=True, help="A overlap start in source seconds")
    parser.add_argument("--entry", type=float, required=True, help="B cue in source seconds")
    parser.add_argument("--bpm", type=float, required=True, help="Shared or target tempo of A in BPM")
    parser.add_argument("--beats-per-bar", type=int, default=4)
    parser.add_argument("--bars", type=int, choices=(2, 4, 8), default=4)
    parser.add_argument("--context", type=float, default=8, help="Seconds before and after the mix")
    parser.add_argument("--volume", choices=("smooth", "full-overlap", "overlap", "fade-cut", "cut-fade", "mid-cut", "linear", "none"), default="smooth")
    parser.add_argument("--eq", choices=("bass-swap", "bass-swap-start", "bass-swap-end", "fast-bass-cut", "3band", "none"), default="bass-swap")
    parser.add_argument("--swap-at", type=float, default=0.5, help="Normalized swap position in [0.05, 0.95]")
    parser.add_argument("--filter-a", choices=("none", "high-out", "high-out-half", "low-out"), default="none")
    parser.add_argument("--filter-b", choices=("none", "low-in", "high-in"), default="none")
    parser.add_argument("--speed-b", type=float, default=1.0, help="Tempo scale applied to B")
    parser.add_argument("--loop-beats", type=int, choices=(0, 1, 2, 4, 8), default=0)
    parser.add_argument("--effect", choices=("none", "echo", "reverb-mid", "reverb-end-cut", "reverb-end-fade"), default="none")
    parser.add_argument("--loudness-match", action="store_true", help="Auto-match B loudness to A within +-6 dB")
    parser.add_argument("--pcm16", action="store_true", help="Export 16-bit PCM WAV instead of float32")
    for track in ("a", "b"):
        parser.add_argument(f"--gain-{track}", type=float, default=0)
        parser.add_argument(f"--bass-{track}", type=float, default=-24, help="Low-band cut depth in dB")
    args = parser.parse_args()
    numbers = [value for value in vars(args).values() if isinstance(value, float)]
    if not all(map(math.isfinite, numbers)) or min(args.exit_start, args.entry, args.context) < 0:
        parser.error("Use finite parameters and nonnegative times")
    if args.bpm <= 0 or args.beats_per_bar < 1 or max(args.bass_a, args.bass_b) > 0:
        parser.error("Use a positive tempo and meter, and bass cut depths at or below 0 dB")
    if not 0.05 <= args.swap_at <= 0.95 or not 0.5 <= args.speed_b <= 2.0:
        parser.error("Use swap-at in [0.05, 0.95] and speed-b in [0.5, 2.0]")
    if args.output.suffix.lower() != ".wav":
        parser.error("Choose a WAV output file")
    if args.output.exists() and any(p.exists() and args.output.samefile(p) for p in (args.outgoing, args.incoming)):
        parser.error("Choose a separate WAV output file")
    try:
        report = render(
            args,
            volume=args.volume,
            eq=args.eq,
            swap_at=args.swap_at,
            filter_a=args.filter_a,
            filter_b=args.filter_b,
            speed_b=args.speed_b,
            loop_beats=args.loop_beats,
            effect=args.effect,
            loudness_match=args.loudness_match,
            pcm16=args.pcm16,
        )
        print(json.dumps(report, indent=2))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))


if __name__ == "__main__":
    main()
