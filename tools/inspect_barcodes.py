"""Inspect the shape of every barcode the API hands back, for both codeType variants."""
from __future__ import annotations

import base64
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

from session import session as get_session  # noqa: E402

YCARD = "https://ycard.ahu.edu.cn"
ENTRY = (
    f"{YCARD}/berserker-auth/cas/login/neusoftCas"
    "?targetUrl=https%3A%2F%2Fycard.ahu.edu.cn%2Fberserker-base%2Fredirect"
    "%3Ftype%3Durl%26url%3Dhttps%253A%252F%252Fycard.ahu.edu.cn%252Fplat"
)
OUT = ROOT / "reports" / "barcode_shapes.json"


def synjones_token(c) -> str:
    from urllib.parse import parse_qs, urlsplit

    r = c.session.get(c.url_for(ENTRY), timeout=30, allow_redirects=False)
    for _ in range(12):
        loc = r.headers.get("Location")
        if not loc:
            break
        absolute = loc if loc.startswith("http") else "https://wvpn.ahu.edu.cn" + loc
        q = parse_qs(urlsplit(absolute).query)
        if "synjones-auth" in q:
            return q["synjones-auth"][0]
        nxt = absolute
        if "wvpn.ahu.edu.cn" not in nxt:
            nxt = c.url_for(nxt)
        r = c.session.get(nxt, timeout=30, allow_redirects=False)
    raise RuntimeError("no synjones token")


def describe(value: str) -> dict:
    info = {
        "len": len(value),
        "all_digits": value.isdigit(),
        "digits_prefix": len(re.match(r"^\d+", value).group(0)) if re.match(r"^\d+", value) else 0,
        "preview": value[:24] + ("..." if len(value) > 24 else ""),
        "head_bytes_b64": None,
    }
    # If the tail is base64url, try to decode the first block to see whether it is a JWT.
    tail = value[info["digits_prefix"]:]
    if tail and len(tail) % 4 != 1:
        padded = tail + "=" * (-len(tail) % 4)
        try:
            raw = base64.urlsafe_b64decode(padded[: int(len(padded) / 4) * 4])
            info["head_bytes_b64"] = raw[:16].hex()
            info["head_text_b64"] = raw[:24].decode("latin-1")
        except Exception:  # noqa: BLE001
            pass
    return info


def main() -> None:
    c = get_session()
    token = synjones_token(c)
    c.session.headers.update({"synjones-auth": f"bearer {token}", "synAccessSource": "app"})

    results: dict = {"token_len": len(token)}

    for label, params in (
        ("no codeType", {"account": "237986", "payacc": "237986", "paytype": "1"}),
        ("codeType=authCode", {"account": "237986", "payacc": "237986", "paytype": "1", "codeType": "authCode"}),
    ):
        q = "&".join(f"{k}={v}" for k, v in list(params.items()) + [("synAccessSource", "app")])
        r = c.session.get(
            c.url_for(f"{YCARD}/berserker-app/ykt/tsm/batchGetBarCodeGet?{q}"), timeout=30
        )
        try:
            data = r.json()
        except Exception:  # noqa: BLE001
            results[label] = {"error": r.content.decode("utf-8", errors="replace")[:300]}
            continue
        payload = data.get("data") or {}
        codes = payload.get("barcode") or []
        results[label] = {
            "api_code": data.get("code"),
            "errmsg": payload.get("errmsg"),
            "expires": payload.get("expires"),
            "count": len(codes),
            "shapes": [describe(x) for x in codes[:6]],
        }

    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", OUT)
    for label, info in results.items():
        if label == "token_len":
            continue
        print(f"\n=== {label} ===")
        print("  errmsg:", info.get("errmsg"), "count:", info.get("count"), "expires:", info.get("expires"))
        for sh in info.get("shapes", [])[:4]:
            print(f"   len={sh['len']} digits_only={sh['all_digits']} prefix={sh['digits_prefix']} {sh['preview']}")


if __name__ == "__main__":
    main()
