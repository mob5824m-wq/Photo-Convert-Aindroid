from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path
from typing import Iterable, Optional, Tuple

INITIAL_VERSION = "1.0.0"
VERSION_TAG_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)$")


def read_repo_version() -> str:
    version_file = Path(__file__).resolve().parent.parent / "VERSION"
    if version_file.is_file():
        raw = version_file.read_text(encoding="utf-8").strip().lstrip("v")
        if VERSION_TAG_RE.match(raw):
            return raw
    return INITIAL_VERSION


def parse_version(raw: str) -> Tuple[int, int, int]:
    match = VERSION_TAG_RE.match((raw or "").strip())
    if not match:
        raise ValueError(f"Invalid version format: {raw!r}")
    major, minor, patch = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
    if major < 1 or not (0 <= minor <= 9) or not (0 <= patch <= 9):
        raise ValueError(
            f"Version {raw!r} outside rollover range (major>=1, 0<=minor<=9, 0<=patch<=9)"
        )
    return major, minor, patch


def compute_version_code(major: int, minor: int, patch: int) -> int:
    """
    Monotonically increasing Android versionCode aligned 1:1 with base-10 rollover:
      1.0.0 -> 1
      1.0.1 -> 2
      ...
      1.0.9 -> 10
      1.1.0 -> 11
      ...
      1.9.9 -> 100
      2.0.0 -> 101
    """
    return (major - 1) * 100 + minor * 10 + patch + 1


def bump_rollover_version(previous: Optional[str]) -> Tuple[str, int]:
    """
    Starts at 1.0.0 when no previous release exists.
    Each merge increments patch by +1 (0.0.1):
      - 1.0.0 -> 1.0.1 -> ... -> 1.0.9 -> 1.1.0
      - 1.1.9 -> 1.2.0 -> ... -> 1.9.9 -> 2.0.0
    Both patch (right column) and minor (middle column) roll over from 9 -> 0!
    """
    if previous is None or not str(previous).strip():
        major, minor, patch = 1, 0, 0
        return f"{major}.{minor}.{patch}", compute_version_code(major, minor, patch)

    major, minor, patch = parse_version(previous)
    patch += 1
    if patch > 9:
        patch = 0
        minor += 1
    if minor > 9:
        minor = 0
        major += 1
    return f"{major}.{minor}.{patch}", compute_version_code(major, minor, patch)


def resolve_next_from_tags(tags: Iterable[str]) -> Tuple[str, int, Optional[str]]:
    parsed_versions: list[Tuple[int, int, int, str]] = []
    for raw_tag in tags:
        clean = (raw_tag or "").strip()
        m = VERSION_TAG_RE.match(clean)
        if not m:
            continue
        major, minor, patch = int(m.group(1)), int(m.group(2)), int(m.group(3))
        if major >= 1 and 0 <= minor <= 9 and 0 <= patch <= 9:
            parsed_versions.append((major, minor, patch, f"v{major}.{minor}.{patch}"))

    if not parsed_versions:
        ver, code = bump_rollover_version(None)
        return ver, code, None

    parsed_versions.sort(key=lambda item: (item[0], item[1], item[2]))
    latest_major, latest_minor, latest_patch, latest_tag = parsed_versions[-1]
    ver, code = bump_rollover_version(f"{latest_major}.{latest_minor}.{latest_patch}")
    return ver, code, latest_tag


def list_git_tags(repo_dir: Optional[Path] = None) -> list[str]:
    proc = subprocess.run(
        ["git", "tag", "--list", "v*"],
        cwd=str(repo_dir) if repo_dir else None,
        capture_output=True,
        text=True,
        check=False,
    )
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Compute next Pixel Photo NAS release version (1.0.0 -> 1.0.9 -> 1.1.0 -> 1.9.9 -> 2.0.0)."
    )
    parser.add_argument(
        "--github-output",
        type=str,
        default="",
        help="Optional path to $GITHUB_OUTPUT file to write version, tag, and version_code.",
    )
    args = parser.parse_args()

    tags = list_git_tags()
    version, version_code, prev_tag = resolve_next_from_tags(tags)
    tag = f"v{version}"

    print(f"Resolved next version: {tag} (versionCode={version_code}, previous={prev_tag or 'none'})")
    if args.github_output:
        out_path = Path(args.github_output)
        with out_path.open("a", encoding="utf-8") as fh:
            fh.write(f"version={version}\n")
            fh.write(f"tag={tag}\n")
            fh.write(f"version_code={version_code}\n")
            fh.write(f"prev_tag={prev_tag or ''}\n")


if __name__ == "__main__":
    main()
