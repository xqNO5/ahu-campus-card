"""Fetch the failing job's log and print the interesting parts."""
from __future__ import annotations

import re
import sys
from pathlib import Path

import requests

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gh_api import REPO, headers  # noqa: E402


def main() -> int:
    auth = headers()
    run_id = sys.argv[1] if len(sys.argv) > 1 else None
    if not run_id:
        r = requests.get(f"https://api.github.com/repos/{REPO}/actions/runs", headers=auth, timeout=30)
        run_id = r.json()["workflow_runs"][0]["id"]
    print("run_id:", run_id)

    jr = requests.get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/jobs", headers=auth, timeout=30)
    jobs = jr.json().get("jobs", [])
    if not jobs:
        print("no jobs")
        return 1
    job_id = jobs[0]["id"]
    print("job:", jobs[0]["name"], job_id)

    lr = requests.get(
        f"https://api.github.com/repos/{REPO}/actions/jobs/{job_id}/logs",
        headers=auth,
        timeout=60,
        allow_redirects=True,
    )
    print("logs status:", lr.status_code, "bytes:", len(lr.content))
    text = lr.content.decode("utf-8", errors="replace")
    out = Path(__file__).resolve().parent.parent / "reports" / "ci_log.txt"
    out.parent.mkdir(exist_ok=True)
    out.write_text(text, encoding="utf-8", errors="replace")
    print("saved:", out)

    # Surface failure-related lines: Gradle errors, test failures, exceptions.
    keys = re.compile(
        r"(FAILED|FAILURE|error:|Exception|Caused by|What went wrong|> Task .*FAILED|"
        r"e: |Test .* failed|assert|expected:|Execution failed|\d+ tests? completed)",
        re.I,
    )
    lines = text.splitlines()
    hits = [ln for ln in lines if keys.search(ln)]
    print(f"\n=== {len(hits)} 条可疑行（最多显示 80 条）===")
    for ln in hits[:80]:
        print(ln[:400])
    return 0


if __name__ == "__main__":
    sys.exit(main())
