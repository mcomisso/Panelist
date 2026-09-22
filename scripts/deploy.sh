#!/usr/bin/env bash
# Build PanelDash, install it on the panel, push the config, and (re)start it.
# Usage: ./scripts/deploy.sh          (PANEL / CONFIG to override, or deploy/local.env)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Personal defaults, kept out of git: deploy/local.env may set PANEL / CONFIG.
if [ -f "$ROOT/deploy/local.env" ]; then . "$ROOT/deploy/local.env"; fi
PANEL="${PANEL:-192.168.1.100:5555}"
CONFIG="${CONFIG:-deploy/config_panel.yaml}"   # deploy/office.yaml is an example live view
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

cd "$ROOT"
# Release APK: R8-minified ~1 MB vs ~12 MB debug — small transfers survive the
# panel's flaky WiFi (a stalled debug install takes 20+ min; release installs in seconds).
./gradlew :app:assembleRelease

adb connect "$PANEL"
# Push to the device, then install from the device — reliable on degraded links.
adb -s "$PANEL" push app/build/outputs/apk/release/app-release.apk /data/local/tmp/paneldash.apk
if ! adb -s "$PANEL" shell "pm install -r /data/local/tmp/paneldash.apk" | grep -q Success; then
  # Signature mismatch (a build signed with a different debug key is installed):
  # update-in-place is impossible, so uninstall + fresh install. Config and token
  # live on /sdcard/paneldash/ and survive the uninstall.
  echo "Update failed — reinstalling fresh (config on /sdcard survives)"
  adb -s "$PANEL" uninstall com.mcsoftware.paneldash || true
  adb -s "$PANEL" shell "pm install /data/local/tmp/paneldash.apk"
  adb -s "$PANEL" shell "pm grant com.mcsoftware.paneldash android.permission.READ_EXTERNAL_STORAGE" || true
  # Re-claim home-app role (lost on uninstall).
  adb -s "$PANEL" shell "cmd package set-home-activity com.mcsoftware.paneldash/.MainActivity" || true
fi
adb -s "$PANEL" shell "rm -f /data/local/tmp/paneldash.apk"
adb -s "$PANEL" shell "mkdir -p /sdcard/paneldash"
# HA token lives outside the repo: ~/paneldash-token.txt -> /sdcard/paneldash/token.txt
if [ -f "$HOME/paneldash-token.txt" ]; then
  adb -s "$PANEL" push "$HOME/paneldash-token.txt" /sdcard/paneldash/token.txt
fi
adb -s "$PANEL" push "$CONFIG" /sdcard/paneldash/config.yaml
adb -s "$PANEL" shell "am start -n com.mcsoftware.paneldash/.MainActivity"
echo "PanelDash deployed to $PANEL"
