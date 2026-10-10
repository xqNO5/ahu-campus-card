"""Sanity-check the downloaded debug APK."""
from __future__ import annotations

import zipfile
from pathlib import Path

p = Path(__file__).resolve().parent.parent / "reports" / "app-debug.apk"
print("size:", p.stat().st_size, "bytes")

with zipfile.ZipFile(p) as z:
    names = z.namelist()
    print("entries:", len(names))

    for key in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
        print("  ", key, "OK" if key in names else "MISSING")

    print("   dex files:", [n for n in names if n.endswith(".dex")])
    print(
        "   signature:",
        [n for n in names if n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC", ".SF"))],
    )
    print("   manifest present:", "AndroidManifest.xml" in names)

    # The binary manifest holds the label as UTF-16; look for it to confirm this is our app.
    blob = z.read("AndroidManifest.xml")
    for probe in ("cn.edu.ahu.cardqr", "MainActivity"):
        print(f"   contains {probe}:", probe.encode("utf-8") in blob)
