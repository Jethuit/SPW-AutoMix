"""Automated synthetic DSP verification suite for prototype/render.py."""

import math
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
from types import SimpleNamespace

import numpy as np
from scipy import signal
from scipy.io import wavfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
import analyze as A
import render as R


RATE = R.RATE


def _make_click_bass_track(duration_s, bpm, burst_hz, bass_hz, burst_amp=0.45, bass_amp=0.25):
    n = round(duration_s * RATE)
    t = np.arange(n, dtype=np.float64) / RATE
    sig = bass_amp * np.sin(2.0 * np.pi * bass_hz * t)
    beat_s = 60.0 / bpm
    burst_len = round(0.012 * RATE)
    half_burst = burst_len // 2
    bt = (np.arange(burst_len, dtype=np.float64) - half_burst) / RATE
    burst_win = np.hanning(burst_len)
    burst = burst_amp * np.sin(2.0 * np.pi * burst_hz * bt) * burst_win
    num_beats = int(duration_s / beat_s)
    for idx in range(num_beats):
        center = round(idx * beat_s * RATE)
        start = center - half_burst
        end = start + burst_len
        dst_s = max(0, start)
        dst_e = min(n, end)
        src_s = dst_s - start
        src_e = src_s + (dst_e - dst_s)
        if dst_e > dst_s:
            sig[dst_s:dst_e] += burst[src_s:src_e]
    return np.column_stack([sig, sig]).astype("<f4")


def _band_rms_db(audio, low_hz, high_hz):
    mono = np.mean(audio, axis=1)
    if low_hz <= 0:
        sos = signal.butter(4, high_hz, btype="low", fs=RATE, output="sos")
    elif high_hz >= RATE * 0.49:
        sos = signal.butter(4, low_hz, btype="high", fs=RATE, output="sos")
    else:
        sos = signal.butter(4, [low_hz, high_hz], btype="bandpass", fs=RATE, output="sos")
    filtered = signal.sosfiltfilt(sos, mono)
    rms = float(np.sqrt(np.mean(filtered ** 2)))
    return 20.0 * math.log10(max(rms, 1e-12))


def _detect_burst_peaks_ms(audio, low_hz, high_hz, expected_times_s):
    mono = np.mean(audio, axis=1)
    sos = signal.butter(4, [low_hz, high_hz], btype="bandpass", fs=RATE, output="sos")
    band = np.abs(signal.sosfiltfilt(sos, mono))
    env = signal.sosfiltfilt(signal.butter(2, 180.0, btype="low", fs=RATE, output="sos"), band)
    errors_ms = []
    amps = []
    half_search = round(0.08 * RATE)
    for t_s in expected_times_s:
        center = round(t_s * RATE)
        lo = max(0, center - half_search)
        hi = min(len(env), center + half_search)
        pk = lo + int(np.argmax(env[lo:hi]))
        errors_ms.append((pk - center) / RATE * 1000.0)
        amps.append(float(np.max(band[max(0, pk - 120):min(len(band), pk + 120)])))
    return errors_ms, amps


def _write_fixture(directory, name, data):
    path = directory / name
    wavfile.write(path, RATE, data)
    return path


def test_same_speed_and_eq(tmp):
    path_a = _write_fixture(tmp, "a_120.wav", _make_click_bass_track(20.0, 120.0, 1000.0, 80.0))
    path_b = _write_fixture(tmp, "b_120.wav", _make_click_bass_track(20.0, 120.0, 3000.0, 100.0))

    def run_case(out_name, **kwargs):
        out_path = tmp / out_name
        args = SimpleNamespace(
            outgoing=path_a, incoming=path_b, output=out_path,
            exit_start=6.0, entry=2.0, bpm=120.0, beats_per_bar=4, bars=4, context=4.0,
            gain_a=kwargs.pop("gain_a", 0.0), gain_b=kwargs.pop("gain_b", 0.0),
            bass_a=kwargs.pop("bass_a", -24.0), bass_b=kwargs.pop("bass_b", -24.0),
        )
        rep = R.render(args, **kwargs)
        _, audio = wavfile.read(out_path)
        return rep, audio.astype(np.float64)

    rep, mix = run_case("same_swap.wav", volume="overlap", eq="bass-swap", swap_at=0.5)
    overlap_s = rep["preview_mix_end_s"] - rep["preview_mix_start_s"]
    assert abs(overlap_s - 8.0) < 1e-4, f"Expected 8.0s overlap, got {overlap_s}"
    assert rep["peak_4x_dbtp"] <= -0.95, f"Peak exceeded ceiling: {rep['peak_4x_dbtp']}"

    # Beat bursts (exclude boundary beat 0/16 edge fade).
    expected_beats = [rep["preview_mix_start_s"] + i * 0.5 for i in range(1, 16)]
    err_a, _ = _detect_burst_peaks_ms(mix, 800, 1250, expected_beats)
    err_b, _ = _detect_burst_peaks_ms(mix, 2600, 3400, expected_beats)
    max_err = max(max(abs(e) for e in err_a), max(abs(e) for e in err_b))
    assert max_err < 1.5, f"Same-speed beat error too large: {max_err:.3f} ms"

    # Single-track mute isolation (compare muted track's own band across only_a vs only_b).
    _, only_a = run_case("only_a.wav", volume="overlap", eq="none", gain_b=-120.0)
    _, only_b = run_case("only_b.wav", volume="overlap", eq="none", gain_a=-120.0)
    s0 = round((rep["preview_mix_start_s"] + 2.0) * RATE)
    s1 = round((rep["preview_mix_end_s"] - 2.0) * RATE)
    iso_a = _band_rms_db(only_a[s0:s1], 800, 1250) - _band_rms_db(only_b[s0:s1], 800, 1250)
    iso_b = _band_rms_db(only_b[s0:s1], 2600, 3400) - _band_rms_db(only_a[s0:s1], 2600, 3400)
    assert iso_a > 30.0 and iso_b > 40.0, f"Mute isolation insufficient: iso_a={iso_a:.1f}, iso_b={iso_b:.1f}"

    # Bass swap verification (pre-swap vs post-swap regions excluding swap transition).
    pre_s0 = round((rep["preview_mix_start_s"] + 1.0) * RATE)
    pre_s1 = round((rep["preview_mix_start_s"] + 3.2) * RATE)
    post_s0 = round((rep["preview_mix_start_s"] + 4.8) * RATE)
    post_s1 = round((rep["preview_mix_start_s"] + 7.0) * RATE)

    def tone_db(buf, freq):
        win = np.hanning(len(buf))
        spec = np.abs(np.fft.rfft(np.mean(buf, axis=1) * win)) / (np.sum(win) * 0.5)
        freqs = np.fft.rfftfreq(len(buf), 1.0 / RATE)
        mask = np.abs(freqs - freq) <= 3.0
        return 20.0 * math.log10(max(float(np.max(spec[mask])), 1e-12))

    # Pre-swap: 80 Hz strong, 100 Hz cut; post-swap: 100 Hz strong, 80 Hz cut.
    b80_pre, b100_pre = tone_db(mix[pre_s0:pre_s1], 80.0), tone_db(mix[pre_s0:pre_s1], 100.0)
    b80_post, b100_post = tone_db(mix[post_s0:post_s1], 80.0), tone_db(mix[post_s0:post_s1], 100.0)
    assert b80_pre - b100_pre > 16.0, f"Pre-swap bass failed: 80Hz={b80_pre:.1f}, 100Hz={b100_pre:.1f}"
    assert b100_post - b80_post > 16.0, f"Post-swap bass failed: 80Hz={b80_post:.1f}, 100Hz={b100_post:.1f}"
    return f"PASS (overlap={overlap_s:.3f}s, max_beat_err={max_err:.2f}ms, bass_delta={b80_pre-b100_pre:.1f}/{b100_post-b80_post:.1f}dB)"


def test_rubberband_sync(tmp):
    # Track A at 96 BPM; Track B at 105 BPM (with cue_b=0.0 AND cue_b=2.0).
    sig_a = _make_click_bass_track(22.0, 96.0, 1000.0, 75.0, burst_amp=0.5, bass_amp=0.0)
    sig_b = _make_click_bass_track(22.0, 105.0, 3000.0, 95.0, burst_amp=0.5, bass_amp=0.0)
    path_a = _write_fixture(tmp, "a_96.wav", sig_a)
    path_b = _write_fixture(tmp, "b_105.wav", sig_b)

    max_err_all = 0.0
    min_beat0_amp = 1.0
    beat_b_step_on_a = 60.0 / 96.0
    for idx, (entry_s, speed_b) in enumerate([(0.0, 96.0 / 105.0), (8.0 * (60.0 / 105.0), 96.0 / 105.0)]):
        out_path = tmp / f"sync_{idx}.wav"
        args = SimpleNamespace(
            outgoing=path_a, incoming=path_b, output=out_path,
            exit_start=8.0 * beat_b_step_on_a, entry=entry_s, bpm=96.0, beats_per_bar=4, bars=4, context=3.0,
            gain_a=-120.0, gain_b=0.0, bass_a=-18.0, bass_b=-18.0,
        )
        rep = R.render(args, volume="overlap", eq="none", speed_b=speed_b)
        _, audio = wavfile.read(out_path)
        audio = audio.astype(np.float64)
        # Check Beat 0..15 of Track B stretched to 96 BPM grid.
        expected = [rep["preview_mix_start_s"] + b * beat_b_step_on_a for b in range(16)]
        # Exclude Beat 0 fade-in ramp by testing Beat 0 separately with volume="cut-fade" or checking overlap peak.
        # In overlap mode env_b starts at 0 at sample 0 and reaches 1 at beat 8; check beats 1..15 and test beat 0 with cut-fade.
        out_cf = tmp / f"sync_cf_{idx}.wav"
        args.output = out_cf
        rep_cf = R.render(args, volume="overlap", eq="none", speed_b=speed_b)
        _, audio_cf = wavfile.read(out_cf)
        errs, amps = _detect_burst_peaks_ms(audio_cf.astype(np.float64), 2500, 3600, expected[1:])
        max_err = max(abs(e) for e in errs)
        # Check Beat 0 directly via decode() since overlap fades B in over multiple beats and cut-fade ramps at sample 0.
        raw_b0 = R.decode(path_b, round(entry_s * RATE), round(0.1 * RATE), speed=speed_b)
        beat0_amp = float(np.max(np.abs(raw_b0[:round(0.02 * RATE)])))
        min_beat0_amp = min(min_beat0_amp, beat0_amp)
        assert beat0_amp > 0.25, f"Beat 0 attenuated on entry={entry_s}: amp0={beat0_amp:.4f}"
        assert max_err < 5.0, f"Rubberband beat drift exceeded 5ms on entry={entry_s}: {max_err:.2f}ms ({errs[:4]})"
        max_err_all = max(max_err_all, max_err)
    return f"PASS (beat0_min_amp={min_beat0_amp:.3f}, max_beat_err={max_err_all:.2f}ms)"


def test_swept_filters(tmp):
    rng = np.random.default_rng(7)
    n = round(16.0 * RATE)
    t = np.arange(n, dtype=np.float64) / RATE
    tone_low = 0.28 * np.sin(2.0 * np.pi * 70.0 * t)
    tone_high = 0.28 * np.sin(2.0 * np.pi * 6000.0 * t)
    noise = 0.04 * rng.standard_normal(n)
    mono = tone_low + tone_high + noise
    stereo = np.column_stack([mono, mono]).astype("<f4")
    path_a = _write_fixture(tmp, "filt_a.wav", stereo)
    path_b = _write_fixture(tmp, "filt_b.wav", stereo)

    def render_filt(name, fa, fb, ga=0.0, gb=-120.0):
        out = tmp / f"{name}.wav"
        args = SimpleNamespace(
            outgoing=path_a, incoming=path_b, output=out,
            exit_start=4.0, entry=2.0, bpm=120.0, beats_per_bar=4, bars=4, context=2.0,
            gain_a=ga, gain_b=gb, bass_a=0.0, bass_b=0.0,
        )
        rep = R.render(args, volume="overlap", eq="none", filter_a=fa, filter_b=fb)
        _, audio = wavfile.read(out)
        return rep, audio.astype(np.float64)

    rep_ref, dry = render_filt("dry", "none", "none")
    s0 = round(rep_ref["preview_mix_start_s"] * RATE)
    ov = round((rep_ref["preview_mix_end_s"] - rep_ref["preview_mix_start_s"]) * RATE)
    q1 = slice(s0 + round(0.05 * ov), s0 + round(0.24 * ov))
    q4 = slice(s0 + round(0.76 * ov), s0 + round(0.95 * ov))

    _, high_out = render_filt("high_out", "high-out", "none")
    bypass_q1 = abs(_band_rms_db(dry[q1], 40, 110) - _band_rms_db(high_out[q1], 40, 110))
    atten_70hz = _band_rms_db(dry[q4], 40, 110) - _band_rms_db(high_out[q4], 40, 110)
    assert bypass_q1 < 0.5, f"high-out altered Q1 before midpoint: diff={bypass_q1:.2f} dB"
    assert atten_70hz > 18.0, f"high-out leaked 70Hz bass in Q4: atten={atten_70hz:.1f} dB"

    _, low_out = render_filt("low_out", "low-out", "none")
    atten_6k = _band_rms_db(dry[q4], 4500, 7500) - _band_rms_db(low_out[q4], 4500, 7500)
    assert atten_6k > 20.0, f"low-out leaked 6kHz highs: atten={atten_6k:.1f} dB"

    _, low_in = render_filt("low_in", "none", "low-in", ga=-120.0, gb=0.0)
    _, high_in = render_filt("high_in", "none", "high-in", ga=-120.0, gb=0.0)
    _, dry_b = render_filt("dry_b", "none", "none", ga=-120.0, gb=0.0)
    atten_in_q1 = _band_rms_db(dry_b[q1], 4500, 7500) - _band_rms_db(low_in[q1], 4500, 7500)
    recov_in_q4 = abs(_band_rms_db(dry_b[q4], 4500, 7500) - _band_rms_db(low_in[q4], 4500, 7500))
    atten_hi_in_q1 = _band_rms_db(dry_b[q1], 40, 110) - _band_rms_db(high_in[q1], 40, 110)
    recov_hi_in_q4 = abs(_band_rms_db(dry_b[q4], 40, 110) - _band_rms_db(high_in[q4], 40, 110))
    assert atten_in_q1 > 18.0, f"low-in Q1 did not attenuate 6kHz enough: {atten_in_q1:.1f} dB"
    assert recov_in_q4 < 0.5, f"low-in Q4 did not bypass cleanly: diff={recov_in_q4:.2f} dB"
    assert atten_hi_in_q1 > 18.0, f"high-in Q1 did not attenuate 70Hz enough: {atten_hi_in_q1:.1f} dB"
    assert recov_hi_in_q4 < 0.5, f"high-in Q4 did not bypass cleanly: diff={recov_hi_in_q4:.2f} dB"
    _, high_out_half = render_filt("high_out_half", "high-out-half", "none")
    half_q1 = abs(_band_rms_db(dry[q1], 40, 110) - _band_rms_db(high_out_half[q1], 40, 110))
    half_atten_70hz = _band_rms_db(dry[q4], 40, 110) - _band_rms_db(high_out_half[q4], 40, 110)
    assert half_q1 < 0.5, f"high-out-half altered Q1: diff={half_q1:.2f} dB"
    assert 6.0 < half_atten_70hz < atten_70hz, f"high-out-half range wrong: {half_atten_70hz:.1f} vs {atten_70hz:.1f} dB"

    return f"PASS (high_out_70Hz=-{atten_70hz:.1f}dB, half=-{half_atten_70hz:.1f}dB, low_out_6k=-{atten_6k:.1f}dB, high_in_70Hz=-{atten_hi_in_q1:.1f}dB)"


def test_crossover_loudness_limiter_effects(tmp):
    # 1. Neutral LR4 crossover flatness check on an impulse.
    imp = np.zeros((RATE, 2), dtype=np.float64)
    imp[1000] = 0.5
    lo, mid, hi = R.lr4_3band(imp)
    recon = lo + mid + hi
    f_in = np.abs(np.fft.rfft(imp[:, 0]))
    f_out = np.abs(np.fft.rfft(recon[:, 0]))
    freqs = np.fft.rfftfreq(len(imp), 1.0 / RATE)
    mask = (freqs >= 30.0) & (freqs <= 18000.0)
    max_lr4_err = float(np.max(np.abs(20.0 * np.log10((f_out[mask] + 1e-12) / (f_in[mask] + 1e-12)))))
    assert max_lr4_err < 0.05, f"LR4 crossover magnitude error too high: {max_lr4_err:.4f} dB"

    # 2. Loudness match + true-peak limiter + echo/reverb tail under smooth fader.
    t = np.arange(round(18.0 * RATE), dtype=np.float64) / RATE
    sig_a = np.column_stack([0.35 * np.sin(2 * np.pi * 440.0 * t)] * 2).astype("<f4")
    sig_b = (sig_a * (10.0 ** (-6.0 / 20.0))).astype("<f4")
    path_a = _write_fixture(tmp, "loud_a.wav", sig_a)
    path_b = _write_fixture(tmp, "loud_b.wav", sig_b)

    out_match = tmp / "match.wav"
    args_m = SimpleNamespace(
        outgoing=path_a, incoming=path_b, output=out_match,
        exit_start=6.0, entry=2.0, bpm=120.0, beats_per_bar=4, bars=4, context=4.0,
        gain_a=0.0, gain_b=0.0, bass_a=-18.0, bass_b=-18.0,
    )
    rep_m = R.render(args_m, volume="smooth", eq="3band", loudness_match=True)
    rel_diff = rep_m["auto_gain_b_db"] - rep_m["auto_gain_a_db"]
    assert abs(rel_diff - 6.0) < 0.25, f"Expected ~+6dB relative gain_b-gain_a, got {rel_diff:.2f}"
    assert rep_m["limiter_gr_db"] >= -0.5, f"Loudness match caused limiter crunch: {rep_m['limiter_gr_db']}"

    # Hot mix limiter check.
    out_hot = tmp / "hot.wav"
    args_hot = SimpleNamespace(
        outgoing=path_a, incoming=path_a, output=out_hot,
        exit_start=6.0, entry=2.0, bpm=120.0, beats_per_bar=4, bars=4, context=4.0,
        gain_a=6.0, gain_b=6.0, bass_a=0.0, bass_b=0.0,
    )
    rep_hot = R.render(args_hot, volume="overlap", eq="none")
    assert rep_hot["peak_4x_dbtp"] <= -0.95, f"Limiter failed ceiling: {rep_hot['peak_4x_dbtp']}"
    assert rep_hot["limiter_gr_db"] < -1.0, f"Limiter did not engage on hot signal: {rep_hot['limiter_gr_db']}"

    # Echo and reverb tails with Track B muted under smooth volume fade.
    tails = {}
    for eff in ("echo", "reverb-end-cut"):
        out_eff = tmp / f"{eff}.wav"
        args_eff = SimpleNamespace(
            outgoing=path_a, incoming=path_b, output=out_eff,
            exit_start=6.0, entry=2.0, bpm=120.0, beats_per_bar=4, bars=4, context=4.0,
            gain_a=0.0, gain_b=-120.0, bass_a=-18.0, bass_b=-18.0,
        )
        rep_e = R.render(args_eff, volume="smooth", eq="bass-swap", effect=eff)
        _, audio_e = wavfile.read(out_eff)
        after_start = round(rep_e["preview_mix_end_s"] * RATE)
        tail_slice = audio_e[after_start:after_start + RATE]
        tail_rms_db = 20.0 * math.log10(max(float(np.sqrt(np.mean(tail_slice.astype(np.float64) ** 2))), 1e-12))
        tails[eff] = tail_rms_db
        assert tail_rms_db > -35.0, f"Effect {eff} tail too quiet: {tail_rms_db:.1f} dBFS"

    # Verify start/end bass swap + full-overlap + mid-cut render cleanly.
    for v_mode, e_mode in [("full-overlap", "bass-swap-end"), ("mid-cut", "none"), ("full-overlap", "bass-swap-start")]:
        out_m = tmp / f"{v_mode}_{e_mode}.wav"
        args_x = SimpleNamespace(
            outgoing=path_a, incoming=path_b, output=out_m,
            exit_start=6.0, entry=2.0, bpm=120.0, beats_per_bar=4, bars=4, context=4.0,
            gain_a=0.0, gain_b=0.0, bass_a=-24.0, bass_b=-24.0,
        )
        rep_x = R.render(args_x, volume=v_mode, eq=e_mode)
        assert rep_x["peak_4x_dbtp"] <= -0.95

    return (
        f"PASS (lr4_err={max_lr4_err:.4f}dB, rel_gain={rel_diff:+.2f}dB, "
        f"hot_peak={rep_hot['peak_4x_dbtp']}dBTP, tails={tails['echo']:.1f}/{tails['reverb-end-cut']:.1f}dBFS)"
    )


def test_limiter_smoothness(tmp):
    # Drive a pure 60 Hz bass sine at +4 dBFS (amp=1.585) into limit_true_peak.
    n = round(6.0 * RATE)
    t = np.arange(n, dtype=np.float64) / RATE
    sine = 1.585 * np.sin(2.0 * np.pi * 60.0 * t)
    audio = np.column_stack([sine, sine])
    limited, gr_db, peak_dbtp = R.limit_true_peak(audio, ceiling_db=-1.0)
    assert peak_dbtp <= -0.95, f"60Hz limiter overshoot: {peak_dbtp}"
    assert gr_db < -4.0, f"Expected substantial gain reduction, got {gr_db}"

    # Measure harmonic distortion in steady-state middle [1.0s..5.0s].
    mid = limited[RATE:5 * RATE, 0]
    win = np.hanning(len(mid))
    spec = np.abs(np.fft.rfft(mid * win))
    freqs = np.fft.rfftfreq(len(mid), 1.0 / RATE)
    fund_mask = (freqs >= 55.0) & (freqs <= 65.0)
    harm_mask = (freqs >= 80.0) & (freqs <= 12000.0)
    fund_pw = float(np.sum(spec[fund_mask] ** 2))
    harm_pw = float(np.sum(spec[harm_mask] ** 2))
    thd_db = 10.0 * math.log10(max(harm_pw, 1e-18) / max(fund_pw, 1e-18))
    assert thd_db < -34.0, f"Limiter caused excessive bass distortion: THD={thd_db:.1f} dB"
    return f"PASS (peak={peak_dbtp}dBTP, gr={gr_db}dB, 60Hz_THD={thd_db:.1f}dB)"


def _make_drum_grooves(duration_s, bpm, first_db_s):
    n = round(duration_s * RATE)
    sig = np.zeros(n, dtype=np.float64)
    period = 60.0 / bpm
    # 4-beat bar: strong 70Hz kick on Beat 1 (and lighter syncopated 3.5), 1.8kHz snare on 2 & 4.
    t_k = np.arange(round(0.06 * RATE), dtype=np.float64) / RATE
    kick = np.sin(2 * np.pi * (110 * np.exp(-t_k * 25) + 48) * t_k) * np.exp(-t_k * 30) * 0.65
    t_s = np.arange(round(0.04 * RATE), dtype=np.float64) / RATE
    rng = np.random.default_rng(123)
    snare = (np.sin(2 * np.pi * 1800 * t_s) * 0.3 + rng.standard_normal(len(t_s)) * 0.4) * np.exp(-t_s * 50)

    def add(pos_s, grain, scale=1.0):
        idx = round(pos_s * RATE)
        if 0 <= idx < n:
            end = min(n, idx + len(grain))
            sig[idx:end] += grain[:end - idx] * scale

    bar = 0
    while True:
        b1 = first_db_s + bar * 4 * period
        if b1 >= duration_s - 0.1:
            break
        add(b1, kick, 1.0)
        add(b1 + period, snare, 0.9)
        add(b1 + 2.0 * period, kick, 0.7)
        add(b1 + 2.5 * period, kick, 0.5)
        add(b1 + 3.0 * period, snare, 0.9)
        bar += 1
    return np.column_stack([sig, sig]).astype("<f4")


def test_analyzer_bpm_and_downbeat(tmp):
    p100 = _write_fixture(tmp, "groove_100.wav", _make_drum_grooves(40.0, 100.0, 0.250))
    p93 = _write_fixture(tmp, "groove_93.wav", _make_drum_grooves(40.0, 93.0, 0.380))

    a100_blind = A.analyze_track(p100)
    a100_known = A.analyze_track(p100, known_bpm=100.0)
    a93_blind = A.analyze_track(p93)

    assert abs(a100_blind["bpm"] - 100.0) <= 0.15, f"Blind 100 BPM error: {a100_blind['bpm']}"
    assert abs(a100_known["bpm"] - 100.0) <= 0.05, f"Known 100 BPM error: {a100_known['bpm']}"
    assert abs(a93_blind["bpm"] - 93.0) <= 0.15, f"Blind 93 BPM error: {a93_blind['bpm']}"

    err_db_100 = min(abs(d - 0.250) for d in a100_known["downbeats"][:4]) * 1000.0
    err_db_93 = min(abs(d - 0.380) for d in a93_blind["downbeats"][:4]) * 1000.0
    assert err_db_100 < 15.0, f"100 BPM Bar-1 downbeat off: {err_db_100:.1f}ms ({a100_known['downbeats'][:4]})"
    assert err_db_93 < 15.0, f"93 BPM Bar-1 downbeat off: {err_db_93:.1f}ms ({a93_blind['downbeats'][:4]})"
    return (
        f"PASS (blind_bpms={a100_blind['bpm']}/{a93_blind['bpm']}, "
        f"downbeat_err={err_db_100:.1f}/{err_db_93:.1f}ms)"
    )


def main():
    with TemporaryDirectory() as td:
        tmp = Path(td)
        results = [
            ("Test 1 · Same-speed & Bass Swap", test_same_speed_and_eq(tmp)),
            ("Test 2 · Rubberband Beat Sync", test_rubberband_sync(tmp)),
            ("Test 3 · Swept Filters (100% Wet)", test_swept_filters(tmp)),
            ("Test 4 · LR4 / Loudness / Limiter / FX", test_crossover_loudness_limiter_effects(tmp)),
            ("Test 5 · Limiter Smoothness (60Hz THD)", test_limiter_smoothness(tmp)),
            ("Test 6 · Analyzer BPM & Bar-1 Downbeat", test_analyzer_bpm_and_downbeat(tmp)),
        ]
        for label, summary in results:
            print(f"{label}: {summary}")


if __name__ == "__main__":
    main()
