#!/usr/bin/env python3
"""Download and safely unpack the checksum-pinned Azahar unified source."""

import argparse
from hashlib import sha256
import json
from pathlib import Path, PurePosixPath
import tarfile
import tempfile
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_LOCK = ROOT / "third_party" / "azahar.lock.json"
DEFAULT_OUTPUT = ROOT / ".cache" / "azahar-source"
MARKER = ".pocket-console-source.json"


def prepare(lock_path: Path, output: Path, archive: Path | None = None) -> Path:
    lock = json.loads(lock_path.read_text())
    output = output.resolve()
    if output.exists():
        marker = output / MARKER
        if marker.is_file() and json.loads(marker.read_text()) == lock and (output / "CMakeLists.txt").is_file():
            return output
        raise ValueError(f"source directory already exists with a different pin: {output}")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="azahar-source-", dir=output.parent) as temp_name:
        temp = Path(temp_name)
        source_archive = archive or temp / "source.tar.xz"
        if archive is None:
            with urlopen(lock["source_url"], timeout=60) as response, source_archive.open("wb") as target:
                while chunk := response.read(1024 * 1024):
                    target.write(chunk)
        digest = sha256()
        with source_archive.open("rb") as source:
            while chunk := source.read(1024 * 1024):
                digest.update(chunk)
        if digest.hexdigest() != lock["source_sha256"]:
            raise ValueError("Azahar source SHA256 mismatch")

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
