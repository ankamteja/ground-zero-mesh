#!/usr/bin/env bash
# Is the demo actually working, and if not, which part is broken?
#
#   ./check.sh          report
#   ./check.sh --fix    re-establish what can be re-established, then report
#
# Written because the failure that costs the most time is the quiet one. An
# `adb reverse` tunnel dies whenever the adb server restarts -- which killing an
# emulator with pkill is enough to do -- and nothing re-creates it. Every visible
# sign stays healthy: the phone still shows as a device, its app is still running,
# the relay is still listening, the board still serves. The only symptom is a
# board that stops changing, which reads as "nothing is happening" rather than
# "this phone has no route to the mesh".
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
adb="${ADB:-adb}"
pkg=org.groundzero.mesh.app
relay_port="${FIELD_RELAY_PORT:-7802}"
board_port="${FIELD_BOARD_PORT:-8080}"
advisor_port="${FIELD_ADVISOR_PORT:-8787}"

fix=0
[[ "${1-}" == "--fix" ]] && fix=1

problems=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  BAD   $*"; problems=$((problems + 1)); }
note() { echo "        $*"; }

listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

echo "=== services ==="
for spec in "$relay_port:relay" "$board_port:board" "$advisor_port:advisor"; do
    port="${spec%%:*}"; name="${spec##*:}"
    if listening "$port"; then ok "$name on $port"; else bad "$name is not listening on $port"; fi
done
if curl -sf --max-time 3 "http://127.0.0.1:11434/api/tags" > /dev/null 2>&1; then
    ok "ollama on 11434"
else
    bad "ollama is not answering on 11434 -- the advisor panel will stay empty"
    note "start it with: ollama serve"
fi

echo
echo "=== devices ==="
mapfile -t serials < <("$adb" devices | awk '$2 == "device" {print $1}')
[[ ${#serials[@]} -gt 0 ]] || bad "no device is attached"
for serial in "${serials[@]}"; do
    if [[ "$serial" == emulator-* ]]; then
        kind="emulator"
    else
        kind="phone"
    fi
    if "$adb" -s "$serial" shell pidof "$pkg" > /dev/null 2>&1; then
        ok "$serial ($kind) -- app running"
    else
        bad "$serial ($kind) -- app is not running"
    fi
done

echo
echo "=== how each device reaches the relay ==="
for serial in "${serials[@]}"; do
    host="$("$adb" -s "$serial" shell "run-as $pkg cat shared_prefs/ground_zero_mesh.xml" 2>/dev/null \
        | grep -o '<string name="relay_host">[^<]*' | sed 's/.*>//')"
    case "$serial" in
        emulator-*)
            # An emulator dials the host directly; 10.0.2.2 is the laptop from inside it.
            if [[ "$host" == 10.0.2.2:* ]]; then ok "$serial -> $host"; else bad "$serial has relay_host='$host', expected 10.0.2.2:$relay_port"; fi
            ;;
        *)
            # A phone dials its own loopback and adb reverse carries it to the laptop.
            if [[ "$host" != 127.0.0.1:* ]]; then
                bad "$serial has relay_host='$host', expected 127.0.0.1:$relay_port"
                continue
            fi
            if "$adb" -s "$serial" reverse --list 2>/dev/null | grep -q "tcp:$relay_port tcp:$relay_port"; then
                ok "$serial -> $host via adb reverse"
            else
                bad "$serial has no adb reverse tunnel -- it cannot reach the relay at all"
                note "tunnels die with the adb server; killing an emulator is enough to do it"
                if [[ "$fix" == 1 ]]; then
                    "$adb" -s "$serial" reverse "tcp:$relay_port" "tcp:$relay_port" > /dev/null 2>&1 \
                        && note "fixed: tunnel re-created. Restart the app so it re-dials:" \
                        || note "could not re-create the tunnel"
                    note "  $adb -s $serial shell am force-stop $pkg && $here/sos.sh $serial drowning"
                else
                    note "re-run with --fix to re-create it"
                fi
            fi
            ;;
    esac
done

echo
echo "=== board ==="
if snapshot="$(curl -sf --max-time 5 "http://127.0.0.1:$board_port/snapshot" 2>/dev/null)"; then
    SNAP="$snapshot" python3 - <<'PY'
import json, os
d = json.loads(os.environ["SNAP"])
clusters = d.get("clusters", [])
print(f"  ok    {len(clusters)} incident(s)")
for c in clusters:
    zone = c.get("zone") or "unset"
    src = c.get("gpsSource") or "no position"
    seen = c.get("lastSeenSecondsAgo")
    stale = "  <- stale, nothing heard recently" if isinstance(seen, int) and seen > 300 else ""
    print(f"        {c.get('origin')}  {c.get('severity')}  {zone}  {src}  "
          f"{c.get('reportCount')} report(s)  last heard {seen}s ago{stale}")
PY
else
    bad "the board is not answering on $board_port"
fi

echo
echo "=== memory ==="
awk '/MemAvailable/ {printf "  %s MB available\n", int($2/1024)} /SwapFree/ {sf=$2} /SwapTotal/ {st=$2} END {if (st > 0) printf "  swap %d of %d MB used\n", (st-sf)/1024, st/1024}' /proc/meminfo

echo
if [[ "$problems" -eq 0 ]]; then
    echo "check: everything is up. Board http://localhost:$board_port/"
else
    echo "check: $problems problem(s) above."
    [[ "$fix" == 0 ]] && echo "check: --fix re-creates what it safely can."
fi
exit 0
