"""Decisive test: can username + password + one image captcha mint a session that returns
the *full* QR payload (20 digits + token) without WeChat and without the WebVPN?

Reuses the JSESSIONID that produced the captcha just read by the user.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
BASE = "https://adwmh.ahu.edu.cn"
OUT = ROOT / "reports" / "login_flow_result.json"
SESSION_FILE = ROOT / "reports" / ".adwmh_session.json"

UA = (
    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
)
HEADERS = {
    "User-Agent": UA,
    "Accept": "application/json, text/plain, */*",
    "x-requested-with": "XMLHttpRequest",
}


def main() -> int:
    captcha = (sys.argv[1] if len(sys.argv) > 1 else "").strip()
    user = os.environ.get("AHU_USER", "")
    password = os.environ.get("AHU_PASS", "")
    if not (captcha and user and password):
        print("用法: AHU_USER=.. AHU_PASS=.. python tools/try_login_once.py <4位验证码>")
        return 2

    # reuse the session that produced the captcha the user read
    saved = SESSION_FILE.read_text(encoding="utf-8") if SESSION_FILE.exists() else ""
    jar = {}
    if saved.startswith("{"):
        jar = json.loads(saved)
    s = requests.Session()
    s.headers.update(HEADERS)
    if jar:
        s.cookies.update(jar)
        print("复用验证码会话:", jar)

    result: dict = {"captcha_session": jar}

    r = s.post(f"{BASE}/user/login",
               data={"username": user, "pwd": password, "flag": "0", "imgcode": captcha},
               headers={"Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"},
               timeout=30)
    print("POST /user/login ->", r.status_code, r.content.decode("utf-8", errors="replace")[:200])
    result["login"] = {"status": r.status_code, "body": r.content.decode("utf-8", errors="replace")[:400]}
    try:
        ok = r.json().get("code") == 10000
    except Exception:  # noqa: BLE001
        ok = False

    if not ok:
        print("\n登录未成功，未继续。")
        OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        return 1

    # the payoff: the full payload
    for label, path in (("qrcode", "/xzxcard/qrcode"), ("balance", "/xzxcard/yue")):
        rr = s.get(BASE + path, timeout=30, allow_redirects=False)
        body = rr.content.decode("utf-8", errors="replace")
        print(f"GET {path:18} -> HTTP {rr.status_code} len={len(rr.content)}")
        print("   body:", body[:260])
        result[label] = {"status": rr.status_code, "body": body[:800]}
        if label == "qrcode" and rr.status_code == 200:
            try:
                obj = rr.json().get("object") or ""
            except Exception:  # noqa: BLE001
                obj = ""
            if obj:
                digits = 0
                while digits < len(obj) and obj[digits].isdigit():
                    digits += 1
                result["payload"] = {"len": len(obj), "digits_prefix": digits}
                print(f"\n*** 拿到完整载荷: {len(obj)} 字符（数字前缀 {digits} 位）***")
                print("   ", obj)

    # persist the logged-in session for reuse
    SESSION_FILE.write_text(json.dumps(s.cookies.get_dict()), encoding="utf-8")
    result["logged_in_cookies"] = list(s.cookies.get_dict())
    OUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\nwrote", OUT)
    print("会话已保存，可直接复用（无需再登录）:", list(s.cookies.get_dict()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
