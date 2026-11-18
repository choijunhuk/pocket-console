#!/usr/bin/env python3
"""Stage an optional pinned Switch SDK into the single production APK."""

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT / ".cache/switch-probes/pocket-switch2-arm64-v8a"
OUTPUT = ROOT / ".cache/switch-jni"
NAMES = {"libpocket_switch2.so", "libhook_impl.so", "libmain_hook.so", "libc++_shared.so"}


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def prepare(sdk=SDK, output=OUTPUT, lock=ROOT / "third_party/switch-hokkaido.lock.json",
            sdk_root=None):
    if not sdk.is_dir():
        if output.exists():
            shutil.rmtree(output)
        return "SWITCH_JNI_NOT_PACKAGED"
    pin = json.loads(lock.read_text())
    if json.loads((sdk / "source-lock.json").read_text()) != pin:
        raise ValueError("Switch SDK source lock mismatch")
    manifest = json.loads((sdk / "runtime-deps.json").read_text())
    if manifest.get("abi") != "arm64-v8a" or manifest.get("android_api") != 33:
        raise ValueError("Switch SDK ABI or API mismatch")
    libraries = manifest.get("libraries", [])
    if {item.get("name") for item in libraries} != NAMES or len(libraries) != len(NAMES):
        raise ValueError("Switch SDK runtime closure is incomplete")
    source = sdk / "runtime/arm64-v8a"
    if {path.name for path in source.glob("*.so")} != NAMES:
        raise ValueError("Switch SDK runtime contains unexpected libraries")
    system = set(manifest.get("system_needed", []))
    for item in libraries:
        path = source / item["name"]
        if digest(path) != item.get("sha256"):
            raise ValueError("Switch SDK library checksum mismatch: " + item["name"])
        if set(item.get("needed", [])) - NAMES - system:
            raise ValueError("Switch SDK library dependencies are incomplete")
        with path.open("rb") as binary:
            header = binary.read(64)
        if len(header) != 64 or header[:7] != b"\x7fELF\x02\x01\x01" or struct.unpack_from("<H", header, 18)[0] != 183:
            raise ValueError("Switch SDK library is not ARM64 ELF: " + item["name"])
    sdk_root = sdk_root or Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or "/missing-android-sdk")
    runtimes = list((sdk_root / "ndk/28.2.13676358/toolchains/llvm/prebuilt").glob(
        "*/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"))
    if len(runtimes) != 1 or digest(runtimes[0]) != digest(source / "libc++_shared.so"):
        raise ValueError("Switch SDK C++ runtime differs from the pinned Android NDK")
    if not (sdk / "SHA256SUMS").is_file():
        raise ValueError("Switch SDK checksums are missing")
    checked = set()
    for line in (sdk / "SHA256SUMS").read_text().splitlines():
        checksum, name = line.split(maxsplit=1)
        name = name.lstrip("*")
        path = PurePosixPath(name)
        if path.is_absolute() or ".." in path.parts or name in checked:
            raise ValueError("Switch SDK checksum manifest has unsafe path")
        candidate = sdk / name
        if not candidate.is_file() or candidate.is_symlink() or digest(candidate) != checksum:
            raise ValueError("Switch SDK artifact checksum mismatch: " + name)
        checked.add(name)
    required = {"source-lock.json", "runtime-deps.json", "sdl-java-bootstrap.tar.gz"}
    required.update("runtime/arm64-v8a/" + name for name in NAMES)
    if not required <= checked:
        raise ValueError("Switch SDK checksum manifest omits required files")
    with tarfile.open(sdk / "sdl-java-bootstrap.tar.gz", "r:gz") as archive:
        java = {}
        for member in archive.getmembers():
            path = PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or member.issym() or member.islnk():
                raise ValueError("Switch SDK SDL Java archive has an unsafe member")
            if member.isdir() and path.parts in (("org",), ("org", "libsdl"), ("org", "libsdl", "app")):
                continue
            if path.parts[:3] != ("org", "libsdl", "app") or path.suffix != ".java" or not member.isfile() or str(path) in java:
                raise ValueError("Switch SDK SDL Java archive has an unsafe member")
            java[str(path)] = archive.extractfile(member).read()
    if "org/libsdl/app/SDL.java" not in java:
        raise ValueError("Switch SDK SDL bootstrap is incomplete")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent, prefix="switch-jni-") as temporary:
        staged = Path(temporary)
        for name in NAMES - {"libc++_shared.so"}:
            target = staged / "runtime/arm64-v8a" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source / name, target)
        for name, data in java.items():
            target = staged / "java" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        if output.exists():
            shutil.rmtree(output)
        os.replace(staged, output)
    return "SWITCH_JNI_PREPARATION_PASS"


if __name__ == "__main__":
    print(prepare())
