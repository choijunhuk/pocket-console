#!/usr/bin/env python3
"""Bundle the tracked app and exact prepared DS/3DS sources for a release."""

import argparse
from hashlib import sha256
import io
import json
import os
from pathlib import Path
from pathlib import PurePosixPath
import subprocess
import tarfile


ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {".git", ".cache", ".secrets"}
SENSITIVE_SUFFIXES = {".apk", ".jks", ".keystore", ".p12", ".pfx", ".keys", ".sav", ".nds",
                      ".3ds", ".3dsx", ".nsp", ".xci", ".nro"}


def git(root, *args):
    return subprocess.check_output(["git", *args], cwd=root).decode().strip()


def add_file(archive, path, name):
    info = archive.gettarinfo(str(path), arcname=f"pocket-console/{name}")
    if info.issym():
        target = PurePosixPath(info.linkname)
        parts = list(PurePosixPath(info.name).parent.parts)
        if target.is_absolute():
            raise ValueError(f"Unsafe release source symlink: {name}")
        for part in target.parts:
            if part == "..":
                if len(parts) == 1:
                    raise ValueError(f"Unsafe release source symlink: {name}")
                parts.pop()
            elif part != ".":
                parts.append(part)
    info.uid = info.gid = info.mtime = 0
    info.uname = info.gname = ""
    if info.isfile():
        with path.open("rb") as source:
            archive.addfile(info, source)
    elif info.isdir() or info.issym():
        archive.addfile(info)
    else:
        raise ValueError(f"Unexpected release source entry: {name}")


def bundle(root, azahar, output, tag):
    root, azahar, output = map(Path, (root, azahar, output))
    version = None
    for line in (root / "app/build.gradle.kts").read_text().splitlines():
        if "versionName =" in line:
            version = line.split('"')[1]
            break
    if not version or tag != f"v{version}":
        raise ValueError("Release tag does not match the APK version")
    melon = root / "third_party/melonDS"
    if not melon.is_dir() or not (melon / "LICENSE").is_file():
        raise ValueError("Missing prepared melonDS source or LICENSE")
    if not azahar.is_dir() or not (azahar / "license.txt").is_file():
        raise ValueError("Missing prepared Azahar source or license.txt")

    tracked = [os.fsdecode(item) for item in subprocess.check_output(
        ["git", "ls-files", "-z"], cwd=root).split(b"\0") if item]
    if not tracked or not (root / "LICENSE").is_file():
        raise ValueError("Tracked project source or LICENSE is missing")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    try:
        with tarfile.open(temporary, "w:gz") as archive:
            for name in sorted(tracked):
                path = Path(name)
                if (path.is_absolute() or ".." in path.parts or path.suffix.lower() in SENSITIVE_SUFFIXES
                        or any(part in EXCLUDED for part in path.parts)):
                    raise ValueError(f"Unexpected tracked release source: {name}")
                if name == "third_party/melonDS":
                    continue
                add_file(archive, root / path, path.as_posix())
            for source, destination in ((melon, "third_party/melonDS"),
                                        (azahar, "third_party/azahar")):
                for current, directories, files in os.walk(source, followlinks=False):
                    directories[:] = sorted(item for item in directories if item not in EXCLUDED)
                    for filename in sorted(files):
                        if filename in EXCLUDED:
                            continue
                        path = Path(current) / filename
                        name = f"{destination}/{path.relative_to(source).as_posix()}"
                        add_file(archive, path, name)
            pin = json.loads((root / "third_party/azahar.lock.json").read_text())
            manifest = {
                "tag": tag,
                "source_commit": git(root, "rev-parse", "HEAD"),
                "melon_ds_commit": git(melon, "rev-parse", "HEAD"),
                "azahar_source_commit": pin["source_commit"],
                "azahar_source_sha256": pin["source_sha256"],
            }
            data = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode()
            info = tarfile.TarInfo("pocket-console/SOURCE-MANIFEST.json")
            info.size = len(data)
            info.mtime = 0
            archive.addfile(info, io.BytesIO(data))
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
    checksum = sha256()
    with output.open("rb") as source:
        while block := source.read(1024 * 1024):
            checksum.update(block)
    print(f"SOURCE_BUNDLE_PASS {checksum.hexdigest()} {output}")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--azahar", type=Path, default=ROOT / ".cache/azahar-source")
    args = parser.parse_args()
    bundle(ROOT, args.azahar, args.output, args.tag)
