"""Build a .spmod against the SPW installation that will load it."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile

from bundle_runtime import build_runtime

ROOT = Path(__file__).resolve().parents[1]
BUILD_DIR = ROOT / "build"
VERSION = "0.3.0"
PLUGIN_ID = "com.jethuit.spw.automix"
SALTUI_VERSION = "3.1.0-alpha02"

MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"

SALTUI_ARTIFACTS = (
    (MAVEN_CENTRAL, "io.github.moriafly", "salt-ui-desktop", SALTUI_VERSION),
    (MAVEN_CENTRAL, "io.github.moriafly", "salt-core-desktop", SALTUI_VERSION),
    (MAVEN_CENTRAL, "io.github.moriafly", "salt-adaptive-window-desktop", SALTUI_VERSION),
    (GOOGLE_MAVEN, "androidx.graphics", "graphics-shapes-desktop", "1.0.1"),
    (GOOGLE_MAVEN, "androidx.collection", "collection-jvm", "1.7.0"),
    (MAVEN_CENTRAL, "dev.chrisbanes.haze", "haze-jvm", "2.1.0"),
    (MAVEN_CENTRAL, "dev.chrisbanes.haze", "haze-blur-jvm", "2.1.0"),
    (MAVEN_CENTRAL, "dev.chrisbanes.haze", "haze-blur-materials-jvm", "2.1.0"),
    (MAVEN_CENTRAL, "dev.chrisbanes.haze", "haze-glass-jvm", "2.1.0"),
    (MAVEN_CENTRAL, "dev.chrisbanes.haze", "haze-utils-jvm", "2.1.0"),
)


def ensure_saltui_jars(cache_dir: Path) -> list[Path]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    opener = urllib.request.build_opener()
    opener.addheaders = [("User-Agent", "Mozilla/5.0")]
    jars: list[Path] = []
    for repo, group, artifact, version in SALTUI_ARTIFACTS:
        jar = cache_dir / f"{artifact}-{version}.jar"
        if not jar.exists() or jar.stat().st_size == 0:
            url = f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"
            print(f"Downloading {url}", flush=True)
            with tempfile.TemporaryDirectory(prefix=f"{artifact}-", dir=cache_dir) as work:
                part = Path(work) / jar.name
                with opener.open(url, timeout=180) as response, part.open("wb") as handle:
                    shutil.copyfileobj(response, handle)
                # Another build can finish the same download while this one is running.
                if not jar.exists() or jar.stat().st_size == 0:
                    part.replace(jar)
        jars.append(jar)
    return jars


def ensure_shaded_jars(java: str | Path, kotlin: Path, raw_jars: list[Path], shaded_dir: Path) -> list[Path]:
    shaded_dir.mkdir(parents=True, exist_ok=True)
    shader = ROOT / "tools/ShadeSaltUi.java"
    shader_mtime = shader.stat().st_mtime
    stale: list[Path] = []
    shaded: list[Path] = []
    for raw in raw_jars:
        target = shaded_dir / raw.name
        shaded.append(target)
        if not target.exists() or target.stat().st_mtime < max(raw.stat().st_mtime, shader_mtime):
            stale.append(raw)
    if stale:
        subprocess.run([str(java), "-Dfile.encoding=UTF-8", "-cp", str(kotlin / "*"),
                        str(shader), "--jars", str(shaded_dir), *map(str, stale)], check=True)
    return shaded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--toolchain", type=Path, default=ROOT.parent / "spw status/build/tools")
    parser.add_argument("--host", type=Path, default=Path("E:/SteamLibrary/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll"))
    parser.add_argument("--system-runtime", action="store_true", help="Build the light edition using the computer's FFmpeg")
    parser.add_argument("--output-dir", type=Path, default=BUILD_DIR / "libs", help="Directory for the install package and source bundle")
    args = parser.parse_args()
    tools = args.toolchain.resolve()
    kotlin = tools / "kotlin/kotlinc/lib"
    bundled_java = next((tools / "jdk25").glob("*/bin/java.exe"), None)
    java = os.environ.get("AUTOMIX_BUILD_JAVA") or shutil.which("java") or bundled_java
    if not java:
        raise SystemExit("Java not found. Set AUTOMIX_BUILD_JAVA to a JDK's java executable.")
    version = f"{VERSION}.lite" if args.system_runtime else VERSION
    out = args.output_dir.resolve() / f"SPW-AutoMix-v{version}.spmod"
    source_out = out.with_name(f"SPW-AutoMix-v{version}-FFmpeg-source.zip")
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        raise SystemExit(f"Version already built: {out}")
    if not args.system_runtime and source_out.exists():
        raise SystemExit(f"FFmpeg source bundle already built: {source_out}")
    salt_cache = BUILD_DIR / "saltui-cache" / SALTUI_VERSION
    raw_salt_jars = ensure_saltui_jars(salt_cache)
    shaded_salt_jars = ensure_shaded_jars(java, kotlin, raw_salt_jars, salt_cache / "shaded")
    with tempfile.TemporaryDirectory(prefix="automix-", dir=BUILD_DIR) as work:
        work = Path(work)
        host = work / "host.jar"
        shutil.copyfile(args.host, host)
        classes = work / "classes"
        index = work / "compose-index.jar"
        subprocess.run([str(java), "-Dfile.encoding=UTF-8", "-cp", str(kotlin / "*"), str(ROOT / "tools/RestoreComposeIndex.java"), str(host), str(index)], check=True)
        cp = os.pathsep.join(map(str, [kotlin / "kotlin-stdlib.jar", *raw_salt_jars, index, host]))
        subprocess.run([str(java), "-cp", str(kotlin / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-opt-in=com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi", "-no-stdlib", "-classpath", cp, f"-Xplugin={kotlin / 'compose-compiler-plugin.jar'}",
                        "-jvm-target", "25", "-d", str(classes), *map(str, sorted((ROOT / "plugin/src").glob("*.kt")))], check=True)
        subprocess.run([str(java), "-Dfile.encoding=UTF-8", "-cp", str(kotlin / "*"), str(ROOT / "tools/ShadeSaltUi.java"), "--dir", str(classes)], check=True)
        shutil.copytree(ROOT / "plugin/resources", classes, dirs_exist_ok=True)
        shutil.copyfile(ROOT / "LICENSE", classes / "META-INF/LICENSE-AutoMix")
        if args.system_runtime:
            (classes / "automix-system-runtime").write_text("system\n", encoding="ascii")
        else:
            runtime = work / "automix-runtime.zip"
            runtime_source = work / "ffmpeg-source.zip"
            digest = build_runtime(BUILD_DIR / "runtime-downloads", runtime, runtime_source)
            (classes / "automix-runtime.sha256").write_text(digest + "\n", encoding="ascii")
        manifest = ["Manifest-Version: 1.0", "Plugin-Class: automix.MainPlugin",
                    f"Plugin-Id: {PLUGIN_ID}", f"Plugin-Version: {version}", "Plugin-Name: AutoMix",
                    "Plugin-Provider: Jethuit", "Plugin-Description: Dual-track transition editor for SPW",
                    "Plugin-Has-Config: true", "Plugin-Permissions: library-read,class-transform"]
        with zipfile.ZipFile(work / "plugin.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
            archive.writestr("classes/META-INF/MANIFEST.MF", "\r\n".join(manifest) + "\r\n\r\n")
            for file in classes.rglob("*"):
                if file.is_file():
                    archive.write(file, "classes/" + file.relative_to(classes).as_posix())
            for jar in shaded_salt_jars:
                archive.write(jar, "lib/" + jar.name)
            if not args.system_runtime:
                archive.write(runtime, "classes/automix-runtime.zip", compress_type=zipfile.ZIP_STORED)
        with (work / "plugin.zip").open("rb") as src, out.open("xb") as dest:
            shutil.copyfileobj(src, dest)
        if not args.system_runtime:
            with runtime_source.open("rb") as src, source_out.open("xb") as dest:
                shutil.copyfileobj(src, dest)
            print(source_out)
    print(out)


if __name__ == "__main__":
    main()
