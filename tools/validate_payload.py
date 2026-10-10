"""Validate a real campus-card QR payload end to end.

Takes the full payload (20-digit code + token), renders it with the verified encoder, and
decodes the result back, reporting the QR version / EC level / mask so it can be compared
with what the official page produced.
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

# EC level indicators as they appear in the format information.
EC_BITS = {1: "L", 0: "M", 3: "Q", 2: "H"}


def decode_format(modules) -> tuple[str, int]:
    """Read EC level and mask index back out of the format information."""
    n = len(modules)

    def get(i: int) -> int:
        if i < 6:
            return int(modules[i][8])
        if i < 8:
            return int(modules[i + 1][8])
        return int(modules[n - 15 + i][8])

    bits = sum(get(i) << i for i in range(15))
    data = 0
    for cand in range(32):
        rem = cand
        for _ in range(10):
            rem = (rem << 1) ^ ((rem >> 9) * 0x537)
        if ((cand << 10) | rem) ^ 0x5412 == bits:
            data = cand
            break
    return EC_BITS.get((data >> 3) & 3, "?"), data & 7


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
    payload = sys.argv[1] if len(sys.argv) > 1 else ""
    if not payload:
        print("用法: python tools/validate_payload.py '<完整的二维码内容>'")
        return 2

    digits = 0
    while digits < len(payload) and payload[digits].isdigit():
        digits += 1
    print(f"载荷长度      : {len(payload)}")
    print(f"数字前缀      : {digits} 位  ({payload[:digits]})")
    print(f"token 段      : {len(payload) - digits} 字符")

    modules, mask = mine.encode(payload, "L")
    version = (len(modules) - 17) // 4
    ec, fmt_mask = decode_format(modules)
    print(f"\n编码结果      : version={version} size={len(modules)}x{len(modules)}")
    print(f"格式信息      : 纠错等级={ec}  掩码={fmt_mask}  (编码器自选掩码={mask})")

    img = to_image(modules)
    ctx = cv2.QRCodeDetector()
    decoded, _, _ = ctx.detectAndDecode(img)
    ok = decoded == payload
    print(f"\n解码回读      : {'OK' if ok else 'FAIL'}")
    if not ok:
        print(f"  期望长度 {len(payload)}，得到 {len(decoded)}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
