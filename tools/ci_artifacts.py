"""List the artifacts produced by the latest successful CI run."""
from __future__ import annotations

import os
import sys
from pathlib import Path

import requests

token = Path(os.environ["TEMP"], "gh_token.txt").read_text().strip()
headers = {
    "Authorization": f"Bearer {token}",
    "Accept": "application/vnd.github+json",
}
REPO = "xqNO5/ahu-campus-card"


def main() -> int:
    r = requests.get(f"https://api.github.com/repos/{REPO}/actions/runs", headers=headers, timeout=30)
    runs = r.json()["workflow_runs"]
    for run in runs[:3]:
        print(f"#{run['run_number']} {run['status']}/{run['conclusion']}  {run['html_url']}")
        if run["conclusion"] != "success":
            continue
        ar = requests.get(run["artifacts_url"], headers=headers, timeout=30)
        for art in ar.json().get("artifacts", []):
            mb = art["size_in_bytes"] / 1024 / 1024
            print(f"    artifact: {art['name']}  {mb:.2f} MB  expired={art['expired']}")
            print(f"      download API: {art['archive_download_url']}")
        break
    return 0


if __name__ == "__main__":
    sys.exit(main())
