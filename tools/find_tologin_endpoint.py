"""Find the tologin POST endpoint and the /www/ app's session setup."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tools"))

from session import session as get_session  # noqa: E402

ADWWH = "https://adwmh.ahu.edu.cn"
OUT = ROOT / "reports" / "tologin_endpoint.txt"

lines: list[str] = []

c = get_session()
r = c.session.get(c.url_for(f"{ADWWH}/index/tologin"), timeout=30, allow_redirects=False)
html = r.content.decode("utf-8", errors="replace")
lines.append(f"tologin html len={len(html)}")
lines.append("")

# every ajax url / form action / fetch on the page
for pat in (r"url\s*:\s*[\"']([^\"']+)", r"action\s*=\s*[\"']([^\"']+)", r"fetch\([\"']([^\"']+)", r"\$\.(?:post|get|ajax)\("):
    for m in re.finditer(pat, html):
        lines.append(f"[{pat}] {m.group(0)[:160]}")
        lines.append("")

# the full button handler
i = html.find("$.ajax")
if i >= 0:
    lines.append("===== $.ajax block =====")
    lines.append(html[max(0, i - 900): i + 1200])
    lines.append("")

# state / redirect handling
for pat in (r"window\.location[^;]{0,160}", r"tologin[^\"']{0,80}", r"success[^;]{0,200}"):
    for m in list(re.finditer(pat, html))[:6]:
        lines.append(f"[{pat}] {m.group(0)[:200]}")
lines.append("")

# /www/ bundle: how does it authenticate and what does it store?
bundle = (ROOT / "bundles" / "ymt_app.028dc21128f22ca574f6.js").read_text(encoding="utf-8", errors="replace")
lines.append("===== /www/ bundle: session & cookie handling =====")
for pat in (r"withCredentials[^,;]{0,60}", r"[Cc]ookie[^,;]{0,80}", r"Authorization[^,;]{0,80}",
            r"localStorage\.setItem\([^)]{0,60}", r"sessionStorage\.setItem\([^)]{0,60}",
            r'"/index/[a-zA-Z]+"', r'"/user/[a-zA-Z]+"', r"tologin"):
    hits = list(re.finditer(pat, bundle))
    lines.append(f"--- {pat}: {len(hits)}")
    for m in hits[:8]:
        s, e = max(0, m.start() - 160), min(len(bundle), m.end() + 160)
        lines.append("   " + bundle[s:e].replace("\n", " "))

OUT.write_text("\n".join(lines), encoding="utf-8")
print(f"wrote {OUT} ({len(lines)} lines)")
