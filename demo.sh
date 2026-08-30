#!/usr/bin/env bash
# Three-phone demo driver: gateway, relay, victim.
#
# Written for one-phone-at-a-time USB, because that is how the demo box is wired. Every
# command takes a serial, so it does not care which phones are plugged in when.
#
#   ./demo.sh list                              what adb can see right now
#   ./demo.sh setup <serial> GATEWAY            responder: fresh id, serves the board
#   ./demo.sh setup <serial> RELAY              carry-only
#   ./demo.sh setup <serial> NODE               victim
#   ./demo.sh board <serial>                    tunnel the gateway's board to localhost:8081
#   ./demo.sh sos <serial> [drowning|trapped|other]
#   ./demo.sh clear <serial>                    clear that gateway's board
#   ./demo.sh show <serial>                     print the board as text
#   ./demo.sh status <serial>                   role, relay host, radios
#
# `setup` mints a NEW node id every time, on purpose: a demo that reuses yesterday's identity
# shows yesterday's incidents corroborating today's, which is indistinguishable from a bug.
set -uo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash rewrites /sdcard/... into a Windows path otherwise

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
ADB="$SDK/platform-tools/adb.exe"
[ -x "$ADB" ] || ADB="$(command -v adb)"
PKG=org.groundzero.mesh.app
ACT="$PKG/$PKG.ui.MainActivity"
PREFS=shared_prefs/ground_zero_mesh.xml
APK="$(dirname "$0")/app/build/outputs/apk/debug/app-debug.apk"
BOARD_PORT=8081

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

need_device() {
  "$ADB" devices | grep -q "^$1[[:space:]]*device$" \
    || die "  $1 is not attached (unlocked? USB debugging allowed?)"
}

cmd_list() { "$ADB" devices -l; }

# Role + a brand-new identity, written straight into SharedPreferences.
#
# Merged rather than overwritten everywhere it matters, except node_id which we are
# deliberately replacing. gateway_serving is set for a GATEWAY so the board comes back by
# itself after the relaunch, which is what MeshForegroundService.applyRole reads.
cmd_setup() {
  local serial="$1" role="$2"
  case "$role" in NODE|RELAY|GATEWAY) ;; *) die "role must be NODE, RELAY or GATEWAY" ;; esac
  need_device "$serial"

  local newid serving
  newid=$(python3 -c "import random; print(random.randint(0, 0xFFFFFFFFFFFF))")
  serving=false; [ "$role" = GATEWAY ] && serving=true

  "$ADB" -s "$serial" shell am force-stop $PKG
  sleep 1
  # relay_host is deliberately absent: blank means the real Nearby radio, which is the
  # whole point of a phone-to-phone demo. A stale value here silently routes the phone to a
  # laptop TCP relay and the mesh looks dead.
  printf '%s\n' \
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
    "<map>" \
    "    <boolean name=\"gateway_serving\" value=\"$serving\" />" \
    "    <string name=\"mesh_role\">$role</string>" \
    "    <long name=\"node_id\" value=\"$newid\" />" \
    "</map>" \
    | "$ADB" -s "$serial" shell "run-as $PKG sh -c 'cat > $PREFS'"

  # run-as is blocked on some OEM builds; if it silently did nothing, say so now rather
  # than letting the demo fail on stage.
  "$ADB" -s "$serial" shell "run-as $PKG cat $PREFS" 2>/dev/null | grep -q "$role" \
    || die "  could not write prefs on $serial (run-as blocked?) — set the role by hand in the app"

  "$ADB" -s "$serial" shell svc wifi enable >/dev/null 2>&1
  "$ADB" -s "$serial" shell svc bluetooth enable >/dev/null 2>&1
  "$ADB" -s "$serial" shell am start -n "$ACT" >/dev/null 2>&1
  sleep 6
  if "$ADB" -s "$serial" logcat -d -t 300 2>/dev/null | grep -q "FATAL EXCEPTION"; then
    say "  !! crashed after launch:"
    "$ADB" -s "$serial" logcat -d 2>/dev/null | grep -A12 "FATAL EXCEPTION" | tail -14
    exit 1
  fi
  say "  $serial is $role, node id $newid, on the Nearby radio"
  [ "$role" = GATEWAY ] && say "  now: ./demo.sh board $serial"
  return 0
}

cmd_board() {
  local serial="$1"
  need_device "$serial"
  "$ADB" -s "$serial" forward --remove-all >/dev/null 2>&1
  "$ADB" -s "$serial" forward tcp:$BOARD_PORT tcp:8080 >/dev/null
  # The gateway binds its socket well after the Activity is up — service start, role
  # restore, then NanoHTTPD. 15s was not enough on a cold launch and reported a working
  # board as dead.
  # The forward is re-asserted every attempt, not just once up front. On these handsets the
  # USB link re-enumerates and silently drops every forward while `adb devices` still lists
  # the phone (docs/RUNNING.md §5) — polling a tunnel that quietly died is how this reported
  # a perfectly healthy board as dead.
  # Re-added only when it is actually missing. Re-issuing `adb forward` on a spec that
  # already exists tears the tunnel down and rebuilds it, so asserting it every pass meant
  # every poll hit a socket that had just been reset — the same shape as docs/RUNNING.md §5's
  # keep-alive loop, which also tests before it re-adds.
  for _ in $(seq 1 40); do
    "$ADB" -s "$serial" forward --list 2>/dev/null | grep -q "tcp:$BOARD_PORT" \
      || "$ADB" -s "$serial" forward tcp:$BOARD_PORT tcp:8080 >/dev/null 2>&1
    curl -sf --max-time 3 "http://localhost:$BOARD_PORT/snapshot" >/dev/null 2>&1 && break || sleep 1
  done
  if ! curl -sf --max-time 4 "http://localhost:$BOARD_PORT/snapshot" >/dev/null 2>&1; then
    say "  board not answering. Checking why:" >&2
    if ! "$ADB" -s "$serial" shell pidof $PKG >/dev/null 2>&1; then
      die "    the app is not running on $serial — ./demo.sh setup $serial GATEWAY"
    fi
    local serving
    serving=$("$ADB" -s "$serial" shell "run-as $PKG cat $PREFS" 2>/dev/null \
      | grep -o 'gateway_serving" value="[a-z]*' | sed 's/.*"//')
    [ "$serving" = "true" ] \
      && die "    it is serving but unreachable — the USB forward is not holding." \
      || die "    the board server is stopped (gateway_serving=$serving). Someone tapped
    'Stop responder server' on the phone. Fix: ./demo.sh setup $serial GATEWAY"
  fi
  say "  board: http://localhost:$BOARD_PORT/"
}

# Presses the real Compose buttons, found by label in a uiautomator dump. Deliberately not a
# back door: the frame reaching the board came through VictimScreen -> NodeViewModel ->
# NodeAgent.raiseSos like any other, so a row on the board proves the whole path.
tap_label() {
  local serial="$1" label="$2" dump="/tmp/gzm-ui-$serial.xml" coords
  "$ADB" -s "$serial" shell uiautomator dump /sdcard/gzm-ui.xml >/dev/null 2>&1
  "$ADB" -s "$serial" shell cat /sdcard/gzm-ui.xml 2>/dev/null > "$dump"
  coords=$(python3 - "$dump" "$label" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="ignore").read()
want = sys.argv[2].strip().lower()
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
    t, x1, y1, x2, y2 = m.groups()
    if t.strip().lower() == want:
        print((int(x1)+int(x2))//2, (int(y1)+int(y2))//2); break
PY
)
  if [ -z "$coords" ]; then
    say "  '$label' is not on screen. Visible now:" >&2
    grep -oE 'text="[^"]+"' "$dump" 2>/dev/null | sed 's/text=//;s/"//g' | sed '/^$/d;s/^/    /' >&2
    return 1
  fi
  "$ADB" -s "$serial" shell input tap $coords
  sleep 1
}

cmd_sos() {
  local serial="$1" what="${2:-trapped}"
  need_device "$serial"
  "$ADB" -s "$serial" shell am start -n "$ACT" >/dev/null 2>&1
  sleep 4
  tap_label "$serial" victim   || die "  is the app open on $serial?"
  tap_label "$serial" "$what"  || die "  no '$what' button — use drowning, trapped or other"
  tap_label "$serial" "SEND SOS" || die "  no SEND SOS button"
  sleep 3
  if "$ADB" -s "$serial" logcat -d -t 200 2>/dev/null | grep -q "FATAL EXCEPTION"; then
    say "  !! crashed after SEND SOS"; exit 1
  fi
  say "  SOS sent from $serial ($what)"
}

cmd_clear() {
  local serial="$1"
  need_device "$serial"
  "$ADB" -s "$serial" forward tcp:$BOARD_PORT tcp:8080 >/dev/null 2>&1
  curl -sf -X POST --max-time 6 "http://localhost:$BOARD_PORT/clear" >/dev/null \
    || die "  clear failed — is $serial the gateway and serving?"
  say "  board cleared on $serial"
  say "  note: a victim whose incident is still open keeps broadcasting it, but this board"
  say "        now refuses those cleared incidents. A NEW press still shows up."
}

cmd_show() {
  local serial="$1"
  need_device "$serial"
  "$ADB" -s "$serial" forward tcp:$BOARD_PORT tcp:8080 >/dev/null 2>&1
  curl -s --max-time 6 "http://localhost:$BOARD_PORT/snapshot" | python3 -c "
import sys, json
d = json.load(sys.stdin)
print('  advice:', d['advice'])
print('  this device:', (d.get('self') or {}).get('nodeId'))
if not d['clusters']: print('  board is empty')
for c in d['clusters']:
    print(f\"  {c['clusterId']}  {c['severity']}  {c['effectiveTier']}  hops={c['minHops']}  reports={c['reportCount']}\")
for l in d.get('links', []):
    print(f\"  carried by {l['carrier']} -> {l['incidentKey']}\")
" 2>/dev/null || say "  board not answering"
}

cmd_status() {
  local serial="$1" p
  need_device "$serial"
  p=$("$ADB" -s "$serial" shell "run-as $PKG cat $PREFS" 2>/dev/null)
  say "  --- $serial ---"
  # Parsed in python rather than grep|cut: the shell quoting this needed to pull values out
  # of XML attributes is what made it die on `cut: the delimiter must be a single character`.
  printf '%s' "$p" | python3 -c '
import sys, re
x = sys.stdin.read()
def s(n):
    m = re.search(r"<string name=\"%s\">([^<]*)</string>" % n, x); return m.group(1) if m else ""
def b(n):
    m = re.search(r"<boolean name=\"%s\" value=\"([^\"]*)\"" % n, x); return m.group(1) if m else ""
i = re.search(r"name=\"node_id\" value=\"(\d+)\"", x)
print("    role       :", s("mesh_role") or "NODE (default)")
print("    relay_host :", s("relay_host") or "<blank> = Nearby radio  [correct]")
print("    serving    :", b("gateway_serving") or "false")
print("    node id    :", i.group(1) if i else "(none yet)")
' 2>/dev/null || say "    (no prefs yet — has the app been launched on this phone?)"
  say "    bluetooth  : $("$ADB" -s "$serial" shell settings get global bluetooth_on 2>/dev/null | tr -d '\r')"
}

case "${1:-}" in
  list)   cmd_list ;;
  setup)  cmd_setup "${2:?serial}" "${3:?role}" ;;
  board)  cmd_board "${2:?serial}" ;;
  sos)    cmd_sos "${2:?serial}" "${3:-trapped}" ;;
  clear)  cmd_clear "${2:?serial}" ;;
  show)   cmd_show "${2:?serial}" ;;
  status) cmd_status "${2:?serial}" ;;
  install)
    need_device "${2:?serial}"
    "$ADB" -s "$2" install -r -g "$APK" || {
      say "  signature clash — removing the old build first"
      "$ADB" -s "$2" uninstall $PKG >/dev/null 2>&1
      "$ADB" -s "$2" install -g "$APK"
    } ;;
  *) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
