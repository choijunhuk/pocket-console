#!/usr/bin/env python3
"""Stage checksum-verified SDK DSOs, SDL Java, and the original NRO for the isolated APK."""

import argparse
import hashlib
import json
import shutil
import tarfile
from pathlib import Path, PurePosixPath


ROOT = Path(__file__).resolve().parent
PRODUCTION_JAVA = (
    "dev/pocketconsole/ConsoleUi.java",
    "dev/pocketconsole/ConsoleType.java",
    "dev/pocketconsole/DeviceCapabilities.java",
    "dev/pocketconsole/DeviceProbe.java",
    "dev/pocketconsole/Switch2PlayerActivity.java",
    "dev/pocketconsole/SwitchSessionStop.java",
    "org/yuzu/yuzu_emu/NativeLibrary.java",
    "org/yuzu/yuzu_emu/features/input/NativeInput.java",
    "org/yuzu/yuzu_emu/applets/keyboard/SoftwareKeyboard.java",
)


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def stage(sdk, nro, producer=None):
    manifest = json.loads((sdk / "runtime-deps.json").read_text())
    if manifest.get("abi") != "arm64-v8a":
        raise ValueError("SDK runtime ABI is not arm64-v8a")
    api = manifest.get("android_api")
    if not isinstance(api, int) or not 33 <= api <= 35:
        raise ValueError("SDK runtime Android API is incompatible with the API 35 smoke image")
    entries = manifest.get("libraries")
    if not isinstance(entries, list) or not entries:
        raise ValueError("SDK runtime dependency list is empty")
    names = {entry["name"] for entry in entries}
    if len(names) != len(entries) or "libpocket_switch2.so" not in names:
        raise ValueError("SDK runtime library names are invalid")
    if not {"libhook_impl.so", "libmain_hook.so"} <= names:
        raise ValueError("SDK runtime is missing Vulkan loader hooks")
    source = sdk / "runtime" / "arm64-v8a"
    actual = {path.name for path in source.glob("*.so")}
    if actual != names:
        raise ValueError(f"SDK runtime DSO set differs: expected={sorted(names)} actual={sorted(actual)}")
    system = set(manifest.get("system_needed", []))
    for entry in entries:
        name = entry["name"]
        if not name.endswith(".so") or "/" in name or "\\" in name:
            raise ValueError("invalid SDK runtime DSO name")
        if digest(source / name) != entry["sha256"]:
            raise ValueError(f"SDK runtime checksum mismatch: {name}")
        unresolved = set(entry["needed"]) - names - system
        if unresolved:
            raise ValueError(f"unresolved SDK dependency for {name}: {sorted(unresolved)}")

    archive = sdk / "sdl-java-bootstrap.tar.gz"
    java_root = ROOT / ".cache" / "sdl-java"
    java_root.mkdir(parents=True, exist_ok=True)
    extracted = set()
    with tarfile.open(archive, "r:gz") as contents:
        for member in contents.getmembers():
            path = PurePosixPath(member.name)
            if not member.isfile() or member.issym() or member.islnk():
                continue
            if path.parts[0] == ".":
                path = PurePosixPath(*path.parts[1:])
            if path.parts[:3] != ("org", "libsdl", "app") or path.suffix != ".java" or ".." in path.parts:
                raise ValueError(f"unexpected SDL Java archive path: {member.name}")
            target = java_root.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with contents.extractfile(member) as input_file, target.open("wb") as output:
                shutil.copyfileobj(input_file, output)
            extracted.add(str(path))
    if "org/libsdl/app/SDL.java" not in extracted:
        raise ValueError("SDL Java bootstrap lacks SDL.java")

    linked = ROOT / ".cache" / "linked-java"
    production = (producer or ROOT.parent.parent) / "app" / "src" / "main" / "java"
    for name in PRODUCTION_JAVA:
        source_file = production / name
        if not source_file.is_file():
            raise ValueError(f"production Java source is missing: {name}")
        target = linked / name
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.is_symlink():
            if target.resolve() != source_file.resolve():
                raise ValueError(f"production Java link points elsewhere: {name}")
        elif target.exists():
            raise ValueError(f"production Java link is not a symlink: {name}")
        else:
            target.symlink_to(source_file)

    runtime = ROOT / ".cache" / "sdk" / "runtime" / "arm64-v8a"
    runtime.mkdir(parents=True, exist_ok=True)
    for name in names:
        shutil.copy2(source / name, runtime / name)
    if not nro.is_file() or nro.stat().st_size < 0x80:
        raise ValueError("original NRO is absent or too small")
    with nro.open("rb") as stream:
        stream.seek(0x10)
        if stream.read(4) != b"NRO0":
            raise ValueError("original NRO header is invalid")
    assets = ROOT / ".cache" / "test-assets"
    assets.mkdir(parents=True, exist_ok=True)
    shutil.copy2(nro, assets / "smoke.nro")
    for entry in sorted(entries, key=lambda item: item["name"]):
        print(f"SWITCH_SDK_RUNTIME_LIBRARY name={entry['name']} sha256={entry['sha256']} needed={','.join(sorted(entry['needed']))}")
    print(f"SWITCH_SDK_RUNTIME_SYSTEM_NEEDED {','.join(sorted(system))}")
    print(f"SWITCH_SDK_STAGE_PASS abi=arm64-v8a dsos={len(names)} sdl_java={len(extracted)} linked_java={len(PRODUCTION_JAVA)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk", type=Path)
    parser.add_argument("nro", type=Path)
    parser.add_argument("--producer", type=Path, help="checkout at the SDK-producing run's exact commit")
    args = parser.parse_args()
    stage(args.sdk.resolve(), args.nro.resolve(), args.producer.resolve() if args.producer else None)
