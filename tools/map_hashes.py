"""Map each /https/<hash>/ segment to the upstream host the VPN reports for it."""
from __future__ import annotations

import os

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient  # noqa: E402

BASE = "https://wvpn.ahu.edu.cn/https"

URLS = {
    "OAuth authorize": (
        f"{BASE}/77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6"
        "/connect/oauth2/authorize?appid=wxb0cc6c5fccbaf1d0"
        "&redirect_uri=https://adwmh.ahu.edu.cn//index/wxindex/login"
        "&response_type=code&scope=snsapi_userinfo&state=2022"
    ),
    "wxindex/login": (
        f"{BASE}/77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6"
        "/index/wxindex/login"
    ),
    "shouyeUser": (
        f"{BASE}/77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b/plat/shouyeUser"
    ),
    "tp_up": f"{BASE}/77726476706e69737468656265737421fff944d226387d1e7b0c9ce29b5b/tp_up/",
    "ymt index": (
        f"{BASE}/77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6/index/index"
    ),
}

c = AhuWebVpnClient(USER, PASSWORD)
c.login()

for name, url in URLS.items():
    r = c.session.get(url, timeout=30)
    host = re.search(r'__vpn_app_hostname_data\s*=\s*"([^"]+)"', r.text)
    title = re.search(r"<title>(.*?)</title>", r.text, re.S)
    assets = re.findall(r'(?:src|href)="(https://[^"]+)"', r.text)
    print(f"{name:18} status={r.status_code} len={len(r.text):>6} app_hash={host.group(1) if host else None}")
    print(f"{'':18} title={(title.group(1).strip()[:50] if title else None)!r}")
    print(f"{'':18} asset0={(assets[0][:100] if assets else None)}")
    print()
