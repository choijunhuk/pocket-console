#!/usr/bin/env python3
"""Fetch the pinned Switch SDK zip from the permanent mirror release, else from the CI artifact."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "third_party/switch-sdk.lock.json"
DEST = ROOT / ".cache/switch-probes/pocket-switch2-arm64-v8a"
REPOSITORY = os.environ.get("GITHUB_REPOSITORY", "choijunhuk/pocket-console")
MIRROR_TAG = "switch-sdk-mirror"


def mirror_has_asset(name):
    result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/tags/{MIRROR_TAG}"],
                            capture_output=True, text=True)
    if result.returncode:
        if "HTTP 404" in result.stderr:
            return False  # no mirror release yet
        raise ValueError("Switch SDK mirror lookup failed: " + result.stderr.strip())
    return any(item.get("name") == name for item in json.loads(result.stdout).get("assets", []))


def download(pin, workdir):
    name = f"pocket-switch2-sdk-{pin['run_id']}.zip"
    target = workdir / name
    if mirror_has_asset(name):
        subprocess.run(["gh", "release", "download", MIRROR_TAG, "-p", name, "-D", str(workdir),
                        "-R", REPOSITORY], check=True)
        return "mirror", target
    with target.open("wb") as out:
        subprocess.run(["gh", "api", f"repos/{REPOSITORY}/actions/artifacts/{pin['artifact_id']}/zip"],
                       stdout=out, check=True)
    return "artifact", target


def verify_zip(path, expected):
    value = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            value.update(chunk)
    if value.hexdigest() != expected:
        raise ValueError(f"Switch SDK zip SHA256 {value.hexdigest()} does not match the lock {expected}")


def extract(path, dest):
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = PurePosixPath(info.filename)
            mode = info.external_attr >> 16
            if (name.is_absolute() or ".." in name.parts or "\\" in info.filename
                    or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError("Switch SDK zip contains an unsafe entry: " + info.filename)
        staged = Path(tempfile.mkdtemp(prefix=".sdk-", dir=dest.parent))
        try:
            archive.extractall(staged)
            old = None
            if dest.exists():
                old = dest.with_name(dest.name + ".old")
                shutil.rmtree(old, ignore_errors=True)
                os.rename(dest, old)
            os.replace(staged, dest)
            if old:
                shutil.rmtree(old, ignore_errors=True)
        except BaseException:
            shutil.rmtree(staged, ignore_errors=True)
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=LOCK)
    parser.add_argument("--dest", type=Path, default=DEST)
    parser.add_argument("--zip-out", type=Path)
    args = parser.parse_args(argv)
    pin = json.loads(args.lock.read_text())
    if not isinstance(pin.get("run_id"), int) or not pin.get("artifact_sha256") or not pin.get("artifact_id"):
        raise ValueError("Switch SDK lock lacks run_id, artifact_id or artifact_sha256")
    with tempfile.TemporaryDirectory() as temp:
        source, zip_path = download(pin, Path(temp))
        verify_zip(zip_path, pin["artifact_sha256"])
        if args.zip_out:
            shutil.copyfile(zip_path, args.zip_out)
        else:
            args.dest.parent.mkdir(parents=True, exist_ok=True)
            extract(zip_path, args.dest)
    print(f"SWITCH_SDK_FETCH_PASS source={source} run_id={pin['run_id']}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(f"SWITCH_SDK_FETCH_FAIL: {error}")
