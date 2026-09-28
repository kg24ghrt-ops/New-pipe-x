#!/usr/bin/env python3
"""Check whether newer releases exist for the two extraction backends.

Backends (see ``config/dependencies.toml``):

* NewPipeExtractor -- lightweight / native Android YouTube path.
* yt-dlp           -- Python path used through Chaquopy.

The script compares every pin against its upstream release feed and prints a
per-backend report.  It never writes to the repository; use
``tools/update-dependencies.py`` to apply a bump.

Usage
-----
    ./tools/check-dependencies.py                 # report, exit 0
    ./tools/check-dependencies.py --json          # machine readable report
    ./tools/check-dependencies.py --fail-outdated # CI gate: exit 1 on drift
    ./tools/check-dependencies.py --only yt_dlp   # check one backend only

Exit codes: 0 = everything up to date (or checks disabled with --no-fetch),
1 = an update is available while --fail-outdated was requested,
2 = at least one upstream could not be queried.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from depcommon import (  # noqa: E402
    DEPENDENCY_ORDER,
    bold,
    die,
    dim,
    fetch_latest,
    green,
    load_dependencies,
    red,
    version_sort_key,
    yellow,
)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Check for newer NewPipeExtractor / yt-dlp releases.",
        epilog="Apply a bump with ./tools/update-dependencies.py",
    )
    parser.add_argument("--json", action="store_true", help="emit a JSON report instead of text")
    parser.add_argument(
        "--only",
        metavar="NAME",
        help="check a single backend: newpipeextractor|newpipe-extractor|yt_dlp|yt-dlp",
    )
    parser.add_argument(
        "--include-prereleases",
        action="store_true",
        help="consider GitHub pre-releases as candidates too",
    )
    parser.add_argument(
        "--no-fetch",
        action="store_true",
        help="skip the network and only validate the local pins",
    )
    parser.add_argument(
        "--fail-outdated",
        action="store_true",
        help="exit non-zero when an update is available (useful as a CI gate)",
    )
    return parser


def select(deps: dict, only: "str | None") -> "list[str]":
    """Map the ``--only`` alias onto a pin-file section name."""
    if not only:
        return list(DEPENDENCY_ORDER)
    alias = {
        "yt-dlp": "yt_dlp",
        "newpipe-extractor": "newpipeextractor",
        "newpipeextractor": "newpipeextractor",
        "yt_dlp": "yt_dlp",
    }
    key = alias.get(only.lower(), only.lower())
    if key not in deps:
        die(f"unknown backend {only!r}; choose from: {', '.join(sorted(deps))}")
    return [key]


def main(argv: "list[str] | None" = None) -> int:
    args = build_parser().parse_args(argv)
    deps = load_dependencies()
    rows: "list[dict]" = []
    outdated = False
    errors = 0

    for key in select(deps, args.only):
        dep = deps[key]
        row = {
            "name": dep.label(),
            "key": key,
            "current": dep.current,
            "latest": None,
            "tag": None,
            "url": dep.get("changelog_url", ""),
            "source": "",
            "update_available": False,
            "error": None,
        }
        if not args.no_fetch:
            try:
                latest = fetch_latest(dep, include_prereleases=args.include_prereleases)
            except RuntimeError as exc:  # upstream unreachable / unexpected payload
                row["error"] = str(exc)
                errors += 1
            else:
                row.update(
                    latest=latest["version"],
                    tag=latest["tag"],
                    url=latest["url"],
                    source=latest["source"],
                )
                row["update_available"] = version_sort_key(latest["version"]) > version_sort_key(dep.current)
                if row["update_available"]:
                    outdated = True
        rows.append(row)

    if args.json:
        print(json.dumps({"up_to_date": not outdated, "outdated": outdated, "errors": errors,
                          "results": rows}, indent=2))
    else:
        print(bold("Extraction backend updates"))
        for row in rows:
            name = f"{row['name']:<16}"
            if row["error"]:
                print(f"  {red('!')} {bold(name)} pinned {row['current']} - {row['error']}")
                continue
            if row["latest"] is None:
                print(f"  {dim('=')} {bold(name)} pinned {row['current']} (network check skipped)")
                continue
            if row["update_available"]:
                marker = yellow("^")
                detail = f"{green(row['current'])} -> {yellow(row['latest'])}"
                suffix = f"  {dim(row['source'] + ' tag ' + row['tag'])}"
            else:
                marker = green("=")
                detail = f"{green(row['current'])} (up to date)"
                suffix = f"  {dim(row['source'])}"
            print(f"  {marker} {bold(name)} {detail}{suffix}")
            if row["update_available"]:
                print(f"      {dim(row['url'])}")
        summary = " ".join(
            filter(
                None,
                [
                    f"{sum(1 for r in rows if r['update_available'])} update(s) available" if outdated else "all backends up to date",
                    f"{errors} check(s) failed" if errors else "",
                ],
            )
        )
        print(dim(f"\n{summary}; pins live in config/dependencies.toml"))

    if errors:
        return 2
    if args.fail_outdated and outdated:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
