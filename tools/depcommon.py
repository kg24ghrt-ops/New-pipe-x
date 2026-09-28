"""Shared helpers for the newpipex dependency-update workflow.

The project pins two extraction backends (see ``config/dependencies.toml``):

* **NewPipeExtractor** -- the lightweight / native Android YouTube path,
  consumed as a Gradle dependency version.
* **yt-dlp** -- the Python path executed through Chaquopy, consumed as a pinned
  requirement in a ``requirements.txt`` file.

This module knows how to

* read and rewrite the pin file without destroying its comments,
* ask GitHub / PyPI for the newest upstream release,
* normalise and order the two different versioning schemes (semver vs CalVer),
* apply a version bump to every mirrored declaration in the build files.

Only the Python standard library is used, so the workflow runs on a plain CI
runner or a developer machine without any extra installation step.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from typing import Iterable

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CONFIG_PATH = os.path.join(REPO_ROOT, "config", "dependencies.toml")

#: The managed backends, in the order reports and updates walk them.
DEPENDENCY_ORDER = ("newpipeextractor", "yt_dlp")

USER_AGENT = "newpipex-dependency-workflow"


# --------------------------------------------------------------------------- #
# terminal helpers
# --------------------------------------------------------------------------- #
def _use_color(stream) -> bool:
    return hasattr(stream, "isatty") and stream.isatty() and os.environ.get("NO_COLOR") is None


def color(text: str, code: str, stream=sys.stdout) -> str:
    if not _use_color(stream):
        return text
    return f"\033[{code}m{text}\033[0m"


def green(text: str) -> str:
    return color(text, "32")


def red(text: str) -> str:
    return color(text, "31")


def yellow(text: str) -> str:
    return color(text, "33")


def bold(text: str) -> str:
    return color(text, "1")


def dim(text: str) -> str:
    return color(text, "2")


def die(message: str, status: int = 1) -> None:
    print(red(f"error: {message}"), file=sys.stderr)
    raise SystemExit(status)


# --------------------------------------------------------------------------- #
# pin file: config/dependencies.toml
# --------------------------------------------------------------------------- #
@dataclass
class Dependency:
    """One managed backend together with its metadata from the pin file."""

    key: str
    fields: dict

    @property
    def current(self) -> str:
        return self.fields["current"]

    @property
    def kind(self) -> str:
        return self.fields.get("kind", "")

    @property
    def repository(self) -> str:
        return self.fields.get("repository", "")

    def get(self, name: str, default=None):
        return self.fields.get(name, default)

    def label(self) -> str:
        """Human readable name used in reports, commit messages, branch names."""
        return {"newpipeextractor": "NewPipeExtractor", "yt_dlp": "yt-dlp"}.get(self.key, self.key)


_TABLE_RE = re.compile(r"^\s*\[([A-Za-z0-9_.-]+)\]\s*(?:#.*)?$")
_KEY_RE = re.compile(r'^\s*([A-Za-z0-9_-]+)\s*=\s*"((?:[^"\\]|\\.)*)"\s*$')


def load_dependencies(path: str = CONFIG_PATH) -> "dict[str, Dependency]":
    """Parse the pin file into ``{key: Dependency}``.

    A tiny purpose-built parser is used instead of :mod:`tomllib` because the
    file must stay round-trippable (comments preserved) when it is rewritten.
    Only the flat ``key = "value"`` form is accepted; anything else is an error
    rather than a silently ignored line.
    """
    if not os.path.exists(path):
        die(f"pin file not found: {rel(path)}")

    deps: "dict[str, Dependency]" = {}
    table: "str | None" = None
    with open(path, encoding="utf-8") as handle:
        for lineno, raw in enumerate(handle, start=1):
            match = _TABLE_RE.match(raw)
            if match:
                table = match.group(1)
                deps.setdefault(table, Dependency(table, {}))
                continue
            match = _KEY_RE.match(raw)
            if match:
                if table is None:
                    die(f"{rel(path)}:{lineno}: key outside of a [section]")
                deps[table].fields[match.group(1)] = match.group(2)
                continue
            stripped = raw.strip()
            if stripped and not stripped.startswith("#"):
                die(f"{rel(path)}:{lineno}: unsupported syntax: {stripped}")

    missing = [key for key in DEPENDENCY_ORDER if key not in deps]
    if missing:
        die(f"pin file is missing sections: {', '.join(missing)}")
    for dep in deps.values():
        if not dep.fields.get("current"):
            die(f"pin file section [{dep.key}] has no 'current' version")
    return deps


# --------------------------------------------------------------------------- #
# versions
# --------------------------------------------------------------------------- #
def strip_tag_prefix(tag: str, prefix: str = "") -> str:
    tag = tag.strip()
    if prefix and tag.startswith(prefix):
        return tag[len(prefix):]
    if re.match(r"^v\d", tag):  # forgiving default: many projects use "v"
        return tag[1:]
    return tag


def normalize_pypi_version(version: str) -> "tuple[int, ...]":
    """Turn a version string into a comparable tuple of integers.

    Handles yt-dlp's CalVer (``2026.8.19``), plain semver (``0.26.5``) and
    pre-release suffixes (``2026.9.17.dev0``, ``1.2.3rc1``) well enough to order
    stable releases.
    """
    core = re.split(r"[^0-9.]", version.strip(), maxsplit=1)[0]
    parts = [int(piece) for piece in core.split(".") if piece.isdigit()]
    return tuple(parts + [0] * (4 - len(parts))) if len(parts) < 4 else tuple(parts)


def github_tag_to_pypi_version(tag: str) -> str:
    """``2026.08.19`` (git tag) -> ``2026.8.19`` (PyPI distribution version)."""
    version = strip_tag_prefix(tag)
    match = re.match(r"^(\d{4})\.0?(\d{1,2})\.0?(\d{1,2})$", version)
    if not match:
        return version
    year, month, day = match.groups()
    return f"{year}.{int(month)}.{int(day)}"


def version_sort_key(version: str) -> tuple:
    return normalize_pypi_version(version)


def pick_latest(versions: Iterable[str]) -> "str | None":
    versions = [version for version in versions if version]
    return max(versions, key=version_sort_key) if versions else None


# --------------------------------------------------------------------------- #
# upstream queries
# --------------------------------------------------------------------------- #
def http_get_json(url: str, timeout: float = 30.0):
    request = urllib.request.Request(
        url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"}
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError) as exc:
        raise RuntimeError(f"cannot query {url}: {exc}") from exc


def github_releases(repository: str, per_page: int = 100) -> list:
    return http_get_json(f"https://api.github.com/repos/{repository}/releases?per_page={per_page}")


def github_latest_release(repository: str, include_prereleases: bool = False) -> dict:
    releases = [
        release
        for release in github_releases(repository)
        if not release.get("draft") and (include_prereleases or not release.get("prerelease"))
    ]
    if not releases:
        raise RuntimeError(f"{repository}: no usable GitHub release found")
    return max(releases, key=lambda release: version_sort_key(release.get("tag_name", "")))


def pypi_latest_version(package: str) -> str:
    info = http_get_json(f"https://pypi.org/pypi/{package}/json")["info"]
    version = info.get("version")
    if not version:
        raise RuntimeError(f"{package}: PyPI returned no latest version")
    return version


def matching_github_tag(repository: str, version: str) -> "str | None":
    """Best-effort lookup of the git tag that corresponds to a PyPI version."""
    try:
        tags = http_get_json(f"https://api.github.com/repos/{repository}/tags?per_page=100")
    except RuntimeError:
        return None
    wanted = normalize_pypi_version(version)
    for entry in tags:
        if normalize_pypi_version(strip_tag_prefix(entry.get("name", ""))) == wanted:
            return entry["name"]
    return None


def fetch_latest(dep: Dependency, include_prereleases: bool = False) -> dict:
    """Resolve the newest upstream release for one dependency.

    The returned dict carries ``version`` (the value written into the build
    files), ``tag`` (upstream git tag), ``url`` (release page) and ``source``.
    """
    kind = dep.kind

    if kind == "python-package":
        package = dep.get("pypi_package") or dep.label()
        version = pypi_latest_version(package)
        tag = matching_github_tag(dep.repository, version) if dep.repository else None
        if tag:
            url = f"https://github.com/{dep.repository}/releases/tag/{tag}"
        else:
            tag = github_tag_to_pypi_version(version)
            url = f"https://pypi.org/project/{package}/{version}/"
        return {"version": version, "tag": tag, "url": url, "source": f"PyPI ({package})"}

    if kind == "github-release":
        release = github_latest_release(dep.repository, include_prereleases=include_prereleases)
        tag = release["tag_name"]
        version = strip_tag_prefix(tag, dep.get("tag_prefix", ""))
        if str(dep.get("version_scheme", "")).startswith("calver"):
            version = github_tag_to_pypi_version(tag)
        return {
            "version": version,
            "tag": tag,
            "url": release.get("html_url", f"https://github.com/{dep.repository}/releases/tag/{tag}"),
            "source": f"GitHub ({dep.repository})",
        }

    raise RuntimeError(f"[{dep.key}]: unknown kind {kind!r} in the pin file")


# --------------------------------------------------------------------------- #
# editing the build files
# --------------------------------------------------------------------------- #
@dataclass
class Edit:
    path: str
    old_line: str
    new_line: str
    reason: str

    def describe(self) -> str:
        indent = " " * (len(self.old_line) - len(self.old_line.lstrip()))
        return (
            f"{self.rel()}:\n"
            f"{indent}{dim('- ' + self.old_line.strip())}\n"
            f"{indent}{green('+ ' + self.new_line.strip())}"
        )

    def rel(self) -> str:
        return rel(self.path)


@dataclass
class ApplyResult:
    edits: "list[Edit]" = field(default_factory=list)
    warnings: "list[str]" = field(default_factory=list)
    unchanged: "list[str]" = field(default_factory=list)

    @property
    def changed_files(self) -> "list[str]":
        return sorted({edit.rel() for edit in self.edits})

    def add_edit(self, path: str, old_line: str, new_line: str, reason: str) -> None:
        self.edits.append(Edit(path, old_line, new_line, reason))

    def record(self, path: str, line: str, reason: str) -> None:
        """Note a declaration that already carries the requested version."""
        self.unchanged.append(f"{rel(path)}: {line.strip()} ({reason})")


def rel(path: str) -> str:
    return os.path.relpath(path, REPO_ROOT)


def _read_lines(path: str) -> "list[str]":
    with open(path, encoding="utf-8") as handle:
        return handle.read().splitlines(keepends=True)


def _write_lines(path: str, lines: Iterable[str]) -> None:
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("".join(lines))


def rewrite(path: str, regex: "re.Pattern", template: str, replacement: str, reason: str,
            result: ApplyResult, *, required: bool = True) -> None:
    """Rewrite each line matching ``regex`` with ``template``.

    ``template`` may reference named groups (``\\g<name>``) and the
    ``{replacement}`` placeholder.  Lines that already carry the requested value
    are recorded but left untouched, which keeps the workflow idempotent.
    """
    if not os.path.exists(path):
        if required:
            result.warnings.append(f"{rel(path)} is missing - expected it to declare {reason}")
        return

    lines = _read_lines(path)
    matched = False
    dirty = False
    for index, line in enumerate(lines):
        stripped = line.rstrip("\n")
        match = regex.fullmatch(stripped)
        if not match:
            continue
        matched = True
        new_line = match.expand(template).replace("{replacement}", replacement)
        if new_line == stripped:
            result.record(path, stripped, reason)
            continue
        newline = "\n" if line.endswith("\n") else ""
        result.add_edit(path, stripped, new_line, reason)
        lines[index] = new_line + newline
        dirty = True

    if not matched and required:
        result.warnings.append(
            f"{rel(path)}: nothing matched /{regex.pattern}/ - the {reason} declaration moved, "
            "update tools/update-dependencies.py"
        )
        return
    if dirty:
        _write_lines(path, lines)


def _gradle_property_regex(name: str) -> "re.Pattern":
    return re.compile(rf'^(?P<head>\s*){re.escape(name)}(?P<eq>\s*=\s*")(?P<old>[^"]*)(?P<tail>"\s*(?:#.*)?)$')


def update_gradle_property(dep: Dependency, version: str, result: ApplyResult,
                           path: str | None = None) -> None:
    """Bump ``<name>=<old>`` in gradle.properties (NewPipeExtractor pin)."""
    name = dep.get("gradle_property")
    if not name:
        return
    path = path or os.path.join(REPO_ROOT, "gradle.properties")
    template = r"\g<head>" + name + r"\g<eq>{replacement}\g<tail>"
    rewrite(path, _gradle_property_regex(name), template, version,
            f"Gradle property '{name}'", result)


def update_requirements(dep: Dependency, version: str, result: ApplyResult,
                        root: str | None = None) -> None:
    """Bump ``yt-dlp==<old>`` in the Chaquopy requirements file."""
    requirement = dep.get("requirement_name") or dep.label()
    filename = dep.get("requirements_file")
    if not filename:
        return
    path = os.path.join(root or REPO_ROOT, filename)
    regex = re.compile(
        rf'^(?P<pad>\s*)(?P<name>{re.escape(requirement)})'
        rf'(?P<op>==)(?P<old>[^;\s]+)(?P<rest>.*)$'
    )
    rewrite(path, regex, r"\g<pad>\g<name>\g<op>{replacement}\g<rest>", version,
            f"Python requirement '{requirement}'", result)


def update_pin_file(dep: Dependency, version: str, result: ApplyResult,
                    path: str = CONFIG_PATH) -> None:
    """Keep the ``current`` field of the pin file in sync with the build files."""
    regex = re.compile(r'^(?P<head>\s*current\s*=\s*")(?P<old>[^"]*)(?P<tail>"\s*(?:#.*)?)$')
    rewrite_at(path, dep.key, regex, r"\g<head>{replacement}\g<tail>", version,
               f"pin file [{dep.key}] current", result)


def rewrite_at(path: str, section: str, regex: "re.Pattern", template: str, replacement: str,
               reason: str, result: ApplyResult) -> None:
    """Rewrite the first matching line inside one ``[section]`` of a file."""
    if not os.path.exists(path):
        result.warnings.append(f"{rel(path)} is missing - expected it to declare {reason}")
        return

    lines = _read_lines(path)
    inside = False
    matched = False
    dirty = False
    for index, line in enumerate(lines):
        table = _TABLE_RE.match(line)
        if table:
            inside = table.group(1) == section
            continue
        if not inside:
            continue
        stripped = line.rstrip("\n")
        match = regex.fullmatch(stripped)
        if not match:
            continue
        matched = True
        new_line = match.expand(template).replace("{replacement}", replacement)
        if new_line == stripped:
            result.record(path, stripped, reason)
        else:
            newline = "\n" if line.endswith("\n") else ""
            result.add_edit(path, stripped, new_line, reason)
            lines[index] = new_line + newline
            dirty = True
        break

    if not matched:
        result.warnings.append(f"{rel(path)}: no 'current' entry found in section [{section}]")
        return
    if dirty:
        _write_lines(path, lines)
