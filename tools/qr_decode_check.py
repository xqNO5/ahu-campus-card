"""Prove the ported QR encoder produces *decodable* codes.

The encoder is compared module-for-module against the reference library elsewhere; this
script closes the loop by rendering the port's output and decoding it with OpenCV, which
is what a turnstile/POS scanner effectively does.
"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / ".pylibs"))

import cv2  # noqa: E402
import numpy as np  # noqa: E402

import qr_port_check as mine  # noqa: E402

PAYLOAD = "40936904090512345678"

CASES = [
    "40936904090512345678",
    "0" * 20,
    "9" * 20,
    "12345678901234567890",
    "hello world",
    "https://ycard.ahu.edu.cn/plat/",
    "安大校园卡",
    "a" * 60,
    "x" * 150,
]


def to_image(modules, scale: int = 8, quiet: int = 4):
    n = len(modules)
    total = n + quiet * 2
    img = np.full((total * scale, total * scale), 255, dtype=np.uint8)
    for y in range(n):
        for x in range(n):
            if modules[y][x]:
                img[(y + quiet) * scale:(y + quiet + 1) * scale,
                    (x + quiet) * scale:(x + quiet + 1) * scale] = 0
    return img


def main() -> int:
    detector = cv2.QRCodeDetector()
    failures = 0
    print(f"{'ECC':4} {'版本':4} {'长度':5} 解码结果")
    for ecc in ("L", "M", "Q", "H"):
        for text in CASES:
            modules, mask = mine.encode(text, ecc)
            version = (len(modules) - 17) // 4
            img = to_image(modules)
            decoded, points, _ = detector.detectAndDecode(img)
            ok = decoded == text
            if not ok:
                failures += 1
            status = "OK " if ok else "FAIL"
            shown = decoded if len(decoded) <= 28 else decoded[:28] + "…"
            src = text if len(text) <= 28 else text[:28] + "…"
            print(f"{status} {ecc:3} v{version:<3} {len(text):>4}  期望={src!r} 得到={shown!r}")

    print()
    print("RESULT: 全部可解码" if failures == 0 else f"RESULT: {failures} 个用例解码失败")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
