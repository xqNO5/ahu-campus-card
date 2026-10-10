"""Check the GitHub Actions status of the pushed repository."""
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
    print("runs API:", r.status_code)
    if r.status_code != 200:
        print(r.text[:300])
        return 1

    data = r.json()
    runs = data.get("workflow_runs", [])
    print("total_count:", data.get("total_count"))
    if not runs:
        print("(还没有任何运行记录)")
        return 0

    for run in runs[:5]:
        print(f"#{run['run_number']} {run['name']}")
        print(f"   status={run['status']} conclusion={run['conclusion']} event={run['event']}")
        print(f"   {run['html_url']}")
        jobs_url = run["jobs_url"]
        jr = requests.get(jobs_url, headers=headers, timeout=30)
        if jr.status_code == 200:
            for job in jr.json().get("jobs", []):
                print(f"   job: {job['name']} status={job['status']} conclusion={job['conclusion']}")
                for step in job.get("steps", []):
                    mark = step.get("conclusion") or step.get("status")
                    print(f"      - {step['name']}: {mark}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
