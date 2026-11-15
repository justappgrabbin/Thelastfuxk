#!/usr/bin/env bash
set -euo pipefail
# Run from a machine with git push access to justappgrabbin/Thelastfuxk.
# Usage: bash scripts/import-preserved-source.sh /path/to/The-Last-Fuck-agent-progress-repair-source.zip
archive="${1:?Supply the preserved repaired source ZIP}"
repo="https://github.com/justappgrabbin/Thelastfuxk.git"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
unzip -q "$archive" -d "$tmp/unpacked"
src="$tmp/unpacked/The-Last-Fuck"
for f in AndroidManifest.xml build.sh assets/alpine-coder.tar.gz native/arm64-v8a/libllama_server.so; do
  test -s "$src/$f" || { echo "Missing required original asset: $f" >&2; exit 1; }
done
git clone --branch fix/agent-autonomy-hands "$repo" "$tmp/repo"
tar -C "$src" --exclude=.git -cf - . | tar -C "$tmp/repo" -xf -
cd "$tmp/repo"
git add AndroidManifest.xml README.md build.sh assets native res src tests
git commit -m "Import preserved v0.1.3 source and local agent repairs" || { echo "No source changes to commit"; exit 0; }
git push origin fix/agent-autonomy-hands
echo "Source imported. Verify GitHub Actions and do not install before device tests."
