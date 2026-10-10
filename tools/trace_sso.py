"""Follow the ycard (berserker) SSO chain: WebVPN -> CAS -> synjones-auth token."""
from __future__ import annotations

import os

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

from ahu_client.webvpn import AhuWebVpnClient  # noqa: E402

YCARD = "https://ycard.ahu.edu.cn"
ADWMH = "https://adwmh.ahu.edu.cn"

ENTRY = (
    f"{YCARD}/berserker-auth/cas/login/neusoftCas"
    "?targetUrl=" + "https%3A%2F%2Fycard.ahu.edu.cn%2Fberserker-base%2Fredirect"
    "%3Ftype%3Durl%26url%3Dhttps%253A%252F%252Fycard.ahu.edu.cn%252Fplat"
)


def main() -> None:
    c = AhuWebVpnClient(USER, PASSWORD)
    c.login()
    print("webvpn ticket:", c.ticket)

    r = c.session.get(c.url_for(ENTRY), timeout=30, allow_redirects=False)
    print(f"\nGET {ENTRY[:80]}...")
    print(f"  -> HTTP {r.status_code}  location={r.headers.get('Location')}")
    body = r.content.decode("utf-8", errors="replace")
    zh = re.findall(r"[\u4e00-\u9fff]{2,}", body)
    print(f"  body {len(r.content)} bytes, 中文: {list(dict.fromkeys(zh))[:8]}")
    print(f"  head: {body[:300]!r}")

    loc = r.headers.get("Location")
    hops = 0
    while loc and hops < 12:
        hops += 1
        nxt = loc if loc.startswith("http") else f"https://{c.vpn_host}{loc}"
        # upstream Location may be an absolute upstream URL; tunnel it
        if "wvpn.ahu.edu.cn" not in nxt:
            nxt = c.url_for(nxt)
            # a tunnelled URL that itself needs tunnelling can appear; unwrap once
            m = re.search(r"/https/[0-9a-f]+/(https?://.+)$", nxt)
            if m:
                nxt = c.url_for(m.group(1))
        rr = c.session.get(nxt, timeout=30, allow_redirects=False)
        print(f"\n  hop{hops} -> HTTP {rr.status_code}")
        print(f"       {nxt[:120]}")
        print(f"       location={rr.headers.get('Location')}")
        for name, val in rr.headers.items():
            if name.lower() in ("set-cookie", "location"):
                print(f"       {name}: {val[:160]}")
        if "synjones-auth" in (rr.headers.get("Location") or "") or "synjones-auth" in rr.text[:2000]:
            print("       *** 发现 synjones-auth ***")
            break
        loc = rr.headers.get("Location")
        if not loc:
            txt = rr.content.decode("utf-8", errors="replace")
            zh2 = list(dict.fromkeys(re.findall(r"[\u4e00-\u9fff]{2,}", txt)))
            print(f"       body {len(rr.content)}B 中文={zh2[:6]}")
            print(f"       head={txt[:200]!r}")
            break


if __name__ == "__main__":
    main()
