"""Systematically try to make /xzxcard/qrcode answer instead of redirecting.

The earlier probe sent no Referer and no app session; this enumerates plausible auth
combinations before concluding the endpoint is unusable.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

from session import session as get_session  # noqa: E402

ADWWH = "https://adwmh.ahu.edu.cn"
WWW = f"{ADWWH}/www/index.html"
OUT = ROOT / "reports" / "xzxcard_matrix.json"

UA_WECHAT = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 "
    "Mobile Safari/537.36 MMWEBID[0] MicroMessenger/8.0.47.2560(0x28002F35) WeChat/arm64"
)


def main() -> None:
    c = get_session()

    # First: does opening the app shell establish anything?
    shell = c.session.get(c.url_for(WWW), timeout=30, allow_redirects=True)
    print("shell:", shell.status_code, "cookies:", c.session.cookies.get_dict())

    base_headers = {
        "Accept": "application/json, text/plain, */*",
        "X-Requested-With": "XMLHttpRequest",
    }

    cases = [
        ("no referer", {}),
        ("referer www", {"Referer": WWW}),
        ("referer www + XRW", {"Referer": WWW, "X-Requested-With": "XMLHttpRequest"}),
        ("referer adwmh root", {"Referer": f"{ADWWH}/"}),
        ("referer index/index", {"Referer": f"{ADWWH}/index/index"}),
        ("wechat UA", {"Referer": WWW, "User-Agent": UA_WECHAT}),
        ("app agent header", {"Referer": WWW, "synjones-agent": "android"}),
        ("synAccessSource", {"Referer": WWW, "synAccessSource": "app"}),
    ]

    results = []
    for label, extra in cases:
        h = dict(base_headers)
        h.update(extra)
        r = c.session.get(c.url_for(f"{ADWWH}/xzxcard/qrcode"), headers=h, timeout=30, allow_redirects=False)
        body = r.content.decode("utf-8", errors="replace")
        results.append({"label": label, "status": r.status_code, "location": r.headers.get("Location"), "body": body[:400]})
        print(f"{label:26} HTTP {r.status_code} loc={(r.headers.get('Location') or '')[-40:]} body={body[:60]!r}")

    # Also try the sibling endpoints, and whether any of them is open
    for path in ("/xzxcard/yue", "/xzxcard/info", "/xzxcard/card", "/commapp/ids?ids=107"):
        r = c.session.get(c.url_for(ADWWH + path), headers={"Referer": WWW, **base_headers}, timeout=30, allow_redirects=False)
        print(f"{path:26} HTTP {r.status_code} loc={(r.headers.get('Location') or '')[-40:]}")
        results.append({"label": path, "status": r.status_code, "location": r.headers.get("Location")})

    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nwrote", OUT)


if __name__ == "__main__":
    main()
