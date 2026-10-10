"""Verify the DES port, the WebVPN/CAS login, and the tunnel.

Usage:
    python tools/verify_login.py                 # offline checks only
    python tools/verify_login.py --online        # also log in and fetch a portal page
"""
from __future__ import annotations

import os

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.cas_des import str_enc  # noqa: E402
from ahu_client.vpn_apps import discover_hashes  # noqa: E402

USER, PASSWORD = os.environ.get("AHU_USER", ""), os.environ.get("AHU_PASS", "")

PORTAL = (
    "https://wvpn.ahu.edu.cn/https/"
    "77726476706e69737468656265737421e9f4408e237e69586b468ca88d1b203b/plat/shouyeUser"
)


def check_des() -> bool:
    vectors = json.loads((ROOT / "tools" / "des_vectors.json").read_text(encoding="utf-8"))
    bad = [v for v in vectors if str_enc(v["input"]) != v["expected"]]
    print(f"[DES] {len(vectors) - len(bad)}/{len(vectors)} vectors match the served des.js")
    for v in bad[:3]:
        print("   FAIL", v["input"][:30], str_enc(v["input"])[:32], "!=", v["expected"][:32])
    return not bad


def check_login() -> bool:
    from ahu_client.webvpn import AhuWebVpnClient

    c = AhuWebVpnClient(USER, PASSWORD)
    c.login()
    print(f"[登录] 成功, wengine_vpn_ticket = {c.ticket}")

    r = c.session.get(PORTAL, timeout=30)
    ok = r.status_code == 200 and "__vpn_app_hostname_data" in r.text
    print(f"[隧道] GET /plat/shouyeUser -> HTTP {r.status_code}, {len(r.text)} 字节, 门户外壳={ok}")

    found = discover_hashes(r.text)
    print(f"[哈希] 页面中发现 {len(found)} 个 /https/<hash>/: {sorted(h[:16] + '...' for h in found)}")

    # a wrong/self-computed hash must be rejected -- proves the hash is not host-derived
    bad = c.session.get(
        "https://wvpn.ahu.edu.cn/https/6164776d682e6168752e6564752e636e/plat/shouyeUser",
        timeout=30,
        allow_redirects=True,
    )
    print(f"[反证] 用 hex(hostname) 当 hash -> 落到 {bad.url.split('/https/')[-1] if '/https/' in bad.url else bad.url}")
    return ok


def check_session_reuse() -> bool:
    """A saved cookie jar should keep working without re-authenticating."""
    import requests

    from ahu_client.webvpn import AhuWebVpnClient

    c = AhuWebVpnClient(USER, PASSWORD)
    c.login()
    jar = requests.utils.dict_from_cookiejar(c.session.cookies)
    print(f"[持久化] cookies = {list(jar)}")

    fresh = AhuWebVpnClient(USER, PASSWORD)
    fresh.session.cookies.update(jar)
    r = fresh.session.get(PORTAL, timeout=30)
    ok = r.status_code == 200 and "wengine-vpn/failed" not in r.url
    print(f"[持久化] 仅用已保存 cookie 访问门户 -> HTTP {r.status_code}, 可用={ok}")
    return ok


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--online", action="store_true", help="也执行联网检查（会真的登录）")
    args = ap.parse_args()

    results = {"des": check_des()}
    if args.online:
        results["login"] = check_login()
        results["session_reuse"] = check_session_reuse()

    print()
    for k, v in results.items():
        print(f"{'PASS' if v else 'FAIL'}  {k}")
    return 0 if all(results.values()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
