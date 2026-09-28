#!/usr/bin/env python3
"""Update tracked dependency versions in gradle/libs.versions.toml.

Used by the "Dependency Updates" GitHub Actions workflow
(.github/workflows/dependency-updates.yml).

This script only rewrites entries in the [versions] table of the version
catalog. It never touches application code, keeping dependency-update logic
isolated from the app.

Usage:
    python3 update_versions.py --newpipe-extractor 0.24.3 --yt-dlp 2025.04.30

A missing/empty argument means "leave that version untouched".
Exits non-zero if a tracked key is not found in the catalog (fail loudly).
"""

import argparse
import re
import sys
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[2] / "gradle" / "libs.versions.toml"

# Maps CLI flags to [versions] keys in the catalog.
KEY_MAP = {
    "newpipe_extractor": "newpipe-extractor",
    "yt_dlp": "yt-dlp",
}


def update_key(text: str, key: str, value: str) -> str:
    """Replace `key = "..."` inside the [versions] table with the new value."""
    pattern = re.compile(rf'^({re.escape(key)}\s*=\s*)"[^"]*"(\s*(?:#.*)?)$', re.MULTILINE)
    new_text, count = pattern.subn(rf'\1"{value}"\2', text)
    if count == 0:
        print(f"ERROR: version key '{key}' not found in {CATALOG}", file=sys.stderr)
        sys.exit(1)
    return new_text


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--newpipe-extractor", default="", help="New release tag for NewPipeExtractor (leading 'v' stripped)")
    parser.add_argument("--yt-dlp", default="", help="New release tag for yt-dlp")
    args = parser.parse_args()

    text = CATALOG.read_text(encoding="utf-8")
    changed = False

    for flag_attr, key in KEY_MAP.items():
        value = getattr(args, flag_attr).strip()
        if not value:
            continue
        value = value.lstrip("vV") if key == "newpipe-extractor" else value
        old_match = re.search(rf'^{re.escape(key)}\s*=\s*"([^"]*)"', text, re.MULTILINE)
        old_value = old_match.group(1) if old_match else None
        if old_value == value:
            print(f"{key}: already up to date ({value})")
            continue
        text = update_key(text, key, value)
        print(f"{key}: {old_value} -> {value}")
        changed = True

    if changed:
        CATALOG.write_text(text, encoding="utf-8")
        print(f"Updated {CATALOG.relative_to(CATALOG.parents[2])}")
    else:
        print("No changes written.")


if __name__ == "__main__":
    main()
