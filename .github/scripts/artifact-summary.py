#!/usr/bin/env python3
"""Index this run's PMTiles artifacts into the job summary.

Every country uploads its archive as a run artifact, but GitHub's run
page buries them behind the artifacts sidebar — this writes a markdown
table (name, size, download link) to $GITHUB_STEP_SUMMARY so one glance
at the run's Summary tab shows what was built.

Env: GITHUB_REPOSITORY, GITHUB_RUN_ID, GH_TOKEN, GITHUB_STEP_SUMMARY.
"""
import json
import os
import urllib.request


def human(n):
    for unit in ("B", "KiB", "MiB", "GiB"):
        if n < 1024 or unit == "GiB":
            return f"{n:.0f} B" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024


def main():
    repo = os.environ["GITHUB_REPOSITORY"]
    run_id = os.environ["GITHUB_RUN_ID"]
    url = f"https://api.github.com/repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100"
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {os.environ['GH_TOKEN']}",
            "Accept": "application/vnd.github+json",
        },
    )
    with urllib.request.urlopen(req) as r:
        artifacts = json.load(r)["artifacts"]

    if not artifacts:
        block = "## PMTiles artifacts\n\nnone — every country job failed\n"
    else:
        rows = [
            f"| [{a['name']}](https://github.com/{repo}/actions/runs/"
            f"{run_id}/artifacts/{a['id']}) "
            f"| {human(a['size_in_bytes'])} |"
            for a in sorted(artifacts, key=lambda a: a["name"])
        ]
        block = (
            "## PMTiles artifacts\n\n"
            "| artifact | size |\n| --- | --- |\n" + "\n".join(rows) + "\n"
        )

    print(block)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(block)


if __name__ == "__main__":
    main()