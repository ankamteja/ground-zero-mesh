#!/usr/bin/env bash
# Give a headless phone its role and relay host, without touching the UI.
#
# The first screen of the app is where a person picks victim / relay / responder
# and types a laptop relay address. A headless emulator has nobody to tap it, and
# both settings are plain SharedPreferences (`RoleStore`, `RelayHostStore`) in one
# file, so a debug build lets `run-as` write them directly.
#
#   ./configure.sh emulator-5554 NODE 10.0.2.2:7802     victim, via laptop relay
#   ./configure.sh emulator-5556 RELAY 10.0.2.2:7802    relay
#   ./configure.sh emulator-5554 NODE ""                victim, over Nearby
#
# Roles are MeshRole names: NODE (victim), RELAY, GATEWAY (responder). A blank
# relay host means "use the real radio", exactly as RelayHostStore documents.
#
# 10.0.2.2 is the emulator's alias for the host loopback, so that is how a phone
# inside QEMU reaches `:core:runRelay` on the laptop. A physical phone on USB uses
# `responder.sh attach` and 127.0.0.1 instead.
#
# MeshForegroundService reads both once, at service start, so this stops the app
# first and you relaunch afterwards -- changing them under a running service does
# nothing, which is the same caveat the app's own screen prints.
set -euo pipefail

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="$sdk/platform-tools/adb"
pkg=org.groundzero.mesh.app
prefs=shared_prefs/ground_zero_mesh.xml

serial="${1:?usage: configure.sh <serial> <NODE|RELAY|GATEWAY> [relay-host[:port]]}"
role="${2:?usage: configure.sh <serial> <NODE|RELAY|GATEWAY> [relay-host[:port]]}"
host="${3-}"

case "$role" in
    NODE|RELAY|GATEWAY) ;;
    *) echo "configure: role must be NODE, RELAY or GATEWAY (got '$role')" >&2; exit 1 ;;
esac

"$adb" -s "$serial" shell am force-stop "$pkg"
sleep 1
"$adb" -s "$serial" shell "run-as $pkg mkdir -p shared_prefs"

# Merge, never overwrite. NodeIdStore keeps `node_id` in this same file, and that
# id is the phone's identity on the mesh -- dedup, corroboration and trust
# standing all key off it. Blowing the file away hands the node a new identity on
# every configure, which looks like a different victim each run.
existing="$("$adb" -s "$serial" shell "run-as $pkg cat $prefs" 2>/dev/null || true)"

merged="$(EXISTING="$existing" ROLE="$role" HOST="$host" python3 - <<'PY'
import os, re, html

existing = os.environ["EXISTING"]
overrides = {"mesh_role": os.environ["ROLE"], "relay_host": os.environ["HOST"]}

keep = {}
for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', existing, re.S):
    keep[m.group(1)] = m.group(2)
for m in re.finditer(r'<(int|long|boolean|float) name="([^"]+)" value="([^"]*)"\s*/>', existing):
    keep[m.group(2)] = (m.group(1), m.group(3))

lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
for name, value in keep.items():
    if name in overrides:
        continue
    if isinstance(value, tuple):
        kind, v = value
        lines.append(f'    <{kind} name="{name}" value="{v}" />')
    else:
        lines.append(f'    <string name="{name}">{value}</string>')
for name, value in overrides.items():
    lines.append(f'    <string name="{name}">{html.escape(value)}</string>')
lines.append("</map>")
print("\n".join(lines))
PY
)"

printf '%s\n' "$merged" | "$adb" -s "$serial" shell "run-as $pkg sh -c 'cat > $prefs'"

echo "configure: $serial role=$role relay_host='${host:-<Nearby>}'"
"$adb" -s "$serial" shell "run-as $pkg cat $prefs"
