"""Build a .spmod against the SPW installation that will load it."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

from bundle_runtime import build_runtime

ROOT = Path(__file__).resolve().parents[1]
VERSION = "0.1.0"
PLUGIN_ID = "com.jethuit.spw.automix"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--toolchain", type=Path, default=ROOT.parent / "spw status/build/tools")
    parser.add_argument("--host", type=Path, default=Path("E:/SteamLibrary/steamapps/common/Salt Player for Windows/app/ffmpeg-x64.dll"))
    parser.add_argument("--system-runtime", action="store_true", help="Build the light edition using the computer's FFmpeg")
    args = parser.parse_args()
    tools = args.toolchain.resolve()
    kotlin = tools / "kotlin/kotlinc/lib"
    bundled_java = next((tools / "jdk25").glob("*/bin/java.exe"), None)
    java = os.environ.get("AUTOMIX_BUILD_JAVA") or shutil.which("java") or bundled_java
    if not java:
        raise SystemExit("Java not found. Set AUTOMIX_BUILD_JAVA to a JDK's java executable.")
    version = f"{VERSION}.lite" if args.system_runtime else VERSION
    out = ROOT / "build/libs" / f"SPW-AutoMix-v{version}.spmod"
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.exists():
        raise SystemExit(f"Version already built: {out}")
    with tempfile.TemporaryDirectory(prefix="automix-", dir=out.parent.parent) as work:
        work = Path(work)
        host = work / "host.jar"
        shutil.copyfile(args.host, host)
        classes = work / "classes"
        index = work / "compose-index.jar"
        subprocess.run([str(java), "-Dfile.encoding=UTF-8", "-cp", str(kotlin / "*"), str(ROOT / "tools/RestoreComposeIndex.java"), str(host), str(index)], check=True)
        cp = os.pathsep.join(map(str, [kotlin / "kotlin-stdlib.jar", index, host]))
        subprocess.run([str(java), "-cp", str(kotlin / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-opt-in=com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi", "-no-stdlib", "-classpath", cp, f"-Xplugin={kotlin / 'compose-compiler-plugin.jar'}",
                        "-jvm-target", "25", "-d", str(classes), *map(str, sorted((ROOT / "plugin/src").glob("*.kt")))], check=True)
        shutil.copytree(ROOT / "plugin/resources", classes, dirs_exist_ok=True)
        if args.system_runtime:
            (classes / "automix-system-runtime").write_text("system\n", encoding="ascii")
        else:
            runtime = work / "automix-runtime.zip"
            digest = build_runtime(out.parent.parent / "runtime-downloads", runtime)
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
            if not args.system_runtime:
                archive.write(runtime, "classes/automix-runtime.zip", compress_type=zipfile.ZIP_STORED)
        with (work / "plugin.zip").open("rb") as src, out.open("xb") as dest:
            shutil.copyfileobj(src, dest)
    print(out)


if __name__ == "__main__":
    main()
