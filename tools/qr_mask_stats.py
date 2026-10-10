"""Statistical check of mask selection: this port vs qrcode vs segno.

All three are forced into byte mode. A high disagreement rate points at a real defect in the
penalty implementation rather than a cosmetic tie-break.
"""
from __future__ import annotations

import random
import string
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / ".pylibs"))

import qrcode  # noqa: E402
import segno  # noqa: E402
from qrcode.constants import ERROR_CORRECT_L  # noqa: E402
from qrcode.util import QRData, create_data  # noqa: E402

import qr_port_check as mine  # noqa: E402

random.seed(20261010)


def payloads(n: int = 30):
    out = []
    for _ in range(n):
        length = random.choice([12, 20, 40, 60, 90, 114])
        out.append("".join(random.choice(string.ascii_letters + string.digits) for _ in range(length)))
    out.append("41194784288188343519SPAEMBAGdcymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYDAAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSivsCnXC")
    out.append("41211426242414189967SPAEMBALxymoBAQE1ATEyAwC+x2oAAAAziQO6OPpKoqEDAGYDAAAADGfTGqgtAaf9PCtTuAifP77Ia7aBR5WSSiu2TqkG")
    return out


def main() -> int:
    agree_qrcode = 0
    agree_segno = 0
    total = 0
    mismatches = []

    for text in payloads():
        data = text.encode("utf-8")

        # this port
        my_mods, my_mask = mine.encode(text, "L")
        my_version = (len(my_mods) - 17) // 4

        # qrcode library, forced to byte mode at the same version
        qr = qrcode.QRCode(version=my_version, error_correction=ERROR_CORRECT_L, box_size=1, border=0)
        qr.add_data(QRData(data, mode=None, check_data=False))
        try:
            qr.make(fit=False)
            q_mask = None
            # recover its chosen mask from the format info
            n = qr.modules_count
            bits = 0
            for i in range(15):
                if i < 6:
                    b = qr.modules[i][8]
                elif i < 8:
                    b = qr.modules[i + 1][8]
                else:
                    b = qr.modules[n - 15 + i][8]
                bits |= (1 if b else 0) << i
            for cand in range(32):
                rem = cand
                for _ in range(10):
                    rem = (rem << 1) ^ ((rem >> 9) * 0x537)
                if (((cand << 10) | rem) ^ 0x5412) == bits:
                    q_mask = cand & 7
                    break
        except Exception:  # noqa: BLE001
            q_mask = None

        # segno in byte mode
        try:
            sq = segno.make(data, error="l", micro=False, boost_error=False)
            s_mask = sq.mask if sq.version == my_version else None
        except Exception:  # noqa: BLE001
            s_mask = None

        total += 1
        if q_mask == my_mask:
            agree_qrcode += 1
        else:
            mismatches.append(("qrcode", len(text), my_version, my_mask, q_mask))
        if s_mask == my_mask:
            agree_segno += 1
        else:
            mismatches.append(("segno", len(text), my_version, my_mask, s_mask))

    print(f"用例数: {total}")
    print(f"与 qrcode 掩码一致: {agree_qrcode}/{total}")
    print(f"与 segno  掩码一致: {agree_segno}/{total}")
    print("\n不一致明细 (库, 长度, 版本, mine, theirs):")
    c = Counter(m[0] for m in mismatches)
    print("  按库统计:", dict(c))
    for m in mismatches[:15]:
        print("   ", m)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
