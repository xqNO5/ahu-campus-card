"""Sanity-check the downloaded debug APK."""
from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APK = ROOT / "reports" / "app-debug.apk"
MODEL_ASSET = "assets/captcha_compact.bin"
MODEL_SOURCE = ROOT / "android" / "app" / "src" / "main" / "assets" / "captcha_compact.bin"


def main() -> int:
    if not APK.is_file():
        print(f"no APK at {APK}; download the CI artifact first (tools/ci_artifacts.py)")
        return 1
    problems: list[str] = []
    print("size:", APK.stat().st_size, "bytes")

    with zipfile.ZipFile(APK) as z:
        names = z.namelist()
        print("entries:", len(names))

        for key in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            present = key in names
            print("  ", key, "OK" if present else "MISSING")
            if not present:
                problems.append(f"{key} missing from the APK")

        print("   dex files:", [n for n in names if n.endswith(".dex")])
        print(
            "   signature:",
            [n for n in names if n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC", ".SF"))],
        )

        # The binary manifest stores its strings as UTF-16LE, so probing for UTF-8 bytes can
        # never match -- that made this check pass vacuously before.
        blob = z.read("AndroidManifest.xml")
        for probe in ("cn.edu.ahu.cardqr", "MainActivity"):
            found = probe.encode("utf-16-le") in blob
            print(f"   contains {probe}: {found}")
            if not found:
                problems.append(f"binary manifest does not mention {probe}")

        # "The model is in the repo" is not "the model is in the APK": hash the packaged copy
        # against the source so a broken asset wiring cannot slip through.
        if MODEL_ASSET not in names:
            problems.append(f"{MODEL_ASSET} is missing from the APK")
        else:
            packaged = z.read(MODEL_ASSET)
            digest = hashlib.sha256(packaged).hexdigest()
            print(f"   {MODEL_ASSET}: {len(packaged)} bytes")
            print(f"      sha256 {digest}")
            if MODEL_SOURCE.is_file():
                source = MODEL_SOURCE.read_bytes()
                same = hashlib.sha256(source).hexdigest() == digest
                print(f"      matches source asset: {same}")
                if not same:
                    problems.append("packaged captcha model differs from the source asset")
            else:
                print("      source asset not present; skipped the byte comparison")

    for problem in problems:
        print("CHECK FAIL", problem)
    if not problems:
        print("CHECK OK  APK looks like this app, with the captcha model inside")
    print(f"SUMMARY FAILURES={len(problems)}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
