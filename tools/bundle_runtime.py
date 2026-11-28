"""Assemble the pinned Windows x64 FFmpeg decoder used by AutoMix."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from urllib.request import Request, urlopen
import zipfile


FFMPEG_COMMIT = "946fcce07b6dcd0331c8cc609192aeff5e1924f8"
FFMPEG_SHA256 = "0aa2b1de2a5698b20a23e93d539a9a8e82ca0117496c5bdf05d198805f42bb3b"
FFMPEG_SOURCE_URL = f"https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/{FFMPEG_COMMIT}"
FFMPEG_RECIPE = Path(__file__).resolve().with_name("build_ffmpeg.sh")


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def download_source(cache: Path) -> Path:
    dest = cache / f"ffmpeg-{FFMPEG_COMMIT}.tar.gz"
    if dest.is_file() and sha256(dest) == FFMPEG_SHA256:
        return dest
    print(f"Downloading runtime dependency: {dest.name}", flush=True)
    with tempfile.NamedTemporaryFile(dir=cache, prefix=dest.name + "-", suffix=".download", delete=False) as output:
        temp = Path(output.name)
    try:
        request = Request(FFMPEG_SOURCE_URL, headers={"User-Agent": "SPW-AutoMix-build"})
        with urlopen(request, timeout=60) as source, temp.open("wb") as output:
            shutil.copyfileobj(source, output, length=1024 * 1024)
        if sha256(temp) != FFMPEG_SHA256:
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


def build_ffmpeg(cache: Path, source: Path) -> Path:
    build_id = hashlib.sha256(FFMPEG_RECIPE.read_bytes() + FFMPEG_SHA256.encode()).hexdigest()[:16]
    executable = cache / f"ffmpeg-win64-{build_id}.exe"
    if executable.is_file():
        return executable

    toolchain = cache.parent / "ffmpeg-toolchain"
    shell = Path(os.environ.get("AUTOMIX_FFMPEG_BASH", toolchain / "msys64/usr/bin/bash.exe"))
    llvm_bin = Path(os.environ.get("AUTOMIX_FFMPEG_LLVM_BIN", toolchain / "llvm-mingw-20261117-ucrt-x86_64/bin"))
    if not shell.is_file() or not (llvm_bin / "x86_64-w64-mingw32-clang.exe").is_file():
        raise SystemExit("FFmpeg build needs MSYS2 with GNU Make and NASM, plus LLVM-MinGW. "
                         "Set AUTOMIX_FFMPEG_BASH and AUTOMIX_FFMPEG_LLVM_BIN (see README).")
    msys_root = shell.resolve().parents[2]
    env = os.environ.copy()
    env["PATH"] = os.pathsep.join(map(str, [llvm_bin.resolve(), shell.resolve().parent,
                                           msys_root / "ucrt64/bin", env.get("PATH", "")]))
    env["LC_ALL"] = "C"
    with tempfile.TemporaryDirectory(prefix="ffmpeg-build-", dir=cache) as work:
        work = Path(work)
        with tarfile.open(source) as archive:
            source_tree = work / archive.firstmember.name
            archive.extractall(work, filter="data")
        subprocess.run([str(shell.resolve()), "--noprofile", "--norc", FFMPEG_RECIPE.as_posix()],
                       cwd=source_tree, env=env, check=True)
        (source_tree / "ffmpeg.exe").replace(executable)
    return executable


def build_runtime(cache: Path, output: Path, source_bundle: Path) -> str:
    cache.mkdir(parents=True, exist_ok=True)
    source = download_source(cache)
    ffmpeg = build_ffmpeg(cache, source)
    configuration = subprocess.check_output([str(ffmpeg.resolve()), "-buildconf"], stderr=subprocess.STDOUT)
    notice = f"""SPW AutoMix runs FFmpeg as a separate decoder process.

FFmpeg, licensed under LGPL v2.1 or later.
Upstream commit: {FFMPEG_COMMIT}
Unmodified source: {FFMPEG_SOURCE_URL}
Source SHA-256: {FFMPEG_SHA256}
The matching release's FFmpeg source ZIP includes the source tarball and build script.
Build recipe and configuration: licenses/ffmpeg/
"""
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as bundle:
        with ffmpeg.open("rb") as binary:
            write_entry(bundle, "ffmpeg.exe", binary)
        with tarfile.open(source) as upstream:
            root = upstream.firstmember.name
            for name in ("COPYING.LGPLv2.1", "LICENSE.md"):
                with upstream.extractfile(f"{root}/{name}") as license_file:
                    write_entry(bundle, f"licenses/ffmpeg/{name}", license_file)
        bundle.write(FFMPEG_RECIPE, "licenses/ffmpeg/build_ffmpeg.sh")
        bundle.writestr("licenses/ffmpeg/build-configuration.txt", configuration)
        bundle.writestr("licenses/THIRD_PARTY.txt", notice)
    with zipfile.ZipFile(source_bundle, "w") as bundle:
        bundle.write(source, source.name)
        bundle.write(FFMPEG_RECIPE, FFMPEG_RECIPE.name, compress_type=zipfile.ZIP_DEFLATED)
        bundle.writestr("build-configuration.txt", configuration, compress_type=zipfile.ZIP_DEFLATED)
    return sha256(output)
