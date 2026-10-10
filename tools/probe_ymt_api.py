"""Probe the 一码通 backend for any endpoint that does not need a WeChat OAuth code."""
from __future__ import annotations

import os

import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient  # noqa: E402

H = (
    "https://wvpn.ahu.edu.cn/https/"
    "77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6"
)

TESTS = [
    ("GET", "/index/wxindex/getCard", None),
    ("POST", "/index/wxindex/getCard", {}),
    ("POST", "/index/wxindex/login", {}),
    ("GET", "/index/wxindex/getUserInfo", None),
    ("POST", "/index/wxindex/getUserInfo", {}),
    ("GET", "/index/wxindex/getCode", None),
    ("POST", "/index/card/qrcode", {}),
    ("GET", "/index/wxindex/getQrCode", None),
    ("GET", "/index/wxindex/pay", None),
    ("GET", "/index/wxindex/QRCode", None),
]

c = AhuWebVpnClient(USER, PASSWORD)
c.login()
print("ticket:", c.ticket, "\n")

for method, path, data in TESTS:
    try:
        r = c.session.request(method, H + path, data=data, timeout=20, allow_redirects=False)
        ctype = r.headers.get("Content-Type", "")[:34]
        body = r.text[:90].replace("\n", " ")
        print(f"{method:5} {path:32} {r.status_code} len={len(r.text):>6} ct={ctype:36} {body}")
    except Exception as exc:  # noqa: BLE001
        print(f"{method:5} {path:32} ERR {str(exc)[:60]}")
    time.sleep(0.2)
