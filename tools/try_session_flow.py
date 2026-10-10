"""Run the flow the userscript uses: POST /user/session, captcha, POST /user/login, then
GET /xzxcard/qrcode.

The session bootstrap was the missing step in the earlier probes; without it every
/xzxcard call redirects to /index/tologin regardless of headers.
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
OUT = ROOT / "reports" / "session_flow.json"
CAPTCHA = ROOT / "reports" / "captcha.png"

JSON_HEADERS = {
    "x-requested-with": "XMLHttpRequest",
    "Accept": "application/json, text/plain, */*",
}


def main() -> None:
    c = get_session()
    log: dict = {}

    def show(label: str, r) -> str:
        body = r.content.decode("utf-8", errors="replace")
        print(f"{label:34} HTTP {r.status_code} len={len(r.content)} ct={r.headers.get('Content-Type')}")
        print(f"   set-cookie: {r.headers.get('Set-Cookie')}")
        # Only echo textual bodies; the captcha is a binary image.
        if r.headers.get("Content-Type", "").startswith("application/json") or body.lstrip().startswith(("{", "<")):
            safe = body[:200].encode("ascii", "backslashreplace").decode("ascii")
            print(f"   body: {safe!r}")
        log[label] = {
            "status": r.status_code,
            "location": r.headers.get("Location"),
            "set_cookie": r.headers.get("Set-Cookie"),
            "body": body[:600],
        }
        return body

    print("=== 1) POST /user/session ===")
    show("POST /user/session", c.session.post(c.url_for(f"{ADWWH}/user/session"), headers=JSON_HEADERS, timeout=30, allow_redirects=False))
    print("cookies:", c.session.cookies.get_dict())

    print("\n=== 2) GET /remind/authcode ===")
    r = c.session.get(c.url_for(f"{ADWWH}//remind/authcode"), headers=JSON_HEADERS, timeout=30, allow_redirects=False)
    show("GET /remind/authcode", r)
    if len(r.content) > 300:
        CAPTCHA.write_bytes(r.content)
        print("   saved", CAPTCHA)

    print("\n=== 3) GET /xzxcard/qrcode （session 之后是否放行） ===")
    show("GET /xzxcard/qrcode", c.session.get(c.url_for(f"{ADWWH}/xzxcard/qrcode"), headers=JSON_HEADERS, timeout=30, allow_redirects=False))

    print("\n=== 4) GET /xzxcard/yue ===")
    show("GET /xzxcard/yue", c.session.get(c.url_for(f"{ADWWH}/xzxcard/yue"), headers=JSON_HEADERS, timeout=30, allow_redirects=False))

    print("\n=== 5) POST /user/login 用假验证码（看是否只卡验证码） ===")
    r = c.session.post(
        c.url_for(f"{ADWWH}/user/login"),
        data={"username": "probe", "pwd": "probe", "flag": "0", "imgcode": "0000"},
        headers={**JSON_HEADERS, "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
        timeout=30,
        allow_redirects=False,
    )
    show("POST /user/login (fake)", r)

    OUT.write_text(json.dumps(log, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nwrote", OUT)


if __name__ == "__main__":
    main()
