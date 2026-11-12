#!/usr/bin/env bash
# Installs a fixed XcodeGen release into the directory given as $1, after checking the download
# against its SHA-256, so CI and the release generate the Xcode project with the same, known build.
# On GitHub Actions it also puts xcodegen on the PATH of later steps. Dependabot doesn't see this
# pin: to upgrade, take the version and the xcodegen.zip digest from the XcodeGen GitHub release.
set -euo pipefail

version=2.46.0
sha256=4d9e34b62172d645eed6457cac13fc222569974098ef4ee9c3368bedf0196806

dest="${1:?usage: install-xcodegen.sh <dir>}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

curl -fsSL --retry 3 -o "$tmp/xcodegen.zip" \
  "https://github.com/yonaskolb/XcodeGen/releases/download/$version/xcodegen.zip"
echo "$sha256  $tmp/xcodegen.zip" | shasum -a 256 -c -

# The archive holds xcodegen/bin and xcodegen/share; the binary finds its presets in ../share.
mkdir -p "$dest"
unzip -q -o "$tmp/xcodegen.zip" -d "$dest"
bin="$(cd "$dest/xcodegen/bin" && pwd)"
if [[ -n "${GITHUB_PATH:-}" ]]; then
  echo "$bin" >> "$GITHUB_PATH"
fi
"$bin/xcodegen" --version
