"""Report the status of the repository's GitHub Actions runs."""
from __future__ import annotations

import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gh_api import REPO, headers  # noqa: E402


def main() -> int:
    h = headers()
    r = requests.get(f"https://api.github.com/repos/{REPO}/actions/runs", headers=h, timeout=30)
    print("runs API:", r.status_code)
    if r.status_code != 200:
        print(r.text[:300])
        return 1

    data = r.json()
    runs = data.get("workflow_runs", [])
    print("total_count:", data.get("total_count"))
    for run in runs[:3]:
        print(f"#{run['run_number']} {run['name']} [{run['head_sha'][:7]}]")
        print(f"   status={run['status']} conclusion={run['conclusion']}")
        print(f"   {run['html_url']}")
        jr = requests.get(run["jobs_url"], headers=h, timeout=30)
        if jr.status_code == 200:
            for job in jr.json().get("jobs", []):
                print(f"   job {job['name']}: {job['status']}/{job['conclusion']}")
                for step in job.get("steps", []):
                    state = step.get("conclusion") or step.get("status")
                    flag = "  " if state == "success" else "!!"
                    print(f"      {flag} {step['name']}: {state}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
