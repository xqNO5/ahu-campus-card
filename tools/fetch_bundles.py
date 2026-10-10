"""Mirror the portal SPA and the 一码通 SPA bundles for offline API analysis."""
from __future__ import annotations

import os

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient  # noqa: E402

USER, PASSWORD = os.environ.get("AHU_USER", ""), os.environ.get("AHU_PASS", "")
OUT = ROOT / "bundles"
OUT.mkdir(exist_ok=True)

PORTAL_HASH = "77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b"
YMT_HASH = "77726476706e69737468656265737421ffe7449269276d59660187e289446d36a8d6"
VPN = "https://wvpn.ahu.edu.cn/https"

c = AhuWebVpnClient(USER, PASSWORD)
c.login()
print("登录成功, ticket =", c.ticket, "\n")


def save(url: str, name: str) -> str:
    r = c.session.get(url, timeout=60)
    (OUT / name).write_text(r.text, encoding="utf-8", errors="replace")
    print(f"HTTP {r.status_code} {len(r.text):>8}  -> {name}")
    return r.text


def mirror_shell(shell_url: str, prefix: str, tag: str) -> None:
    html = save(shell_url, f"{tag}_shell.html")
    seen = set()
    for m in re.finditer(r'(?:src|href)="(/https/[^"?]+\.(?:js|css))', html):
        a = m.group(1)
        if a in seen:
            continue
        seen.add(a)
        save("https://wvpn.ahu.edu.cn" + a, f"{tag}_{prefix}_{Path(a).name}")


print("== 门户 SPA ==")
mirror_shell(f"{VPN}/{PORTAL_HASH}/plat/shouyeUser", "plat", "portal")

print("\n== 一码通 SPA ==")
mirror_shell(f"{VPN}/{YMT_HASH}/index/wxindex/login", "ymt", "ymt")

print("\n抓取完成，文件在 bundles/:")
for f in sorted(OUT.glob("*")):
    print(f"  {f.name:45} {f.stat().st_size:>9,} bytes")
