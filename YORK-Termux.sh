#!/data/data/com.termux/files/usr/bin/bash
# YORK: one-command preserved-source import, GitHub APK build, and Android installer handoff.
set -Eeuo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO='justappgrabbin/Thelastfuxk'
BRANCH='fix/agent-autonomy-hands'
ARCHIVE="${SOURCE_ZIP:-$HERE/../The-Last-Fuck-YORK-Termux-ready.zip}"
if [[ ! -f "$ARCHIVE" ]]; then
  echo 'Set SOURCE_ZIP to the downloaded YORK ZIP if it was moved.'
fi
[[ -f "$ARCHIVE" ]] || { echo "Missing source ZIP: $ARCHIVE" >&2; exit 1; }
if [[ -n "${PREFIX:-}" && "$PREFIX" == *com.termux* ]]; then
  echo 'Installing Termux prerequisites (may prompt for approval)...'
  pkg install -y git python unzip gh curl
fi
for cmd in git python3 unzip gh; do command -v "$cmd" >/dev/null || { echo "Missing $cmd. Install it before continuing." >&2; exit 1; }; done
if ! gh auth status >/dev/null 2>&1; then
  echo 'Connect GitHub once using gh auth login:'
  gh auth login -h github.com -p https -w
fi
export GH_REPO="$REPO"
# Never overwrite the original installed application or discard its data.
export CONFIRM_IMPORT=yes
bash "$HERE/import-lastfuck-to-github.sh" "$ARCHIVE"
echo 'Waiting for the new Android APK workflow...'
# Identify run triggered by this import (newest branch run).
run_id=''
for i in $(seq 1 24); do
  run_id="$(gh run list --repo "$REPO" --branch "$BRANCH" --workflow android-apk.yml --limit 1 --json databaseId --jq '.[0].databaseId // empty')"
  [[ -n "$run_id" ]] && break
  sleep 5
done
[[ -n "$run_id" ]] || { echo 'No Android build run found. Check GitHub Actions.' >&2; exit 1; }
echo "Following workflow run $run_id"
if ! gh run watch "$run_id" --repo "$REPO" --exit-status; then
  echo 'Build failed. Read the workflow logs; no APK will be installed.' >&2
  exit 1
fi
out="$HOME/storage/downloads/The-Last-Fuck-YORK"
mkdir -p "$out"
gh run download "$run_id" --repo "$REPO" --name The-Last-Fuck-debug-apk --dir "$out"
apk="$out/The-Last-Fuck-debug.apk"
[[ -s "$apk" ]] || { echo 'Build completed but no APK artifact found.' >&2; exit 1; }
echo "APK downloaded: $apk"
echo 'IMPORTANT: This workflow may use a NEW debug signing key, which cannot update your installed v0.1.3 app.'
echo 'Do not uninstall the original app or delete its data just to install this test APK.'
echo 'Android will check the signature and ask you to confirm installation.'
if command -v termux-open >/dev/null; then
  termux-open --view --content-type application/vnd.android.package-archive "$apk"
else
  echo "Open the APK in your Downloads file manager: $apk"
fi
