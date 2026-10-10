"""Emit reference QR matrices as JSON, for freezing into Kotlin unit-test fixtures."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT.parent / ".pylibs"))

import qr_port_check as mine  # noqa: E402

PAYLOAD = "40936904090512345678"
ECC_ENUM = {"L": "LOW", "M": "MEDIUM", "Q": "QUARTILE", "H": "HIGH"}


def main() -> None:
    cases = []
    for name, enum_name in ECC_ENUM.items():
        modules, _ = mine.encode(PAYLOAD, name)
        rows = [
            "".join("#" if modules[y][x] else "." for x in range(len(modules)))
            for y in range(len(modules))
        ]
        cases.append(
            {
                "ecc": enum_name,
                "version": (len(modules) - 17) // 4,
                "rows": rows,
            }
        )
    print(json.dumps({"payload": PAYLOAD, "cases": cases}))


if __name__ == "__main__":
    main()
