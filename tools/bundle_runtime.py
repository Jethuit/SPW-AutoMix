"""Assemble the pinned Windows x64 FFmpeg decoder used by AutoMix."""

import hashlib
from pathlib import Path
import shutil
import tempfile
from urllib.request import Request, urlopen
import zipfile


FFMPEG = (
    "https://github.com/GyanD/codexffmpeg/releases/download/9.0.2/ffmpeg-9.0.2-essentials_build.zip",
    "60f467265b1e312373dbcd92200c2618a74850f98d3d078e94296bb3fa2047ba",
)


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download(cache: Path, asset: tuple[str, str]) -> Path:
    url, expected = asset
    dest = cache / url.rsplit("/", 1)[-1]
    if dest.is_file() and sha256(dest) == expected:
        return dest
    print(f"Downloading runtime dependency: {dest.name}", flush=True)
    with tempfile.NamedTemporaryFile(dir=cache, prefix=dest.name + "-", suffix=".download", delete=False) as output:
        temp = Path(output.name)
    try:
        request = Request(url, headers={"User-Agent": "SPW-AutoMix-build"})
        with urlopen(request, timeout=60) as source, temp.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        if sha256(temp) != expected:
            raise ValueError(f"SHA-256 mismatch for {dest.name}")
        temp.replace(dest)
    finally:
        temp.unlink(missing_ok=True)
    return dest


def write_entry(archive: zipfile.ZipFile, name: str, source) -> None:
    entry = zipfile.ZipInfo(name)
    entry.compress_type = zipfile.ZIP_DEFLATED
    with archive.open(entry, "w") as output:
        shutil.copyfileobj(source, output, length=1024 * 1024)


def build_runtime(cache: Path, output: Path) -> str:
    cache.mkdir(parents=True, exist_ok=True)
    ffmpeg = download(cache, FFMPEG)

    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        with zipfile.ZipFile(ffmpeg) as package:
            executable = next(name for name in package.namelist() if name.endswith("/bin/ffmpeg.exe"))
            with package.open(executable) as source:
                write_entry(bundle, "ffmpeg.exe", source)
            for name in sorted(package.namelist()):
                if name.rsplit("/", 1)[-1].upper() in {"LICENSE", "LICENSE.TXT", "README.TXT"}:
                    with package.open(name) as source:
                        write_entry(bundle, f"licenses/ffmpeg/{name.rsplit('/', 1)[-1]}", source)

        bundle.writestr("licenses/THIRD_PARTY.txt", """SPW AutoMix runs bundled FFmpeg as a separate decoder process.

FFmpeg 9.0.2 essentials build by Gyan Doshi (GPLv3, includes Rubber Band)
License and build notices: licenses/ffmpeg/
Binary upstream: https://github.com/GyanD/codexffmpeg/releases/tag/9.0.2
FFmpeg source for this build: https://github.com/FFmpeg/FFmpeg/tree/946fcce07b
Rubber Band source upstream: https://breakfastquay.com/rubberband/
""")
    return sha256(output)
