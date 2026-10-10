"""Fetch an arbitrary set of portal chunks by their webpack id."""
from __future__ import annotations

import os

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient, WECHAT_UA  # noqa: E402

PORTAL_HOST = "ycard.ahu.edu.cn"

WANTED_IDS = [
    "chunk-2d0b6aeb",   # codeBarPayinfo deps
    "chunk-db8ea976",
    "chunk-3f8a756a",
    "chunk-2d21d0c2",   # queryCard deps
    "chunk-eb9527fc",
    "chunk-73637e43",   # PaymentcodeBinded
    "chunk-aa56cdf4",   # personalData
    "chunk-1098608c",   # appviewBalance
    "chunk-2d0d3a23",
    "chunk-1f82eea0",
]


def main() -> None:
    src = (ROOT / "bundles" / "portal_plat_app.f8045d7c.js").read_text(encoding="utf-8", errors="replace")
    m = re.search(r'"js/"\+\(\{(?P<names>[^}]{100,})\}\[e\]\|\|e\)\+"\."\+\{(?P<hashes>[^}]{100,})\}\[e\]', src)
    names = {k.strip('"'): v for k, v in re.findall(r'([A-Za-z0-9_~\-]+|"[^"]+")\s*:\s*"([^"]+)"', m.group("names"))}
    hashes = {k.strip('"'): v for k, v in re.findall(r'([A-Za-z0-9_~\-]+|"[^"]+")\s*:\s*"([0-9a-f]{8})"', m.group("hashes"))}

    c = AhuWebVpnClient(USER, PASSWORD)
    c.login()
    c.session.headers["User-Agent"] = WECHAT_UA

    for name in WANTED_IDS:
        fid = names.get(name, name)
        h = hashes.get(fid)
        if not h:
            print(f"  ?? {name}: fid={fid} 无 hash")
            continue
        path = f"plat/js/{fid}.{h}.js"
        r = c.session.get(c.url_for(f"https://{PORTAL_HOST}/{path}"), timeout=60)
        fn = f"chunk_{fid}.{h}.js"
        (ROOT / "bundles" / fn).write_text(r.text, encoding="utf-8", errors="replace")
        ok = "javascript" in r.headers.get("Content-Type", "")
        print(f"  {'OK ' if ok else 'BAD'} {path:46} {r.status_code} {len(r.text):>8} -> {fn}")


if __name__ == "__main__":
    main()
