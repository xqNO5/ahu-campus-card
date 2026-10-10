"""Dump tampered-request experiments to a UTF-8 file (console encoding mangles CJK)."""
from __future__ import annotations

import os

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient, DESKTOP_UA, WECHAT_UA  # noqa: E402

OUT = ROOT / "reports"
OUT.mkdir(exist_ok=True)

LOGIN_PATH = "https://adwmh.ahu.edu.cn/index/wxindex/login"


def zh_hints(body: str) -> list[str]:
    return list(dict.fromkeys(x.strip() for x in re.findall(r"[\u4e00-\u9fff][\u4e00-\u9fff\s，。！、：；？…]{2,}", body)))


c = AhuWebVpnClient(USER, PASSWORD)
c.login()

results = []


def record(label: str, ua: str, upstream: str, extra_headers: dict | None = None) -> None:
    c.session.headers["User-Agent"] = ua
    headers = {"Referer": "https://adwmh.ahu.edu.cn/"}
    headers.update(extra_headers or {})
    r = c.session.get(c.url_for(upstream), timeout=30, allow_redirects=False, headers=headers)
    body = r.content.decode("utf-8", errors="replace")
    results.append(
        {
            "label": label,
            "status": r.status_code,
            "len": len(r.content),
            "location": r.headers.get("Location"),
            "set_cookie": r.headers.get("Set-Cookie"),
            "zh": zh_hints(body),
        }
    )


# Does the gateway accept the WebVPN/CAS identity instead of a WeChat code?
record("baseline desktop, no code", DESKTOP_UA, LOGIN_PATH)
record("wechat UA, no code", WECHAT_UA, LOGIN_PATH)
record("fake code", DESKTOP_UA, LOGIN_PATH + "?code=FAKE&state=2022")
record("WeChat-ish query params", WECHAT_UA, LOGIN_PATH + "?ticket=abc&openid=abc&token=abc")
record("referer=wechat", WECHAT_UA, LOGIN_PATH, {"Referer": "https://open.weixin.qq.com/"})

# Nearby endpoints that might not be WeChat-gated
for path in (
    "/",
    "/index",
    "/index/",
    "/index/index",
    "/wxindex/login",
    "/index/wxindex/index",
    "/index/wxindex/user",
    "/api",
    "/actuator/health",
    "/static/css/weui.min.css",
):
    upstream = f"https://adwmh.ahu.edu.cn{path}"
    c.session.headers["User-Agent"] = WECHAT_UA
    try:
        r = c.session.get(c.url_for(upstream), timeout=25, allow_redirects=False)
        results.append(
            {
                "label": f"probe {path}",
                "status": r.status_code,
                "len": len(r.content),
                "content_type": r.headers.get("Content-Type"),
                "location": r.headers.get("Location"),
                "zh": zh_hints(r.content.decode("utf-8", errors="replace")),
            }
        )
    except Exception as exc:  # noqa: BLE001
        results.append({"label": f"probe {path}", "error": str(exc)[:120]})

(OUT / "adwmh_gateway_probe.json").write_text(
    json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8"
)
print(f"wrote {OUT / 'adwmh_gateway_probe.json'} ({len(results)} records)")
