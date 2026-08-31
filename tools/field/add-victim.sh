#!/usr/bin/env bash
# Add one emulated victim to a demo that is already running.
#
#   ./add-victim.sh [zone] [severity]
#
# Unlike `demo.sh --show`, this leaves the existing run alone: the relay, the
# laptop's board and the advisor keep their state, the phone keeps its incident,
# and the emulator joins as one more victim on the same mesh. That is the point
# -- a second casualty appearing mid-incident is the situation a responder board
# is for, and restarting everything to stage it would throw away the first one.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
adb="${ADB:-adb}"
relay_port="${FIELD_RELAY_PORT:-7802}"
mem_mb="${FIELD_MEM_MB:-1024}"

zone="${1:-block-b-west}"
severity="${2:-trapped}"

# Plan units for the zone centres in app/src/main/assets/siteplan/plan.txt.
case "$zone" in
    block-a-north) mark_x=230; mark_y=170 ;;
    block-a-south) mark_x=230; mark_y=430 ;;
    block-b-east)  mark_x=750; mark_y=170 ;;
    block-b-west)  mark_x=750; mark_y=430 ;;
    courtyard)     mark_x=480; mark_y=400 ;;
    car-park)      mark_x=490; mark_y=670 ;;
    *) echo "add-victim: unknown zone '$zone' -- see siteplan/plan.txt" >&2; exit 1 ;;
esac

# The relay has to already be up: this joins a run, it does not start one.
if ! (exec 3<>/dev/tcp/127.0.0.1/"$relay_port") 2>/dev/null; then
    echo "add-victim: nothing is listening on $relay_port -- run ./demo.sh first" >&2
    exit 1
fi

available=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
need=$(( mem_mb + 400 ))
echo "add-victim: one emulator at ${mem_mb} MB needs about ${need} MB; ${available} MB available"
if [[ "$available" -lt "$need" ]]; then
    echo "add-victim: not enough memory. Close a browser, or FIELD_MEM_MB=768 $0" >&2
    exit 1
fi

FIELD_MEM_MB="$mem_mb" "$here/field.sh" up 1 || exit 1
FIELD_MEM_MB="$mem_mb" "$here/field.sh" install || exit 1

serial="$("$adb" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1; exit}')"
[[ -n "$serial" ]] || { echo "add-victim: no emulator came up" >&2; exit 1; }

# 10.0.2.2 is the host as seen from inside an emulator; the phone reaches the same
# relay through its adb reverse tunnel. Different addresses, one mesh.
"$here/configure.sh" "$serial" NODE "10.0.2.2:$relay_port" > /dev/null

# The emulator's synthetic GPS reports a Californian coordinate nobody measured, and
# it would arrive labelled SATELLITE and outrank the mark below. Off is the honest state.
"$adb" -s "$serial" shell settings put secure location_mode 0 > /dev/null 2>&1 || true
"$here/mark.sh" "$serial" "$mark_x" "$mark_y" "$zone" > /dev/null
echo "add-victim: $serial is a victim in $zone"

"$here/mirror.sh" || echo "add-victim: mirroring failed -- the node is fine, just not on screen" >&2

"$here/sos.sh" "$serial" "$severity" || exit 1

echo
echo "add-victim: $serial raised '$severity' from $zone; the phone's incident is untouched"
echo "board  http://localhost:${FIELD_BOARD_PORT:-8080}/"
