#!/usr/bin/env bash
# Raise an SOS on a headless phone by driving its actual UI.
#
# Not a back door: this taps the same Compose buttons a trapped person would, so
# the frame that reaches the board came through VictimScreen -> NodeViewModel ->
# NodeAgent.raiseSos like any other. That is the point -- a board row proves the
# whole path, which an injected frame would not.
#
#   ./sos.sh emulator-5554                  trapped (default)
#   ./sos.sh emulator-5554 drowning
#
# Buttons are found by label in a uiautomator dump rather than by pixel, so this
# survives a layout change and fails loudly instead of tapping empty screen.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="$sdk/platform-tools/adb"
pkg=org.groundzero.mesh.app

serial="${1:?usage: sos.sh <serial> [drowning|trapped|other]}"
what="${2:-trapped}"
dump="$here/.run/ui-$serial.xml"
mkdir -p "$here/.run"

launch() {
    "$adb" -s "$serial" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
    sleep 8
}

refresh() {
    "$adb" -s "$serial" shell uiautomator dump /sdcard/gzm-ui.xml > /dev/null 2>&1
    "$adb" -s "$serial" shell cat /sdcard/gzm-ui.xml 2>/dev/null > "$dump"
}

# Centre of the node whose text matches $1 exactly, or empty if it is not on screen.
centre_of() {
    python3 - "$dump" "$1" <<'PY'
import re, sys
try:
    xml = open(sys.argv[1]).read()
except OSError:
    sys.exit(0)
want = sys.argv[2].lower()
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    text, x1, y1, x2, y2 = m.groups()
    if text.strip().lower() == want:
        print((int(x1) + int(x2)) // 2, (int(y1) + int(y2)) // 2)
        break
PY
}

tap_label() {
    local label="$1" coords
    coords="$(centre_of "$label")"
    if [[ -z "$coords" ]]; then
        echo "sos: '$label' is not on screen -- is the app open on the victim screen?" >&2
        echo "sos: labels currently visible:" >&2
        grep -oE 'text="[^"]+"' "$dump" 2>/dev/null | sed 's/text=//;s/"//g' | sed 's/^/  /' | head -12 >&2
        exit 1
    fi
    # shellcheck disable=SC2086
    "$adb" -s "$serial" shell input tap $coords
    echo "sos: tapped '$label' at $coords"
    sleep 1
    refresh
}

launch
refresh
# A phone already serving as victim shows the SOS button without a role tap, but
# tapping the role again is harmless and makes the run reproducible from any state.
tap_label victim
tap_label "$what"
tap_label "SEND SOS"

sleep 4
if "$adb" -s "$serial" logcat -d 2>/dev/null | grep -q "FATAL EXCEPTION"; then
    echo "sos: the app crashed after SEND SOS -- last stack:" >&2
    "$adb" -s "$serial" logcat -d 2>/dev/null | grep -A12 "FATAL EXCEPTION" | tail -14 >&2
    exit 1
fi
echo "sos: sent from $serial ($what), no crash"
