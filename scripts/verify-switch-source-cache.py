#!/usr/bin/env python3
"""Reject local Git credentials before caching the pinned public source tree."""

from pathlib import Path
import re
import sys


SECRET_SETTING = re.compile(r"^\s*(?:\[\s*(?:credential|user)\b|(?:extraheader|password|oauth|token|helper)\s*=)", re.I)
URL_WITH_USERINFO = re.compile(r"https?://[^/\s]+@", re.I)


def verify(source, build=None):
    git_directory = Path(source) / ".git"
    if not (git_directory / "config").is_file():
        raise ValueError("Cached public source Git metadata is missing")
    configs = list(git_directory.rglob("config"))
    if build is not None:
        configs.extend(path for path in Path(build).rglob("config") if ".git" in path.parts)
    for config in configs:
        contents = config.read_text()
        if any(SECRET_SETTING.search(line) or URL_WITH_USERINFO.search(line)
               for line in contents.splitlines()):
            raise ValueError(f"Git config has non-public credential material: {config}")
    return len(configs)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify-switch-source-cache.py source_dir build_dir")
    print(f"PUBLIC_SWITCH_SOURCE_CACHE_PASS configs={verify(sys.argv[1], sys.argv[2])}")
