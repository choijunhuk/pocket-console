#!/usr/bin/env python3
"""Download and safely unpack the checksum-pinned Azahar unified source."""

import argparse
from hashlib import sha256
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile
from urllib.parse import urlsplit, urlunsplit
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCK = ROOT / "third_party" / "azahar.lock.json"
DEFAULT_OUTPUT = ROOT / ".cache" / "azahar-source"
MARKER = ".pocket-console-source.json"


def apply_pinned_patch(source: Path, patch: Path, source_path: str, base_hash: str,
                       patched_hash: str, patch_hash: str) -> None:
    target = source / source_path
    if sha256(patch.read_bytes()).hexdigest() != patch_hash:
        raise ValueError("Azahar patch SHA256 mismatch")
    if sha256(target.read_bytes()).hexdigest() != base_hash:
        raise ValueError(f"Azahar source file SHA256 mismatch: {source_path}")
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=source, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=source, check=True)
    if sha256(target.read_bytes()).hexdigest() != patched_hash:
        raise ValueError(f"Azahar patched file SHA256 mismatch: {source_path}")


def prepare(lock_path: Path, output: Path, archive: Path | None = None) -> Path:
    lock = json.loads(lock_path.read_text())
    if not re.fullmatch(r"[0-9a-f]{64}", lock["source_sha256"]):
        raise ValueError("invalid source_sha256 in Azahar lock (expected 64 lowercase hex characters)")
    patch_config = lock.get("patch")
    if patch_config:
        for field in ("sha256", "source_sha256", "patched_sha256"):
            if not re.fullmatch(r"[0-9a-f]{64}", patch_config[field]):
                raise ValueError(f"invalid patch {field} in Azahar lock")
    output = output.resolve()
    if output.exists():
        marker = output / MARKER
        patched = output / patch_config["source_path"] if patch_config else output / "CMakeLists.txt"
        expected = patch_config["patched_sha256"] if patch_config else None
        if (marker.is_file() and json.loads(marker.read_text()) == lock and
                (output / "CMakeLists.txt").is_file() and patched.is_file() and
                (expected is None or sha256(patched.read_bytes()).hexdigest() == expected)):
            return output
        raise ValueError(f"source directory already exists with a different pin: {output}")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="azahar-source-", dir=output.parent) as temp_name:
        temp = Path(temp_name)
        source_archive = archive or temp / "source.tar.xz"
        content_type = "local archive"
        source_url = str(source_archive)
        if archive is None:
            with urlopen(lock["source_url"], timeout=60) as response, source_archive.open("wb") as target:
                content_type = response.headers.get_content_type()
                final_url = urlsplit(response.geturl())
                source_url = urlunsplit((final_url.scheme, final_url.netloc, final_url.path, "", ""))
                while chunk := response.read(1024 * 1024):
                    target.write(chunk)
        digest = sha256()
        with source_archive.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        if digest.hexdigest() != lock["source_sha256"]:
            raise ValueError(f"Azahar source SHA256 mismatch: actual SHA256 {digest.hexdigest()}, "
                             f"{source_archive.stat().st_size} bytes, content type {content_type}, "
                             f"final URL {source_url}")

        with tarfile.open(source_archive, "r:xz") as tar:
            members = tar.getmembers()
            roots = set()
            for member in members:
                name = PurePosixPath(member.name)
                if name.is_absolute() or ".." in name.parts or not name.parts:
                    raise ValueError(f"unsafe archive path: {member.name}")
                roots.add(name.parts[0])
            if len(roots) != 1:
                raise ValueError("Azahar archive must have one source root")
            tar.extractall(temp / "extracted", filter="data")

        source = temp / "extracted" / roots.pop()
        if not (source / "CMakeLists.txt").is_file():
            raise ValueError("Azahar archive has no CMakeLists.txt")
        if patch_config:
            apply_pinned_patch(source, ROOT / patch_config["file"], patch_config["source_path"],
                               patch_config["source_sha256"], patch_config["patched_sha256"],
                               patch_config["sha256"])
        (source / MARKER).write_text(json.dumps(lock, indent=2) + "\n")
        source.rename(output)
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--archive", type=Path, help="Use a local archive instead of downloading")
    args = parser.parse_args()
    print(prepare(args.lock, args.output, args.archive))


if __name__ == "__main__":
    main()
