#!/usr/bin/env python3
"""Static check for the Android resource references, without an Android SDK.

`gradle assembleDebug` is the real thing, but it only runs in CI, and the two mistakes that
break it most often are cheap to catch here:

  * a layout or Kotlin file references `@string/foo` / `R.id.bar` that no resource defines;
  * a resource is defined but the code asks for a different name.

Both show up as a resource-linking failure several minutes into CI. This script resolves the
names against `res/values/*.xml`, `res/layout/*.xml`, `res/drawable*`, `res/xml` and reports
every dangling reference instead.

Usage:  python tools/check_android_resources.py [android_dir]
"""

from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID = "http://schemas.android.com/apk/res/android"
# @string/foo, @+id/foo, @style/Theme.AhuCard (style names may contain dots), @android:... is
# the platform's and not ours to check.
REFERENCE = re.compile(r"@(\+?)(string|color|style|drawable|xml|id|layout|dimen)/([A-Za-z_][A-Za-z0-9_.]*)")
KOTLIN_R = re.compile(r"\bR\.(string|id|layout|drawable|color|style|dimen|xml)\.([A-Za-z_][A-Za-z0-9_]*)")


class Resources:
    """Names defined by the module's resources, keyed by resource type."""

    def __init__(self, module: Path) -> None:
        self.module = module
        self.defined: dict[str, set[str]] = {
            "string": set(),
            "color": set(),
            "style": set(),
            "drawable": set(),
            "xml": set(),
            "id": set(),
            "layout": set(),
            "dimen": set(),
        }
        self.defined_in: dict[tuple[str, str], str] = {}
        self._read_values()
        self._read_files()

    # ------------------------------------------------------------------ loading

    def _define(self, kind: str, name: str, where: str) -> None:
        self.defined.setdefault(kind, set()).add(name)
        self.defined_in.setdefault((kind, name), where)

    def _read_values(self) -> None:
        values = self.module / "res" / "values"
        if not values.is_dir():
            return
        for path in sorted(values.glob("*.xml")):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError as error:
                raise SystemExit(f"{path}: 不是合法 XML: {error}") from error
            for child in root:
                if not isinstance(child.tag, str):
                    continue
                name = child.attrib.get("name")
                if not name:
                    continue
                # <item type="id" name="x"/> declares an id, not a string.
                kind = child.attrib.get("type") if child.tag == "item" else child.tag
                self._define(kind or "item", name, path.name)

    def _read_files(self) -> None:
        res = self.module / "res"
        if not res.is_dir():
            return
        for folder, kind in (
            ("layout", "layout"),
            ("drawable", "drawable"),
            ("xml", "xml"),
            ("color", "color"),
            ("dimen", "dimen"),
        ):
            for path in res.glob(f"{folder}*/*"):
                if path.suffix in {".xml", ".png", ".jpg", ".webp", ".9.png"}:
                    stem = path.name
                    for suffix in (".9.png", ".png", ".jpg", ".webp", ".xml"):
                        if stem.endswith(suffix):
                            stem = stem[: -len(suffix)]
                            break
                    self._define(kind, stem, path.name)

    # ------------------------------------------------------------------ queries

    def missing(self, kind: str, name: str) -> bool:
        return name not in self.defined.get(kind, set())

    def where(self, kind: str, name: str) -> str:
        return self.defined_in.get((kind, name), "?")


def layout_ids(module: Path) -> dict[str, str]:
    """Every `@+id/name` declared in a layout, mapped to the file declaring it."""
    found: dict[str, str] = {}
    for path in (module / "res" / "layout").glob("*.xml"):
        text = path.read_text(encoding="utf-8")
        for _, kind, name in REFERENCE.findall(text):
            if kind == "id":
                found.setdefault(name, path.name)
    return found


def check(android_dir: Path) -> list[str]:
    app = android_dir / "app"
    module = app / "src" / "main"
    manifest = module / "AndroidManifest.xml"
    if not manifest.is_file():
        raise SystemExit(f"找不到 {manifest}；请传入 android 目录")

    resources = Resources(module)
    ids = layout_ids(module)
    for name, where in ids.items():
        resources._define("id", name, where)

    problems: list[str] = []

    # 1) XML: layouts + manifest
    xml_files = sorted((module / "res" / "layout").glob("*.xml")) + [manifest]
    for path in xml_files:
        text = path.read_text(encoding="utf-8")
        for plus, kind, name in REFERENCE.findall(text):
            if plus == "+":
                continue  # a declaration, not a reference
            if resources.missing(kind, name):
                problems.append(f"{path.name}: 引用了未定义的 @{kind}/{name}")

    # 2) Kotlin: R.<type>.<name>
    kotlin_files = sorted((app / "src").rglob("*.kt"))
    for path in kotlin_files:
        text = path.read_text(encoding="utf-8")
        for kind, name in KOTLIN_R.findall(text):
            if resources.missing(kind, name):
                problems.append(f"{path.name}: 引用了未定义的 R.{kind}.{name}")

    # 3) sanity: any Activity named in the manifest must exist as a Kotlin file
    for match in re.findall(r'android:name="\.([A-Za-z0-9_.]+)"', manifest.read_text(encoding="utf-8")):
        simple = match.split(".")[-1] + ".kt"
        if not any(path.name == simple for path in (module / "java").rglob("*.kt")):
            problems.append(f"AndroidManifest.xml: 找不到 Activity 类 {match}")

    report(module, resources, kotlin_files, problems)
    return problems


def report(module: Path, resources: Resources, kotlin_files: list[Path], problems: list[str]) -> None:
    counts = ", ".join(
        f"{kind}={len(names)}" for kind, names in sorted(resources.defined.items()) if names
    )
    print(f"resources: {counts}")
    print(f"layouts: {len(list((module / 'res' / 'layout').glob('*.xml')))}, "
          f"kotlin files: {len(kotlin_files)}")
    for problem in problems:
        print(f"CHECK FAIL {problem}")
    if not problems:
        print("CHECK OK  every resource reference resolves")
    print(f"SUMMARY FAILURES={len(problems)}")


def main(argv: list[str]) -> int:
    if argv:
        android_dir = Path(argv[0]).resolve()
    else:
        android_dir = Path(__file__).resolve().parent.parent / "android"
    problems = check(android_dir)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
