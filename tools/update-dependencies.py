#!/usr/bin/env python3
"""Bump the NewPipeExtractor / yt-dlp pins to newer upstream releases.

This is the companion of ``tools/check-dependencies.py``: it queries the same
upstream feeds (GitHub for NewPipeExtractor, PyPI for yt-dlp) and rewrites the
version in every place the project declares it:

* ``config/dependencies.toml``            -- ``current`` field (source of truth)
* ``gradle.properties``                   -- ``newpipeExtractorVersion`` / ``ytDlpVersion``
* ``app/python-requirements.txt``         -- ``yt-dlp==<version>`` (Chaquopy backend)

Safety gate ("only automatically drop in a version that needs no manual API
changes"): before touching any file, each candidate release is classified by
its version number:

* NewPipeExtractor (semver): 0.x.y minor bumps (0.26.5 -> 0.27.0) are treated as
  compatible; anything with a new major version (0.x -> 1.0, or 1.x -> 2.x) is
  skipped unless ``--allow-major`` is given.
* yt-dlp (CalVer): releases only fix/extend extractors and keep the CLI/API
  stable, so every newer release is accepted.

Skipped candidates are reported on stderr (and in ``--json`` output under
``skipped``) so CI can surface them without silently upgrading a breaking
release.

Usage
-----
    ./tools/update-dependencies.py                    # dry run: show the bump plan
    ./tools/update-dependencies.py --write            # apply it
    ./tools/update-dependencies.py --only yt-dlp --write
    ./tools/update-dependencies.py --json             # machine readable plan
    ./tools/update-dependencies.py --target NewPipeExtractor=0.26.5   # pin exactly

Exit codes: 0 = nothing to do or bump applied, 1 = --write refused
(major update blocked), 2 = an upstream could not be queried.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from depcommon import (  # noqa: E402
    ApplyResult,
    DEPENDENCY_ORDER,
    REPO_ROOT,
    bold,
    die,
    dim,
    fetch_latest,
    green,
    load_dependencies,
    red,
    update_gradle_property,
    update_pin_file,
    update_requirements,
    version_sort_key,
    yellow,
)


# --------------------------------------------------------------------------- #
# candidate classification
# --------------------------------------------------------------------------- #
def is_safe_bump(dep, current: str, candidate: str) -> "tuple[bool, str]":
    """Decide whether ``candidate`` may replace ``current`` without manual API changes."""
    scheme = str(dep.get("version_scheme", ""))
    cur = version_sort_key(current)
    new = version_sort_key(candidate)
    if new <= cur:
        return True, "not newer"  # caller filters this out anyway

    if scheme.startswith("calver"):
        # yt-dlp keeps its Python/CLI interface stable across dated releases;
        # new versions almost exclusively add/fix extractors.
        return True, "calver: extractor fixes only"

    # semver-ish (NewPipeExtractor). While upstream is 0.x every minor bump may
    # already change APIs, but that is the rhythm NewPipe consumers follow;
    # crossing a major boundary is the hard stop.
    cur_major, new_major = cur[0], new[0]
    if cur_major == 0 and new_major == 0:
        return True, "0.x minor bump"
    if new_major > cur_major:
        return False, f"major version bump {cur_major} -> {new_major}"
    return True, "minor bump"


# --------------------------------------------------------------------------- #
# planning
# --------------------------------------------------------------------------- #
def build_plan(deps: dict, targets: "dict[str, str]", allow_major: bool,
               keys: "tuple[str, ...] | list[str]" = DEPENDENCY_ORDER) -> "tuple[list[dict], int]":
    """Return one row per managed backend plus the count of failed upstream checks."""
    plan: "list[dict]" = []
    errors = 0
    for key in keys:
        dep = deps[key]
        row = {
            "key": key,
            "name": dep.label(),
            "current": dep.current,
            "latest": None,
            "tag": None,
            "url": dep.get("changelog_url", ""),
            "target": None,
            "bump": False,
            "blocked": False,
            "reason": "",
            "error": None,
        }
        forced = targets.get(key) or targets.get(dep.label().lower())
        if forced is not None:
            row["latest"] = forced
            row["url"] = dep.get("changelog_url", "")
            safe, why = is_safe_bump(dep, dep.current, forced)
            if version_sort_key(forced) <= version_sort_key(dep.current) and forced == dep.current:
                row["reason"] = "already pinned to this version"
                plan.append(row)
                continue
            if safe or allow_major:
                row["target"] = forced
                row["bump"] = True
                row["reason"] = "explicit --target" + ("" if safe else f" ({why}, allowed via --allow-major)")
            else:
                # An explicit --target never overrides the compatibility gate:
                # it still needs --allow-major once a human reviewed the changelog.
                row["blocked"] = True
                row["reason"] = f"{why} requested via --target"
                print(
                    red(f"refusing {dep.label()} {dep.current} -> {forced}: ")
                    + f"{why}; rerun with --allow-major after reviewing the changelog",
                    file=sys.stderr,
                )
            plan.append(row)
            continue

        try:
            latest = fetch_latest(dep)
        except RuntimeError as exc:
            row["error"] = str(exc)
            errors += 1
            plan.append(row)
            continue

        row.update(latest=latest["version"], tag=latest["tag"], url=latest["url"])
        if version_sort_key(latest["version"]) <= version_sort_key(dep.current):
            row["reason"] = "up to date"
            plan.append(row)
            continue

        safe, why = is_safe_bump(dep, dep.current, latest["version"])
        row["reason"] = why
        row["bump"] = safe
        row["blocked"] = not safe
        if safe:
            row["target"] = latest["version"]
        elif allow_major:
            row["blocked"] = False
            row["bump"] = True
            row["target"] = latest["version"]
            row["reason"] = why + " (allowed via --allow-major)"
        else:
            print(
                red(f"skipping {dep.label()} {dep.current} -> {latest['version']}: ")
                + f"{why}; rerun with --allow-major after reviewing the changelog",
                file=sys.stderr,
            )
        plan.append(row)
    return plan, errors


# --------------------------------------------------------------------------- #
# applying
# --------------------------------------------------------------------------- #
def gradle_property_names(deps: dict) -> "dict[str, str]":
    """Map every managed backend onto the gradle property that mirrors it.

    ``config/dependencies.toml`` only records a ``gradle_property`` for
    NewPipeExtractor, but both backends are mirrored in ``gradle.properties``
    (``ytDlpVersion`` documents the Chaquopy pin next to it), so any declared
    ``*Version`` property is matched against its section by name.
    """
    names: "dict[str, str]" = {}
    path = os.path.join(REPO_ROOT, "gradle.properties")
    declared: "list[str]" = []
    if os.path.exists(path):
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                match = re.match(r"^\s*([A-Za-z0-9_]+)\s*=", line)
                if match:
                    declared.append(match.group(1))

    def normalise(text: str) -> str:
        return re.sub(r"[^a-z0-9]", "", text.lower())

    for key, dep in deps.items():
        explicit = dep.get("gradle_property")
        if explicit and explicit in declared:
            names[key] = explicit
            continue
        wanted = {normalise(dep.label()), normalise(key)}
        for prop in declared:
            stem = normalise(prop[: -len("Version")] if prop.endswith("Version") else prop)
            if stem in wanted:
                names[key] = prop
                break
    return names


def apply_plan(deps: dict, plan: "list[dict]", result: ApplyResult) -> None:
    prop_names = gradle_property_names(deps)
    for row in plan:
        if not row["bump"] or not row["target"]:
            continue
        dep = deps[row["key"]]
        version = row["target"]

        update_pin_file(dep, version, result)

        prop = prop_names.get(row["key"])
        if prop:
            synthetic = type(dep)(dep.key, {**dep.fields, "gradle_property": prop})
            update_gradle_property(synthetic, version, result)
        else:
            result.warnings.append(
                f"gradle.properties: no '{row['name']}' version property found - "
                "the mirror declaration moved, update tools/update-dependencies.py"
            )

        if dep.get("requirements_file"):
            update_requirements(dep, version, result)


# --------------------------------------------------------------------------- #
# reporting
# --------------------------------------------------------------------------- #
def print_plan(plan: "list[dict]", writing: bool) -> None:
    print(bold("Dependency bump plan"))
    for row in plan:
        name = f"{row['name']:<16}"
        if row["error"]:
            print(f"  {red('!')} {bold(name)} pinned {row['current']} - {row['error']}")
        elif row["blocked"]:
            print(f"  {red('x')} {bold(name)} {row['current']} -> {yellow(row['latest'])} BLOCKED ({row['reason']})")
        elif row["bump"]:
            action = "updating" if writing else "would update"
            print(f"  {green('^')} {bold(name)} {row['current']} -> {yellow(row['target'])} ({action}; {row['reason']})")
            print(f"      {dim(str(row['url']))}")
        else:
            print(f"  {dim('=')} {bold(name)} {row['current']} (up to date)")


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(
        description="Update the NewPipeExtractor and yt-dlp pins to newer upstream releases.",
        epilog="Inspect drift first with ./tools/check-dependencies.py",
    )
    parser.add_argument("--write", action="store_true", help="apply the bump (default is a dry run)")
    parser.add_argument("--only", metavar="NAME", help="restrict to one backend: newpipeextractor|yt-dlp")
    parser.add_argument(
        "--target",
        action="append",
        metavar="NAME=VERSION",
        default=[],
        help="pin an exact version instead of the newest (repeatable)",
    )
    parser.add_argument(
        "--allow-major",
        action="store_true",
        help="also accept major version bumps that may need manual API changes",
    )
    parser.add_argument("--json", action="store_true", help="emit the plan as JSON")
    args = parser.parse_args(argv)

    alias = {
        "yt-dlp": "yt_dlp",
        "ytdlp": "yt_dlp",
        "yt_dlp": "yt_dlp",
        "newpipe-extractor": "newpipeextractor",
        "newpipeextractor": "newpipeextractor",
    }

    def normalise(name: str) -> str:
        lowered = name.lower().replace("_", "-")
        return alias.get(lowered, alias.get(name.lower(), name.lower()))

    selected = None
    if args.only:
        selected = normalise(args.only)
        if selected not in DEPENDENCY_ORDER:
            die(f"unknown backend {args.only!r}; choose from: {', '.join(DEPENDENCY_ORDER)}")

    targets: "dict[str, str]" = {}
    for entry in args.target:
        if "=" not in entry:
            die(f"--target expects NAME=VERSION, got {entry!r}")
        name, version = entry.split("=", 1)
        targets[normalise(name)] = version.strip()
        if selected and normalise(name) != selected:
            die("--target and --only refer to different backends")

    deps = load_dependencies()
    keys = [selected] if selected else list(DEPENDENCY_ORDER)
    plan, errors = build_plan(deps, targets, args.allow_major, keys)

    actionable = [row for row in plan if row["bump"] and row["target"]]
    blocked = [row for row in plan if row["blocked"]]

    result = ApplyResult()
    if args.write and actionable:
        apply_plan(deps, plan, result)

    if args.json:
        print(json.dumps({
            "applied": bool(args.write and actionable),
            "outdated": bool(actionable),
            "errors": errors,
            "skipped": [{"name": r["name"], "from": r["current"], "to": r["latest"], "reason": r["reason"]}
                        for r in blocked],
            "results": plan,
            "changed_files": result.changed_files,
            "warnings": result.warnings,
        }, indent=2))
    else:
        print_plan(plan, writing=args.write)
        for edit in result.edits:
            print(edit.describe())
        for warning in result.warnings:
            print(yellow(f"warning: {warning}"), file=sys.stderr)
        if result.edits:
            print(green(f"\nApplied {len(result.edits)} edit(s) to {len(result.changed_files)} file(s)."))
        elif args.write and actionable:
            print(dim("\nNothing changed."))
        elif actionable:
            print(dim("\nDry run: pass --write to apply."))
        else:
            print(dim("\nNothing to do."))

    if errors:
        return 2
    if blocked and args.write and not actionable:
        # nothing could be applied because every candidate needed manual review
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
