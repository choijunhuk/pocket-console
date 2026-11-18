#!/usr/bin/env python3
"""Release this workflow's disposable emulator after acceptance checks."""

import os
from pathlib import Path
import signal


def matches_emulator(executable, arguments, sdk):
    expected = Path(sdk).resolve() / "emulator/qemu/linux-x86_64/qemu-system-x86_64-headless"
    return (Path(executable).resolve() == expected
            and any(arguments[i:i + 2] == [b"-avd", b"pocket-console-ci"]
                    for i in range(len(arguments) - 1)))


def main():
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("POCKET_CI_EMULATOR") != "1":
        return
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        return
    for process in Path("/proc").iterdir():
        if not process.name.isdecimal():
            continue
        try:
            executable = (process / "exe").resolve(strict=True)
            arguments = (process / "cmdline").read_bytes().split(b"\0")
            if matches_emulator(executable, arguments, sdk):
                os.kill(int(process.name), signal.SIGKILL)
                print("Stopped disposable CI emulator", process.name)
        except OSError:
            continue


if __name__ == "__main__":
    main()
