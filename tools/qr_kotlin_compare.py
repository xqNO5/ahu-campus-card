"""Compare the Kotlin encoder's QR matrices against the verified Python reference."""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / ".pylibs"))

import qr_port_check as mine  # noqa: E402

KT_OUT = ROOT.parent / "reports" / "kotlin_verify.txt"
PAYLOAD = "40936904090512345678"

ECC_NAMES = {"L": "L", "M": "M", "Q": "Q", "H": "H"}


def parse_kotlin(text: str):
    """Return {ecc: (version, [rows])} from the harness output."""
    out: dict[str, tuple[int, list[str]]] = {}
    current = None
    for line in text.splitlines():
        if line.startswith("QR ecc="):
            parts = dict(p.split("=") for p in line.split()[1:])
            current = parts["ecc"]
            out[current] = (int(parts["version"]), [])
        elif line.startswith("QRROW ") and current:
            out[current][1].append(line.split()[3])
    return out


def main() -> int:
    if not KT_OUT.exists():
        print(f"缺少 {KT_OUT}; 先运行 Kotlin 验证程序")
        return 2

    kt = parse_kotlin(KT_OUT.read_text(encoding="utf-8"))
    print(f"Kotlin 输出中解析到 {len(kt)} 个纠错级别\n")

    failures = 0
    for name, ecc in ECC_NAMES.items():
        if name not in kt:
            print(f"  ?? Kotlin 未输出 ecc={name}")
            failures += 1
            continue
        kt_version, kt_rows = kt[name]
        py_modules, _ = mine.encode(PAYLOAD, ecc)
        py_version = (len(py_modules) - 17) // 4
        py_rows = ["".join("#" if py_modules[y][x] else "." for x in range(len(py_modules))) for y in range(len(py_modules))]

        version_ok = kt_version == py_version
        rows_ok = kt_rows == py_rows
        diff = 0
        if len(kt_rows) == len(py_rows):
            for a, b in zip(kt_rows, py_rows):
                diff += sum(1 for ca, cb in zip(a, b) if ca != cb)
        else:
            diff = -1

        if version_ok and rows_ok:
            print(f"  OK   ecc={name} v{kt_version} 矩阵 {len(kt_rows)}x{len(kt_rows[0])} 逐模块一致")
        else:
            failures += 1
            print(
                f"  FAIL ecc={name} version kt={kt_version} py={py_version} "
                f"rows_match={rows_ok} diff={diff}"
            )

    print()
    if failures == 0:
        print("RESULT: PASS — Kotlin 与 Python 二维码矩阵完全一致")
    else:
        print(f"RESULT: {failures} 处不一致")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
