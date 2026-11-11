#!/usr/bin/env python3
"""Compile Pocket Console JNI seams before the full native dependency graph."""

import json
from pathlib import Path
import shlex
import subprocess
import sys


OWNED = {"id_cache.cpp", "software_keyboard.cpp", "emu_window.cpp", "first_frame.cpp"}


def object_targets(commands, source_dir, build_dir):
    source_dir = Path(source_dir).resolve()
    build_dir = Path(build_dir).resolve()
    generated = build_dir / "pocket-switch-sdk/native.cpp"
    selected = {}
    for entry in json.loads(Path(commands).read_text()):
        source = Path(entry["file"]).resolve()
        if not (source == generated or source.parent == source_dir
                and source.name in OWNED):
            continue
        args = entry.get("arguments") or shlex.split(entry["command"])
        if "-o" not in args:
            raise ValueError(f"Missing object output for {source.name}")
        output = Path(args[args.index("-o") + 1])
        if not output.is_absolute():
            output = Path(entry["directory"]) / output
        output = output.resolve()
        if not output.is_relative_to(build_dir) or "pocket_switch2.dir" not in str(output):
            raise ValueError(f"Object outside Pocket Console target: {source.name}")
        selected[source] = str(output.relative_to(build_dir))
    if {file.name for file in selected} != OWNED | {"native.cpp"}:
        raise ValueError("Pocket Console SDK source compilation is incomplete")
    return list(selected.values())


if __name__ == "__main__":
    if len(sys.argv) != 5:
        raise SystemExit("usage: build-switch-sdk-objects.py compile_commands.json sdk_source build_dir ninja")
    commands, source, build, ninja = sys.argv[1:]
    targets = object_targets(commands, source, build)
    subprocess.run([ninja, "-C", build, "-j", "2", *targets], check=True)
