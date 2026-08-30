#!/usr/bin/env bash
# Phone-to-phone demo. No laptop relay — the two phones talk over Nearby Connections
# (BLE discovery, Bluetooth/Wi-Fi Direct data channel). The laptop only *views* the
# responder's board, tunnelled over the USB cable.
#
#   Realme (victim) ──Nearby──► S25 (responder). Board viewed on the laptop at :8081 (adb forward)
#                                      │ adb forward, over USB
#                                      ▼
#                          laptop browser  http://localhost:8081/  (:8080 is the emulation, if running)
#
# Usage:
#   ./run-phone-mesh.sh          set both phones up and open the board
#   ./run-phone-mesh.sh --sos    ... and fire one test SOS from the Realme
#   ./run-phone-mesh.sh --status just report current state, change nothing
#   ./run-phone-mesh.sh --clear  wipe the live board: force-stop both apps (the victim
#                                keeps replaying its SOS otherwise), then bring the S25
#                                responder + board server back up empty
set -uo pipefail
cd "$(dirname "$0")"

VICTIM=3f2d6f59          # Realme RMX3660  (1080x2400)
RESPONDER=RZCY219DWVM    # Samsung S25     (1080x2340)
PKG=org.groundzero.mesh.app
ACT="$PKG/$PKG.ui.MainActivity"

# Tap targets, in real device pixels.
S25_RESPONDER_BTN="689 782"     # role toggle: "responder"
S25_START_SRV_BTN="539 1157"    # "Start responder server"
REALME_SOS_BTN="538 1512"       # "SEND SOS"

say() { printf '%s\n' "$*"; }
prefs() { adb -s "$1" shell run-as $PKG cat /data/data/$PKG/shared_prefs/ground_zero_mesh.xml 2>/dev/null; }

check_device() {
  adb devices | grep -q "^$1[[:space:]]*device$" || { say "  MISSING: $1 not attached (check the USB cable)"; return 1; }
}

status() {
  for D in "$VICTIM" "$RESPONDER"; do
    say "  --- $D ---"
    check_device "$D" || continue
    local rh role
    rh=$(prefs "$D" | grep -o 'relay_host">[^<]*' | cut -d'>' -f2)
    role=$(prefs "$D" | grep -o 'mesh_role">[^<]*' | cut -d'>' -f2)
    say "    role       : ${role:-NODE (default)}"
    say "    relay_host : ${rh:-<blank> = Nearby radio  [correct]}"
    say "    bluetooth  : $(adb -s "$D" shell settings get global bluetooth_on 2>/dev/null | tr -d '\r')"
    say "    wifi       : $(adb -s "$D" shell dumpsys wifi 2>/dev/null | grep -m1 -o 'Wi-Fi is [a-z]*' | tr -d '\r')"
  done
}

[ "${1:-}" = "--status" ] && { status; exit 0; }

if [ "${1:-}" = "--clear" ]; then
  check_device "$RESPONDER" || exit 1
  say "==> force-stop both apps"
  adb -s "$VICTIM"    shell am force-stop $PKG 2>/dev/null
  adb -s "$RESPONDER" shell am force-stop $PKG
  sleep 3
  say "==> S25 -> responder, start board server"
  adb -s "$RESPONDER" shell am start -n "$ACT" >/dev/null 2>&1
  sleep 6
  adb -s "$RESPONDER" shell input tap $S25_RESPONDER_BTN; sleep 3
  adb -s "$RESPONDER" shell input tap $S25_START_SRV_BTN; sleep 4
  adb -s "$RESPONDER" forward tcp:8081 tcp:8080 >/dev/null 2>&1
  n=$(curl -s --max-time 8 http://localhost:8081/snapshot | python3 -c "import sys,json;print(len(json.load(sys.stdin)['clusters']))" 2>/dev/null)
  say "live board: ${n:-?} incidents  (reopen the Realme app when you want to demo again)"
  exit 0
fi

say "==> devices"
check_device "$VICTIM"    || exit 1
check_device "$RESPONDER" || exit 1
say "  victim    $VICTIM (Realme)"
say "  responder $RESPONDER (S25)"

# Nearby needs both radios up on both phones. Airplane mode is fine — and is in fact the
# honest disaster state — as long as BT and Wi-Fi are individually re-enabled.
say "==> radios"
for D in "$VICTIM" "$RESPONDER"; do
  adb -s "$D" shell svc wifi enable  >/dev/null 2>&1
  adb -s "$D" shell svc bluetooth enable >/dev/null 2>&1
done
sleep 4
for D in "$VICTIM" "$RESPONDER"; do
  say "  $D wifi: $(adb -s "$D" shell dumpsys wifi 2>/dev/null | grep -m1 -o 'Wi-Fi is [a-z]*' | tr -d '\r'), bt: $(adb -s "$D" shell settings get global bluetooth_on 2>/dev/null | tr -d '\r')"
done

# A non-blank relay_host silently routes the phone to a laptop TCP relay instead of the
# radio, which is exactly the failure that looks like "the mesh is just not working".
# It cannot be cleared from adb (both ColorOS and One UI block run-as writes and pm clear),
# so this only warns.
say "==> relay_host must be blank for phone-to-phone"
for D in "$VICTIM" "$RESPONDER"; do
  RH=$(prefs "$D" | grep -o 'relay_host">[^<]*' | cut -d'>' -f2)
  if [ -n "$RH" ]; then
    say "  !! $D has relay_host='$RH' — clear that field in the app's first screen, then re-run"
    exit 1
  fi
  say "  $D blank, on the Nearby radio"
done

say "==> restarting both apps"
for D in "$VICTIM" "$RESPONDER"; do adb -s "$D" shell am force-stop $PKG; done
sleep 2
for D in "$VICTIM" "$RESPONDER"; do adb -s "$D" shell am start -n "$ACT" >/dev/null 2>&1; done
sleep 8

# GatewayController lives in memory only, so the board server must be (re)started by hand
# after every app launch — there is no LaunchedEffect that does it.
say "==> S25 -> responder role, start board server"
adb -s "$RESPONDER" shell input tap $S25_RESPONDER_BTN; sleep 3
adb -s "$RESPONDER" shell input tap $S25_START_SRV_BTN; sleep 4

say "==> board tunnel over USB"
adb -s "$RESPONDER" forward --remove-all >/dev/null 2>&1
adb -s "$RESPONDER" forward tcp:8081 tcp:8080 >/dev/null 2>&1

for _ in $(seq 1 15); do curl -sf -o /dev/null --max-time 3 http://localhost:8081/snapshot && break || sleep 1; done
if ! curl -sf -o /dev/null --max-time 4 http://localhost:8081/snapshot; then
  say "  !! board not answering — the 'Start responder server' tap probably missed."
  say "     Look at the phone (scrcpy -s $RESPONDER) and tap it by hand."
  exit 1
fi
say "  board is up"

if [ "${1:-}" = "--sos" ]; then
  say "==> SOS from the Realme"
  adb -s "$VICTIM" shell am start -n "$ACT" >/dev/null 2>&1; sleep 3
  adb -s "$VICTIM" shell input tap $REALME_SOS_BTN
  sleep 6
fi

say ""
say "board: http://localhost:8081/   (toggle to 'emulation' needs ./run-emulation.sh on :8080)"
curl -s --max-time 6 http://localhost:8081/snapshot | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('  advice :',d['advice'])
print('  self   :',d['self'])
for x in d['clusters']:
    print(f\"  incident {x['clusterId']}  {x['severity']}  {x['effectiveTier']}  hops={x['minHops']}  reports={x['reportCount']}\")
" 2>/dev/null
