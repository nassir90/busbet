"""Everything icon-tool does, with no user interface attached.

The GTK window in app.py was the only way to reach any of this: building an AAB, generating
mipmaps, installing to a device. Splitting the work out means a second front end -- a CLI, an
MCP server, an HTTP API -- can call the same code instead of reimplementing it, and it can be
tested without a display.

Progress is reported through a `log_cb(str)` callback rather than printed, so a caller decides
where output goes. Long operations block; run them off your UI thread.
"""

import os
import re
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT_DIR = Path(__file__).parent.parent.parent

ANDROID_SDK_CANDIDATES = ["/opt/android-sdk", os.environ.get("ANDROID_HOME", ""), os.environ.get("ANDROID_SDK_ROOT", "")]
JAVA_HOME_DEFAULT = "/usr/lib/jvm/java-17-openjdk"


def find_adb() -> str:
    for sdk in ANDROID_SDK_CANDIDATES:
        if not sdk:
            continue
        candidate = Path(sdk) / "platform-tools" / "adb"
        if candidate.exists():
            return str(candidate)
    return "adb"  # fall back to PATH


ADB_BIN = find_adb()


@dataclass
class AppProject:
    name: str
    project_dir: Path
    res_dir: Path
    application_id: str | None


def discover_apps(root: Path) -> dict[str, AppProject]:
    """Find every sibling project that follows the tfi-app/bronto-pronto layout:
    <name>/app/src/main/res with an AndroidManifest.xml next to it, and a
    gradlew at the project root. Returns {project_name: AppProject}, sorted by
    name."""
    apps = {}
    for manifest in sorted(root.glob("*/app/src/main/AndroidManifest.xml")):
        project_dir = manifest.parents[3]
        res_dir = manifest.parent / "res"
        apps[project_dir.name] = AppProject(
            name=project_dir.name,
            project_dir=project_dir,
            res_dir=res_dir,
            application_id=find_application_id(project_dir),
        )
    return apps


def find_application_id(project_dir: Path) -> str | None:
    build_gradle = project_dir / "app" / "build.gradle"
    if not build_gradle.exists():
        return None
    m = re.search(r'applicationId\s+"([^"]+)"', build_gradle.read_text())
    return m.group(1) if m else None


@dataclass
class AdbDevice:
    serial: str
    model: str | None

    @property
    def label(self) -> str:
        return f"{self.model} ({self.serial})" if self.model else self.serial


def list_adb_devices() -> list[AdbDevice]:
    """Returns devices in 'device' state (skips unauthorized/offline), with a
    human-readable model name from `adb devices -l`'s `model:` field when
    available (e.g. "A001" rather than just the serial)."""
    try:
        result = subprocess.run([ADB_BIN, "devices", "-l"], capture_output=True, text=True, timeout=10, check=True)
    except Exception:
        return []
    devices = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            serial = parts[0]
            model = None
            for part in parts[2:]:
                if part.startswith("model:"):
                    model = part[len("model:"):]
                    break
            devices.append(AdbDevice(serial=serial, model=model))
    return devices


def _run_gradle(project_dir: Path, args: list[str], log_cb) -> None:
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME_DEFAULT
    proc = subprocess.Popen(
        ["./gradlew", *args],
        cwd=project_dir, env=env,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    for line in proc.stdout:
        log_cb(line.rstrip())
    code = proc.wait()
    if code != 0:
        raise RuntimeError(f"gradlew exited with code {code}")


def build_app(project_dir: Path, log_cb) -> None:
    _run_gradle(project_dir, [":app:assembleDebug", "--build-cache", "--offline"], log_cb)


def build_aab(project_dir: Path, log_cb) -> None:
    """Release App Bundle -- the artifact Play accepts. Deliberately not --offline: a release
    build can need to resolve something the debug build never pulled, and a cache miss then
    fails the build instead of quietly going and fetching it."""
    _run_gradle(project_dir, [":app:bundleRelease", "--build-cache"], log_cb)


def find_apk(project_dir: Path) -> Path:
    apk = project_dir / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk.exists():
        raise RuntimeError(f"No APK found at {apk} -- build first")
    return apk


def find_aab(project_dir: Path) -> Path:
    aab = project_dir / "app" / "build" / "outputs" / "bundle" / "release" / "app-release.aab"
    if not aab.exists():
        raise RuntimeError(f"No AAB found at {aab} -- build first")
    return aab


def aab_is_signed(aab: Path) -> bool:
    """An unsigned bundle is what you get when release.* is missing from local.properties. Gradle
    only warns, so it's worth catching here rather than at upload."""
    proc = subprocess.run(
        ["unzip", "-l", str(aab)],
        capture_output=True, text=True,
    )
    return "META-INF/" in proc.stdout and (".RSA" in proc.stdout or ".EC" in proc.stdout or ".DSA" in proc.stdout)


def reveal_in_file_manager(path: Path, log_cb) -> None:
    """Select the file in the desktop's file manager. The FileManager1 D-Bus interface highlights
    the file itself; if no file manager implements it, fall back to opening the parent directory."""
    try:
        subprocess.run(
            [
                "dbus-send", "--session", "--print-reply",
                "--dest=org.freedesktop.FileManager1",
                "/org/freedesktop/FileManager1",
                "org.freedesktop.FileManager1.ShowItems",
                f"array:string:file://{path}", "string:",
            ],
            capture_output=True, text=True, check=True, timeout=10,
        )
        log_cb(f"Revealed {path}")
        return
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, FileNotFoundError) as e:
        log_cb(f"FileManager1 unavailable ({type(e).__name__}), opening parent directory instead")

    subprocess.Popen(
        ["xdg-open", str(path.parent)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    log_cb(f"Opened {path.parent}")


def install_apk(apk: Path, serial: str, log_cb) -> None:
    proc = subprocess.Popen(
        [ADB_BIN, "-s", serial, "install", "-r", str(apk)],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    for line in proc.stdout:
        log_cb(line.rstrip())
    code = proc.wait()
    if code != 0:
        raise RuntimeError(f"adb install exited with code {code}")


def launch_app(application_id: str, serial: str, log_cb) -> None:
    """Launches the app's main activity via monkey -- doesn't require knowing
    the launcher activity's class name, unlike `adb shell am start`."""
    proc = subprocess.Popen(
        [ADB_BIN, "-s", serial, "shell", "monkey", "-p", application_id,
         "-c", "android.intent.category.LAUNCHER", "1"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1,
    )
    for line in proc.stdout:
        log_cb(line.rstrip())
    code = proc.wait()
    if code != 0:
        raise RuntimeError(f"adb shell monkey exited with code {code}")


VARIANTS = [
    ("mdpi",    48),
    ("hdpi",    72),
    ("xhdpi",   96),
    ("xxhdpi",  144),
    ("xxxhdpi", 192),
]


def remove_background(source_path: Path, fuzz_percent: int = 10) -> Path:
    """Flood-fills transparency in from each of the 4 corners, matching pixels
    within fuzz_percent of each corner's own color -- handles a plain white (or
    any solid-ish) background without assuming it's specifically white, and
    without touching same-colored regions in the middle of the image that
    aren't connected to an edge."""
    identify = subprocess.run(
        ["magick", str(source_path), "-format", "%w %h", "info:"],
        capture_output=True, text=True, check=True,
    )
    w, h = (int(n) for n in identify.stdout.split())
    tmp = Path(tempfile.mkstemp(suffix=".png")[1])
    subprocess.run([
        "magick", str(source_path),
        "-alpha", "set",
        "-fuzz", f"{fuzz_percent}%",
        "-fill", "none",
        "-draw", "color 0,0 floodfill",
        "-draw", f"color {w - 1},0 floodfill",
        "-draw", f"color 0,{h - 1} floodfill",
        "-draw", f"color {w - 1},{h - 1} floodfill",
        str(tmp),
    ], check=True)
    return tmp


def generate_icons(source_path: Path, res_dir: Path, remove_bg: bool = False) -> None:
    if remove_bg:
        source_path = remove_background(source_path)
    for density, size in VARIANTS:
        for name, round_ in [("ic_launcher", False), ("ic_launcher_round", True)]:
            dest = res_dir / f"mipmap-{density}" / f"{name}.png"
            dest.parent.mkdir(parents=True, exist_ok=True)
            if round_:
                r = size // 2
                subprocess.run([
                    "magick", str(source_path),
                    "-resize", f"{size}x{size}",
                    "-alpha", "set",
                    "(",
                        "+clone", "-alpha", "transparent",
                        "-fill", "white",
                        "-draw", f"circle {r},{r} {r},0",
                    ")",
                    "-compose", "DstIn", "-composite",
                    str(dest),
                ], check=True)
            else:
                subprocess.run([
                    "magick", str(source_path),
                    "-resize", f"{size}x{size}",
                    str(dest),
                ], check=True)
