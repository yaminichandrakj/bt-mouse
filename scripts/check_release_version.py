"""Ensure the Android app, Windows companion, and release tag use one version."""

import os
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_BUILD = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
COMPANION_SOURCE = (ROOT / "pc_server" / "app_gui.py").read_text(encoding="utf-8")
SERVER_SOURCE = (ROOT / "pc_server" / "server.py").read_text(encoding="utf-8")


def find(pattern: str, source: str, label: str) -> str:
    match = re.search(pattern, source, re.MULTILINE)
    if not match:
        raise SystemExit(f"Could not read {label} from source")
    return match.group(1)


android_version = find(r'versionName\s*=\s*"([0-9]+(?:\.[0-9]+){2})"', ANDROID_BUILD, "Android versionName")
companion_version = find(r'^APP_VERSION\s*=\s*"([0-9]+(?:\.[0-9]+){2})"', COMPANION_SOURCE, "companion APP_VERSION")
server_version = find(r'"version"\s*:\s*"([0-9]+(?:\.[0-9]+){2})"', SERVER_SOURCE, "server protocol version")
if len({android_version, companion_version, server_version}) != 1:
    raise SystemExit(
        f"Version mismatch: Android {android_version}, companion {companion_version}, server {server_version}"
    )

github_ref = os.environ.get("GITHUB_REF", "")
if github_ref.startswith("refs/tags/"):
    tag_version = github_ref.removeprefix("refs/tags/").removeprefix("v")
    if tag_version != android_version:
        raise SystemExit(
            f"Release tag {tag_version} does not match Android/companion version {android_version}"
        )

print(f"v{android_version}")
