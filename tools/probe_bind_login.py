"""Probe the adwmh account-bind login: is the image captcha actually enforced?"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

from session import session as get_session  # noqa: E402

ADWWH = "https://adwmh.ahu.edu.cn"
OUT = ROOT / "reports" / "bind_probe.json"
CAPTCHA = ROOT / "reports" / "captcha.png"


def main() -> None:
    c = get_session()
    results: dict = {}

    # 1) the captcha image
    r = c.session.get(
        c.url_for(f"{ADWWH}/remind/authcode?t=1"),
        headers={"Referer": c.url_for(f"{ADWWH}/index/tologin")},
        timeout=30,
    )
    results["captcha"] = {
        "status": r.status_code,
        "content_type": r.headers.get("Content-Type"),
        "bytes": len(r.content),
        "sha256": hashlib.sha256(r.content).hexdigest()[:16],
    }
    print("captcha:", results["captcha"])
    if r.content[:2] in (b"\xff\xd8", b"\x89P") or len(r.content) > 500:
        CAPTCHA.write_bytes(r.content)
        print("saved", CAPTCHA)

    # 2) try the bind endpoint with a deliberately wrong captcha
    for label, code in (("wrong captcha", "0000"), ("empty captcha", "")):
        payload = {"username": "probe", "pwd": "probe", "imgcode": code, "flag": 1}
        rr = c.session.post(
            c.url_for(f"{ADWWH}/index/user/account/bind"),
            data=payload,
            headers={
                "Referer": c.url_for(f"{ADWWH}/index/tologin"),
                "X-Requested-With": "XMLHttpRequest",
            },
            timeout=30,
            allow_redirects=False,
        )
        body = rr.content.decode("utf-8", errors="replace")
        results[label] = {"status": rr.status_code, "body": body[:400]}
        print(f"{label:16} HTTP {rr.status_code} body={body[:160]!r}")

    # 3) is the API open after that?
    after = c.session.get(
        c.url_for(f"{ADWWH}/xzxcard/qrcode"),
        headers={"Referer": c.url_for(f"{ADWWH}/www/index.html")},
        timeout=30,
        allow_redirects=False,
    )
    results["xzxcard_after"] = {"status": after.status_code, "location": after.headers.get("Location")}
    print("xzxcard after bind attempt:", after.status_code, after.headers.get("Location"))

    OUT.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
    print("wrote", OUT)


if __name__ == "__main__":
    main()
