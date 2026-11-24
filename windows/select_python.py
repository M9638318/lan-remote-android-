from __future__ import annotations

import os
import re
import struct
import subprocess
import sys
from pathlib import Path

MIN = (3, 10)
MAX_EXCLUSIVE = (3, 15)


def supported(exe: str) -> tuple[bool, str]:
    try:
        result = subprocess.run(
            [exe, "-c", "import sys,struct; print('%d.%d|%d' % (*sys.version_info[:2], struct.calcsize('P')*8))"],
            capture_output=True, text=True, timeout=8,
        )
        if result.returncode:
            return False, ""
        version, bits = result.stdout.strip().split("|")
        major, minor = map(int, version.split("."))
        return MIN <= (major, minor) < MAX_EXCLUSIVE and bits == "64", version
    except Exception:
        return False, ""


def main() -> int:
    candidates: list[tuple[str, str]] = []
    try:
        listing = subprocess.run(["py", "-0p"], capture_output=True, text=True, timeout=8)
        for line in listing.stdout.splitlines():
            match = re.search(r"([A-Za-z]:\\.*?python(?:w)?\.exe)\s*$", line, re.I)
            if match:
                path = str(Path(match.group(1)))
                ok, version = supported(path)
                if ok and all(existing[0].lower() != path.lower() for existing in candidates):
                    candidates.append((path, version))
    except Exception:
        pass

    current = str(Path(sys.executable))
    ok, version = supported(current)
    if ok and all(existing[0].lower() != current.lower() for existing in candidates):
        candidates.append((current, version))

    if not candidates:
        print("ERROR: No compatible 64-bit Python 3.10-3.14 found.", file=sys.stderr)
        return 2
    candidates.sort(key=lambda item: tuple(map(int, item[1].split("."))), reverse=True)
    if len(candidates) == 1:
        chosen = candidates[0]
    elif os.environ.get("CI", "").lower() == "true":
        # CI should use the interpreter that invoked this selector; never prompt.
        chosen = next((item for item in candidates if item[0].lower() == current.lower()), candidates[0])
    else:
        print("Detected compatible Python runtimes:", file=sys.stderr)
        for index, (path, ver) in enumerate(candidates, 1):
            marker = " (recommended)" if index == 1 else ""
            print(f"  {index}. Python {ver}{marker} - {path}", file=sys.stderr)
        print(f"Select Python [1-{len(candidates)}] (default 1): ", end="", file=sys.stderr, flush=True)
        answer = input().strip()
        try:
            chosen = candidates[int(answer) - 1] if answer else candidates[0]
        except (ValueError, IndexError):
            print("ERROR: Invalid selection.", file=sys.stderr)
            return 3
    print(chosen[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
