"""Exploratory: drive the WebVPN CAS login and report what comes back.

Run: python tools/try_login.py
"""
from __future__ import annotations

import os

import re
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "ahu_client"))
from cas_des import str_enc  # noqa: E402

USER = os.environ.get("AHU_" + "USER", "")
PASSWORD = os.environ.get("AHU_" + "PASSWORD", "")

UA_DESKTOP = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

VPN_ROOT = "https://wvpn.ahu.edu.cn"

s = requests.Session()
s.headers.update(
    {
        "User-Agent": UA_DESKTOP,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    }
)

print("== 1. GET /login ==")
r = s.get(f"{VPN_ROOT}/login", timeout=30, allow_redirects=True)
print("status:", r.status_code)
print("url   :", r.url)
print("cookies:", s.cookies.get_dict())

html = r.text
form = re.search(r'<form id="loginForm" action="([^"]+)"', html)
if not form:
    print("!! loginForm not found; saving page")
    Path("debug_login.html").write_text(html, encoding="utf-8")
    sys.exit(1)

action = form.group(1)
print("form action:", action)

hidden = dict(re.findall(r'<input type="hidden"[^>]*name="([^"]+)"[^>]*value="([^"]*)"', html))
hidden2 = dict(re.findall(r'<input[^>]*name="([^"]+)"[^>]*value="([^"]*)"[^>]*type="hidden"', html))
hidden.update(hidden2)
print("hidden fields:", hidden)

lt = hidden.get("lt")
if not lt:
    print("!! no lt token")
    sys.exit(1)

base = re.match(r"(https://[^/]+/https/[0-9a-f]+)", r.url)
base = base.group(1) if base else VPN_ROOT
print("cas base:", base)

rsa = str_enc(USER + PASSWORD + lt, "1", "2", "3")
print("rsa:", rsa)

print()
print("== 2. POST /cas/device ==")
dev = s.post(
    f"{base}/cas/device",
    data={"ul": len(USER), "pl": len(PASSWORD), "rsa": rsa, "method": "login"},
    headers={
        "X-Requested-With": "XMLHttpRequest",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "Referer": r.url,
        "Origin": VPN_ROOT,
    },
    timeout=30,
)
print("status:", dev.status_code)
print("body  :", dev.text[:500])

print()
print("== 3. POST loginForm ==")
payload = dict(hidden)
payload.update({"un": USER, "pd": PASSWORD, "ul": str(len(USER)), "pl": str(len(PASSWORD)), "rsa": rsa, "lt": lt})
if "_eventId" not in payload:
    payload["_eventId"] = "submit"
if "execution" not in payload:
    payload["execution"] = "e1s1"

post = s.post(
    VPN_ROOT + action,
    data=payload,
    headers={"Referer": r.url, "Origin": VPN_ROOT, "Content-Type": "application/x-www-form-urlencoded"},
    timeout=30,
    allow_redirects=False,
)
print("status:", post.status_code)
print("location:", post.headers.get("Location"))
print("set-cookie:", post.headers.get("Set-Cookie"))
print("cookies:", s.cookies.get_dict())
body = post.text
print("body len:", len(body))
if body:
    Path("debug_login_post.html").write_text(body, encoding="utf-8")
    errs = re.findall(r'id="errormsg"[^>]*>([^<]*)<', body)
    print("errormsg:", errs)
    print("body head:", body[:600].replace("\n", " "))
