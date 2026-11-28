"""Compare the reduced decoder with an FFmpeg build that can create audio fixtures."""

from argparse import ArgumentParser
from array import array
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory


FIXTURES = (
    ("flac", "flac"), ("mp3", "libmp3lame"), ("m4a", "aac"), ("alac.m4a", "alac"),
    ("wav", "pcm_s24le"), ("w64", "pcm_s24le"), ("aiff", "pcm_s16be"), ("ogg", "libvorbis"), ("opus", "libopus"),
    ("webm", "libopus"), ("wv", "wavpack"), ("tta", "tta"), ("wma", "wmav2"), ("ts", "ac3"),
)
SIGNAL = "aevalsrc=0.4*sin(2*PI*349*t)|0.3*sin(2*PI*523*t):s=44100:d=2"


def ffmpeg(program: Path, *arguments: str | Path) -> bytes:
    result = subprocess.run([str(program), "-nostdin", *map(str, arguments)],
                            capture_output=True, timeout=30)
    if result.returncode != 0:
        raise RuntimeError(f"{program.name} {arguments}: {result.stderr.decode(errors='replace')}")
    return result.stdout


def pcm(program: Path, audio: Path, rate: int, channels: int,
        start: str | None = None, duration: str | None = None) -> bytes:
    arguments = ["-v", "error"]
    if start is not None:
        arguments += ["-ss", start]
    arguments += ["-i", str(audio), "-map", "0:a:0"]
    if duration is not None:
        arguments += ["-t", duration]
    return ffmpeg(program, *arguments, "-ac", str(channels), "-ar", str(rate), "-f", "f32le", "pipe:1")


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--sample", action="append", default=[], type=Path, help="Additional audio fixture for clip checks")
    args = parser.parse_args()
    reference, candidate = args.reference.resolve(), args.candidate.resolve()

    assert ffmpeg(candidate, "-hide_banner", "-hwaccels").strip() == b"Hardware acceleration methods:"
    assert b"rubberband" not in ffmpeg(candidate, "-hide_banner", "-filters")
    assert ffmpeg(candidate, "-hide_banner", "-protocols").decode().split() == [
        "Supported", "file", "protocols:", "Input:", "file", "pipe", "Output:", "file", "pipe",
    ]

    def compare(audio: Path, cases: list[tuple], label: str) -> None:
        largest = 0.0
        for case in cases:
            before = pcm(reference, audio, *case)
            after = pcm(candidate, audio, *case)
            assert len(before) == len(after) and before, (label, case, len(before), len(after))
            if before != after:
                delta = max(abs(x - y) for x, y in zip(array("f", before), array("f", after)))
                assert delta <= 1e-5, (label, case, delta)
                largest = max(largest, delta)
        print(f"PASS {label}: {len(cases)} PCM checks, max sample difference {largest:.3g}", flush=True)

    with TemporaryDirectory(prefix="automix-ffmpeg-test-") as temp, ThreadPoolExecutor(4) as pool:
        def check_format(fixture: tuple[str, str]) -> None:
            extension, codec = fixture
            audio = Path(temp) / f"曲目.{extension}"
            ffmpeg(reference, "-v", "error", "-f", "lavfi", "-i", SIGNAL, "-c:a", codec, audio)
            compare(audio, [(16000, 1), (22050, 1), (48000, 2, "0.431", "0.470")], extension)

        list(pool.map(check_format, FIXTURES))
        for audio in args.sample:
            compare(audio.resolve(), [(16000, 1, None, "0.13"), (48000, 2, "0.01", "0.10")], audio.suffix)


if __name__ == "__main__":
    main()
