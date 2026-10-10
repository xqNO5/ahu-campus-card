"""Shared GitHub API helper for the CI inspection scripts.

Resolves a token from, in order: $GH_TOKEN, a gh config dir in the workspace, the user's
gh config dir, or `gh auth token`. Keeps the CI scripts runnable without exporting anything.
"""
from __future__ import annotations

import os
import subprocess
from pathlib import Path

REPO = "xqNO5/ahu-campus-card"
_ROOT = Path(__file__).resolve().parent.parent
_GH = _ROOT / ".tools" / "gh.exe"


def _from_gh(config_dir: Path | None) -> str | None:
    if not _GH.exists():
        return None
    env = dict(os.environ)
    if config_dir:
        env["GH_CONFIG_DIR"] = str(config_dir)
    try:
        out = subprocess.run(
            [str(_GH), "auth", "token"],
            capture_output=True,
            text=True,
            timeout=30,
            env=env,
            stdin=subprocess.DEVNULL,
        )
    except Exception:  # noqa: BLE001
        return None
    token = out.stdout.strip()
    return token if token.startswith(("gho_", "ghp_", "github_pat_")) else None


def token() -> str:
    for key in ("GH_TOKEN", "GITHUB_TOKEN"):
        value = os.environ.get(key)
        if value:
            return value.strip()

    for candidate in (_ROOT / ".tools" / "ghconfig", Path(os.environ.get("APPDATA", "")) / "GitHub CLI"):
        found = _from_gh(candidate)
        if found:
            return found

    found = _from_gh(None)
    if found:
        return found

    raise SystemExit(
        "找不到 GitHub 凭据。请设置 GH_TOKEN，或用 gh auth login 重新授权后重试。"
    )


def headers() -> dict[str, str]:
    return {
        "Authorization": f"Bearer {token()}",
        "Accept": "application/vnd.github+json",
    }
