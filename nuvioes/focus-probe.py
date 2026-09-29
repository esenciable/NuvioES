#!/usr/bin/env python3
"""Which channel row holds focus, read from a raw `adb exec-out screencap`.

Why this exists: `uiautomator dump` cannot be trusted for this. Hammering it a few dozen
times makes it fail with "UiAutomationService already registered", after which it keeps
returning STALE element trees -- a screen that had moved on reported "Looking for channels…"
forever, and the app looked hung when it was fine. A screenshot needs no accessibility
service at all.

Raw pixels rather than PNG: `screencap -p` needs a decoder, and PIL is not installed here.
`adb exec-out screencap` (no -p) emits three little-endian uint32 header fields and then
RGBA_8888 rows, which `struct` reads directly.

The focused row is drawn over `SecondaryVariant` and everything else over `Surface`, so the
mean channel value is ~200 for the focused row and ~30 for the rest. Sampling one pixel
inside each row band turns "did focus move on its own?" into a number.

Usage:
    adb exec-out screencap > /tmp/raw.bin
    python3 nuvioes/focus-probe.py /tmp/raw.bin

Calibrate ROW_TOP/ROW_PITCH at x=PROBE_X if the layout changes: they are measured from the
screen, not from the code, because the header height depends on whether the guide loaded.
"""

import struct
import sys

PROBE_X = 120
ROW_TOP = 95
ROW_PITCH = 68
ROW_COUNT = 9


def load(path: str) -> tuple[int, int, bytes]:
    with open(path, "rb") as handle:
        head = handle.read(12)
        width, height, _format = struct.unpack("<III", head)
        pixels = handle.read(width * height * 4)
    return width, height, pixels


def brightness(pixels: bytes, stride: int, x: int, y: int) -> int:
    offset = y * stride + x * 4
    r, g, b = pixels[offset], pixels[offset + 1], pixels[offset + 2]
    return (r + g + b) // 3


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    width, _height, pixels = load(sys.argv[1])
    stride = width * 4

    samples = [
        (index + 1, brightness(pixels, stride, PROBE_X, ROW_TOP + index * ROW_PITCH + 12))
        for index in range(ROW_COUNT)
    ]
    focused = max(samples, key=lambda sample: sample[1])
    rest = [value for _row, value in samples if value != focused[1]]

    print(f"focused row ~ {focused[0]} (brightness {focused[1]}), others {rest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
