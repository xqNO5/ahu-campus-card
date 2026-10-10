"""Is adwmh directly reachable, and does a /user/login session persist in a cookie?"""
from __future__ import annotations

import socket
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reports" / "direct_reach.json"

UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)


def main() -> None:
    print("=== DNS ===")
    for h in ("adwmh.ahu.edu.cn", "ycard.ahu.edu.cn", "wvpn.ahu.edu.cn", "one.ahu.edu.cn"):
        try:
            print(f"  {h:22} {socket.gethostbyname(h)}")
        except Exception as exc:  # noqa: BLE001
            print(f"  {h:22} DNS 失败: {exc}")

    print("\n=== 直连 adwmh（不经 WebVPN）===")
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept": "application/json, text/plain, */*",
                      "x-requested-with": "XMLHttpRequest"})
    for method, path in (("POST", "/user/session"), ("GET", "/www/index.html"),
                         ("GET", "/xzxcard/qrcode"), ("GET", "/remind/authcode")):
        url = "https://adwmh.ahu.edu.cn" + path
        try:
            r = s.request(method, url, timeout=25, allow_redirects=False)
            ct = r.headers.get("Content-Type", "")
            print(f"  {method:5}{path:22} HTTP {r.status_code} len={len(r.content):>6} ct={ct[:30]}")
            if r.headers.get("Set-Cookie"):
                print(f"        Set-Cookie: {r.headers['Set-Cookie'][:120]}")
            if r.status_code == 200 and ct.startswith("application/json"):
                print(f"        body: {r.content[:150]!r}")
        except Exception as exc:  # noqa: BLE001
            print(f"  {method:5}{path:22} ERR {type(exc).__name__}: {str(exc)[:70]}")

    print("\n直连后的 cookies:", s.cookies.get_dict())


if __name__ == "__main__":
    main()
