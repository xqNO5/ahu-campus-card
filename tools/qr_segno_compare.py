"""Compare the port against segno with both forced into byte mode and the same version."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / ".pylibs"))

import segno  # noqa: E402

import qr_port_check as mine  # noqa: E402

PAYLOADS = [
    "40936904090512345678",
    "41194784288188343519SPAEMBAGdcymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYDAAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSivsCnXC",
    "41211426242414189967SPAEMBALxZymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYDAAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSiu2TqkG",
]


def port_matrix(text: str, ecc: str, mask: int):
    version = mine.choose_version(len(text.encode("utf-8")), ecc)
    size = version * 4 + 17
    codewords = mine.build_codewords(text.encode("utf-8"), version, ecc)
    modules, is_function = mine.build_base(version)
    mine.draw_data(modules, is_function, codewords, size)
    mine.apply_mask(modules, is_function, mask, size)
    mine.draw_format(modules, ecc, mask, size)
    if version >= 7:
        mine.draw_version(modules, version, size)
    return modules, version


def segno_matrix(text: str, ecc: str, mask: int, version: int):
    """segno in byte mode, fixed version and mask, no quiet zone in q.matrix."""
    q = segno.make(
        text.encode("utf-8"),
        error=ecc,
        micro=False,
        boost_error=False,
        version=version,
        mask=mask,
    )
    seg = [[bool(b) for b in row] for row in q.matrix]
    n = version * 4 + 17
    if len(seg) != n:
        border = (len(seg) - n) // 2
        seg = [row[border:border + n] for row in seg[border:border + n]]
    return seg


def main() -> int:
    all_ok = True
    for text in PAYLOADS:
        _, version = port_matrix(text, "L", 0)
        n = version * 4 + 17
        print(f"\n载荷长度 {len(text)}  version={version} size={n}")
        for mask in range(8):
            mine_m, _ = port_matrix(text, "L", mask)
            ref = segno_matrix(text, "L", mask, version)
            diff = sum(1 for y in range(n) for x in range(n) if ref[y][x] != mine_m[y][x])
            flag = "OK " if diff == 0 else "DIFF"
            if diff:
                all_ok = False
            print(f"   {flag} mask {mask}: diff={diff}")
        print(f"   自选掩码: mine={mine.encode(text, 'L')[1]} segno={segno.make(text.encode(), error='l', micro=False, boost_error=False).mask}")
    print("\nRESULT:", "PASS 与 segno 逐模块一致" if all_ok else "仍有差异")
    return 0 if all_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
