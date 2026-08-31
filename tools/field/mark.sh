#!/usr/bin/env bash
# Place a phone on the site plan, the way tapping the picker would.
#
#   ./mark.sh <serial> <plan-x> <plan-y> <zone>
#
# This writes the same three keys SelfPositionStore writes when a person taps the
# site plan (self_pos_x, self_pos_y, self_pos_zone), in plan units -- so an
# emulator, which has no GPS at all, can still say where it is.
#
# It is not a faked fix. The mark travels as FixSource.SELF_REPORTED and the
# board renders it "Marked", never "GPS fix"; IncidentCluster.betterFix() will
# not let it displace a real satellite lock. A tap is a claim, and the wire
# format says so. Faking a satellite fix instead would put a measurement's
# authority behind a guess, which is the one thing this board must never do.
#
# Coordinates are plan units from app/src/main/assets/siteplan/plan.txt -- the
# stored mark is plan-space on purpose, so correcting the georeference moves
# every mark with it rather than stranding them at stale coordinates.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
adb="${ADB:-adb}"
pkg=org.groundzero.mesh.app
# Same file configure.sh writes, and the one RoleStore/SelfPositionStore actually read:
# getSharedPreferences("ground_zero_mesh"), not the package-named default. Writing
# shared_prefs/$pkg.xml instead just creates a file nothing ever opens.
prefs=shared_prefs/ground_zero_mesh.xml

serial="${1:?usage: mark.sh <serial> <plan-x> <plan-y> <zone>}"
mark_x="${2:?usage: mark.sh <serial> <plan-x> <plan-y> <zone>}"
mark_y="${3:?usage: mark.sh <serial> <plan-x> <plan-y> <zone>}"
zone="${4:?usage: mark.sh <serial> <plan-x> <plan-y> <zone>}"

# The app rewrites this file when it stops, so it has to be down before we edit.
"$adb" -s "$serial" shell am force-stop "$pkg"
sleep 1
"$adb" -s "$serial" shell "run-as $pkg mkdir -p shared_prefs"

existing="$("$adb" -s "$serial" shell "run-as $pkg cat $prefs" 2>/dev/null || true)"

# Same merge rule as configure.sh: keep every other key, above all node_id, which
# is this phone's identity on the mesh.
merged="$(EXISTING="$existing" X="$mark_x" Y="$mark_y" ZONE="$zone" python3 - <<'PY'
import os, re, html

existing = os.environ["EXISTING"]
floats = {"self_pos_x": os.environ["X"], "self_pos_y": os.environ["Y"]}
strings = {"self_pos_zone": os.environ["ZONE"]}
drop = set(floats) | set(strings)

keep = {}
for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', existing, re.S):
    keep[m.group(1)] = m.group(2)
for m in re.finditer(r'<(int|long|boolean|float) name="([^"]+)" value="([^"]*)"\s*/>', existing):
    keep[m.group(2)] = (m.group(1), m.group(3))

lines = ["<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", "<map>"]
for name, value in keep.items():
    if name in drop:
        continue
    if isinstance(value, tuple):
        kind, v = value
        lines.append(f'    <{kind} name="{name}" value="{v}" />')
    else:
        lines.append(f'    <string name="{name}">{value}</string>')
for name, value in floats.items():
    lines.append(f'    <float name="{name}" value="{value}" />')
for name, value in strings.items():
    lines.append(f'    <string name="{name}">{html.escape(value)}</string>')
lines.append("</map>")
print("\n".join(lines))
PY
)"

printf '%s\n' "$merged" | "$adb" -s "$serial" shell "run-as $pkg sh -c 'cat > $prefs'"
echo "mark: $serial marked at ($mark_x, $mark_y) in $zone -- self-reported, not a fix"
