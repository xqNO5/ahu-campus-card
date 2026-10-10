"""Enumerate every API endpoint the /www/ SPA calls, to look for a captcha-free mint path."""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
text = (ROOT / "bundles" / "ymt_app.028dc21128f22ca574f6.js").read_text(
    encoding="utf-8", errors="replace"
)
OUT = ROOT / "reports" / "ymt_endpoints.txt"

# http.get("/x") / http.post("/x") / $.get / $.post / axios url:"/x"
patterns = [
    r'http\.(?:get|post|put|delete)\(\s*["\']([^"\']+)["\']',
    r'\$\.(?:get|post|ajax)\(\s*["\']([^"\']+)["\']',
    r'url\s*:\s*["\']([^"\']+)["\']',
    r'["\'](/(?:xzxcard|commapp|appvisited|user|index|remind|api|card|ecard|ykt)[^"\']*)["\']',
]

found: dict[str, int] = {}
for p in patterns:
    for m in re.finditer(p, text):
        url = m.group(1)
        found[url] = found.get(url, 0) + 1

lines = ["=== 全部接口（按路径） ==="]
for url in sorted(found, key=lambda u: (not u.startswith("/xzxcard"), u)):
    lines.append(f"  {found[url]:>3}x  {url}")

# Also: anything mentioning token / sign / auth in a request context
lines.append("")
lines.append("=== token / sign / auth 相关片段 ===")
for pat in (r"token", r"sign", r"auth", r"openid", r"wechat"):
    hits = list(re.finditer(pat, text, re.I))
    lines.append(f"--- {pat}: {len(hits)}")
    for m in hits[:6]:
        s, e = max(0, m.start() - 200), min(len(text), m.end() + 250)
        snippet = text[s:e].replace("\n", " ")
        if "/" in snippet and ("http" in snippet or "get(" in snippet or "post(" in snippet):
            lines.append("   " + snippet)

OUT.write_text("\n".join(lines), encoding="utf-8")
print(f"wrote {OUT}")
print("\n".join(lines[:50]))
