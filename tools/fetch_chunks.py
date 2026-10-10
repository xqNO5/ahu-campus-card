"""Extract webpack chunk hashes from the portal bundle and fetch the card/QR chunks."""
from __future__ import annotations

import os

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient, WECHAT_UA  # noqa: E402

PORTAL_HOST = "ycard.ahu.edu.cn"

# The bundle holds two maps: name -> friendly-id, then friendly-id -> content hash.
WANTED = [
    "campusCode", "cardcode", "cardcode~paycode", "paycode", "login", "login~signup",
    "chunk-9bdffc2c", "chunk-db8ea976", "profile", "user",
]


def chunk_hashes(src: str) -> dict[str, str]:
    out: dict[str, str] = {}
    for m in re.finditer(r'([A-Za-z0-9_~\-]+|"[^"]+")":\s*"([0-9a-f]{8})"', src):
        key = m.group(1).strip('"')
        out[key] = m.group(2)
    return out


def main() -> None:
    src = (ROOT / "bundles" / "portal_plat_app.f8045d7c.js").read_text(encoding="utf-8", errors="replace")

    # locate the two maps:  ...="js/"+({name:id,...}[e]||e)+"."+{id:hash,...}[e]+".js"
    m = re.search(
        r'"js/"\+\(\{(?P<names>[^}]{100,})\}\[e\]\|\|e\)\+"\."\+\{(?P<hashes>[^}]{100,})\}\[e\]',
        src,
    )
    if not m:
        raise SystemExit("未能定位 chunk 名称/hash 映射表")
    names = dict(re.findall(r'([A-Za-z0-9_~\-]+|"[^"]+")\s*:\s*"([^"]+)"', m.group("names")))
    hashes = dict(re.findall(r'([A-Za-z0-9_~\-]+|"[^"]+")\s*:\s*"([0-9a-f]{8})"', m.group("hashes")))
    names = {k.strip('"'): v for k, v in names.items()}
    hashes = {k.strip('"'): v for k, v in hashes.items()}
    print(f"解析出 {len(names)} 个名称映射, {len(hashes)} 条 hash")

    c = AhuWebVpnClient(USER, PASSWORD)
    c.login()
    c.session.headers["User-Agent"] = WECHAT_UA

    for name in WANTED:
        fid = names.get(name, name)
        h = hashes.get(fid)
        if not h:
            print(f"  ?? {name}: 没有 hash（fid={fid}）")
            continue
        path = f"plat/js/{fid}.{h}.js"
        r = c.session.get(c.url_for(f"https://{PORTAL_HOST}/{path}"), timeout=60)
        ctype = r.headers.get("Content-Type", "")
        is_js = "javascript" in ctype or r.text.lstrip().startswith(("!", "(function", "/*", "window", "var", "("))
        fn = f"chunk_{fid}.{h}.js"
        (ROOT / "bundles" / fn).write_text(r.text, encoding="utf-8", errors="replace")
        print(f"  {'OK ' if is_js else 'BAD'} {path:50} {r.status_code} {len(r.text):>8} ct={ctype[:24]:26} -> {fn}")


if __name__ == "__main__":
    main()
