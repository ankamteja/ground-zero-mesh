#!/usr/bin/env bash
# One command for the whole live demo: a real phone raising a real SOS, a laptop relaying
# it, the responder board serving it, and a local model answering questions about it.
#
#   ./demo.sh                    one phone (the plugged-in one) as the victim
#   ./demo.sh --emulators 2      two emulated victims as well, if a field is worth booting
#   ./demo.sh --no-advisor       skip the model, if ollama is not wanted or not installed
#   ./demo.sh reset              wipe the board and raise a fresh SOS, for the next run-through
#   ./demo.sh down               put everything back and stop
#
# Why a script rather than the run-book's steps: the ordering matters and is easy to get
# wrong under an audience. The relay has to be listening before the phone is pointed at it,
# the reverse tunnel has to exist before the app starts, and the board has to be up before
# the advisor tries to read it. Each of those failing looks identical from the outside --
# an empty board -- so every step here waits for a port and says which one did not come up.
#
# What this deliberately does NOT do: fabricate an incident. Every row on the board comes
# from tapping the app's real SOS button through `sos.sh`. A demo that injects frames proves
# the dashboard renders JSON, which was never in doubt.
#
# The phone's own settings are saved before it is repointed at the relay and restored by
# `down`, so a demo does not cost you the role and node id the phone was carrying.
#
# ### GPS: a real fix or nothing
#
# `GpsBridge` asks for `GPS_PROVIDER` and nothing else -- never network location, because a
# cell/Wi-Fi position can be kilometres out and `Envelope.gpsLat` is documented as a real fix
# or nothing. So the coordinate on the board is a genuine satellite lock or it is absent, and
# this script never mocks one: a demo that invents a position is demonstrating the one thing
# the design refuses to do.
#
# The practical consequence: indoors there is usually no lock, and the board honestly shows no
# coordinate. Stand the victim phone by a window or step outside, give it a minute with the
# screen on, and the same run fills in gpsLat/gpsLon by itself. `gps_state` below reports
# which of those you are in, so an empty coordinate is never a mystery mid-demo.
#
# A fix does not place the incident on the 3D board -- that is `zone`/`floor`, which a
# responder enters -- so expect a row that is "unplaced" and still carries coordinates.
set -uo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
run="$here/.run"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="$sdk/platform-tools/adb"
pkg=org.groundzero.mesh.app
prefs=shared_prefs/ground_zero_mesh.xml
mkdir -p "$run"

relay_port="${FIELD_RELAY_PORT:-7802}"
board_port="${FIELD_BOARD_PORT:-8080}"
advisor_port="${FIELD_ADVISOR_PORT:-8787}"
model="${FIELD_MODEL:-qwen3:8b}"
saved="$run/demo-prefs.xml"

emulators=0
advisor=1
action=up
while [[ $# -gt 0 ]]; do
    case "$1" in
        down)         action=down; shift ;;
        reset)        action=reset; shift ;;
        --emulators)  emulators="${2:-2}"; shift 2 ;;
        --no-advisor) advisor=0; shift ;;
        *)            echo "demo: unknown argument '$1'" >&2; exit 2 ;;
    esac
done

# The physical phone, never an emulator: it is the one carrying settings worth restoring.
phone="${FIELD_VICTIM:-$("$adb" devices | awk '$2 == "device" && $1 !~ /^emulator-/ {print $1; exit}')}"

listening() { ss -ltn 2>/dev/null | grep -q ":$1[[:space:]]"; }

# Waits rather than sleeps: a cold Gradle daemon takes far longer than a warm one, and a
# fixed sleep is either a slow demo or a flaky one.
await() {
    local port="$1" what="$2" log="$3" i
    for ((i = 0; i < 60; i++)); do
        listening "$port" && { echo "demo: $what up on $port"; return 0; }
        sleep 2
    done
    echo "demo: $what never bound $port -- last of $log:" >&2
    tail -15 "$log" >&2
    return 1
}

start() {  # start <name> <logfile> <gradle args...>
    local name="$1" log="$2"; shift 2
    setsid nohup "$root/gradlew" "$@" > "$log" 2>&1 &
    echo "$!" > "$run/demo-$name.pid"
}

emulator_serials() { "$adb" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1}'; }

# Says which of the two GPS situations you are in, before the board does. Reports only
# whether a lock exists -- printing the coordinate is the board's job, not a log's.
gps_state() {
    local enabled providers
    enabled="$("$adb" -s "$phone" shell settings get secure location_mode 2>/dev/null | tr -d '\r')"
    if [[ "$enabled" == "0" ]]; then
        echo "demo: location is switched off on $phone -- turn it on for a coordinate"
        return
    fi
    providers="$("$adb" -s "$phone" shell dumpsys location 2>/dev/null | grep -A3 'last location' | head -8)"
    if echo "$providers" | grep -q 'gps'; then
        echo "demo: $phone has a GPS lock -- the board will carry real coordinates"
    else
        echo "demo: $phone has no GPS lock yet (indoors is normal, and the app refuses to guess)"
        echo "demo:   put it by a window or outside for a minute; the row fills in on the next SOS"
    fi
}

# Every incident on the board is a real button press. Logcat is cleared first so `sos.sh`'s
# crash check reads this run's log rather than an older FATAL from a previous one.
raise_all() {
    # A phone left alone between run-throughs has gone to sleep, and `sos.sh` looks for its
    # buttons by label in a uiautomator dump -- on a lock screen it finds "Emergency calls
    # only" and stops. Waking first is what makes `reset` repeatable in front of an audience.
    # KEYCODE_MENU dismisses a swipe-only lock; a phone with a PIN has to be opened by hand.
    "$adb" -s "$phone" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
    "$adb" -s "$phone" shell input keyevent KEYCODE_MENU   > /dev/null 2>&1
    if "$adb" -s "$phone" shell dumpsys window 2>/dev/null | grep -q 'mDreamingLockscreen=true'; then
        echo "demo: $phone is still locked -- unlock it once and re-run" >&2
        return 1
    fi
    gps_state
    "$adb" -s "$phone" logcat -c > /dev/null 2>&1
    "$here/sos.sh" "$phone" drowning || { echo "demo: the phone did not send -- see above" >&2; return 1; }
    local serial
    for serial in $(emulator_serials); do
        "$here/sos.sh" "$serial" trapped || echo "demo: $serial did not send (continuing)" >&2
    done
}

show_board() {
    # An SOS is not on the board the instant `sos.sh` returns: the frame still has to cross
    # the relay and be ingested. Printing immediately shows "0 cluster(s)" for a run that is
    # about to work, which is the most misleading thing this script could say. Wait for the
    # first cluster, briefly, and print whatever is true after that.
    local i
    for ((i = 0; i < 15; i++)); do
        curl -s --max-time 3 "http://127.0.0.1:$board_port/snapshot" \
            | grep -q '"clusters":\[{' && break
        sleep 1
    done
    echo
    echo "=== board ==="
    # Python fetches it rather than reading a pipe: `python3 - <<PY` already spends stdin on
    # the program itself, so a piped body never arrives.
    BOARD_URL="http://127.0.0.1:$board_port/snapshot" python3 - <<'PY'
import json, os, sys, urllib.request
try:
    with urllib.request.urlopen(os.environ["BOARD_URL"], timeout=5) as r:
        clusters = json.load(r).get("clusters", [])
except Exception as e:
    print(f"  the board did not answer ({e})")
    sys.exit(0)
print(f"  {len(clusters)} cluster(s)")
for c in clusters:
    lat, lon = c.get("gpsLat"), c.get("gpsLon")
    # No fix is a real state to report, not a blank: it means the phone had no lock.
    where = f"{lat:.5f},{lon:.5f}" if lat is not None and lon is not None else "no GPS lock"
    print("  {origin}  {severity}  reports {reports}  hops {hops}  {where}".format(
        origin=c.get("origin"), severity=c.get("severity"),
        reports=c.get("reportCount"), hops=c.get("minHops"), where=where))
PY
}

# ------------------------------------------------------------------------------------ down

if [[ "$action" == down ]]; then
    if [[ -n "$phone" ]] && [[ -s "$saved" ]]; then
        "$adb" -s "$phone" shell am force-stop "$pkg" > /dev/null 2>&1
        "$adb" -s "$phone" shell "run-as $pkg sh -c 'cat > $prefs'" < "$saved"
        echo "demo: $phone restored to"
        "$adb" -s "$phone" shell "run-as $pkg cat $prefs" | sed 's/^/  /'
        rm -f "$saved"
    fi
    [[ -n "$phone" ]] && "$adb" -s "$phone" reverse --remove "tcp:$relay_port" > /dev/null 2>&1
    pkill -f 'TcpRelayMain'  > /dev/null 2>&1
    pkill -f 'GwHeadlessKt'  > /dev/null 2>&1
    pkill -f 'AdvisorMain'   > /dev/null 2>&1
    rm -f "$run"/demo-*.pid
    sleep 2
    echo "demo: down (ollama and any emulators are left running -- ./field.sh down for those)"
    exit 0
fi

# ----------------------------------------------------------------------------------- reset

# There is no "clear" button on a live board, and there should not be: a responder cannot be
# able to make real reports disappear. The board's contents are the gateway process's own
# gossip state -- `Gossip` holds clusters in memory and a gateway only ever shows what
# arrived after it connected -- so restarting that one process is what an empty board means.
# The relay keeps running, the phone keeps its node id and its place in the mesh, and the
# advisor keeps its model loaded, which is why this is seconds rather than a fresh `up`.
#
# The new SOS lands as a *new* cluster rather than a resurrected one: clusters key on
# origin@firstSeen, so the same phone raising a second alarm is a second incident. The relay's
# `duplicates=` counter ticking up while the board fills is that working, not a fault.
if [[ "$action" == reset ]]; then
    listening "$board_port" || { echo "demo: nothing is running on $board_port -- ./demo.sh first" >&2; exit 1; }
    [[ -n "$phone" ]] || { echo "demo: no phone attached to raise a fresh SOS" >&2; exit 1; }

    pkill -f 'GwHeadlessKt' > /dev/null 2>&1
    for ((i = 0; i < 15; i++)); do listening "$board_port" || break; sleep 1; done
    listening "$board_port" && { echo "demo: the old board would not let go of $board_port" >&2; exit 1; }
    echo "demo: board cleared"

    start board "$run/gw.log" :app:runHeadlessGateway --offline -q \
        -PgwArgs="127.0.0.1 $relay_port $board_port"
    await "$board_port" board "$run/gw.log" || exit 1

    raise_all || exit 1
    show_board
    echo
    echo "open  http://localhost:$board_port/    (reload the page -- its stream died with the old board)"
    exit 0
fi

# -------------------------------------------------------------------------------------- up

[[ -n "$phone" ]] || {
    echo "demo: no physical phone attached. Plug one in, or set FIELD_VICTIM=<serial>." >&2
    exit 1
}
"$adb" -s "$phone" shell pm path "$pkg" > /dev/null 2>&1 || {
    echo "demo: $pkg is not installed on $phone -- ./responder.sh install" >&2
    exit 1
}

echo "demo: victim phone $phone, relay $relay_port, board $board_port"

# Saved before anything is changed, so `down` can hand the phone back as it was found.
"$adb" -s "$phone" shell "run-as $pkg cat $prefs" > "$saved" 2>/dev/null
[[ -s "$saved" ]] && echo "demo: saved this phone's settings to $saved"

start relay "$run/relay.log" :core:runRelay --offline -q -PrelayArgs="$relay_port"
await "$relay_port" relay "$run/relay.log" || exit 1

# A reverse tunnel, not a LAN address: it is the one path that works on any network, needs
# no hotspot, and cannot be broken by the venue's Wi-Fi. The phone dials its own loopback.
"$adb" -s "$phone" reverse "tcp:$relay_port" "tcp:$relay_port" > /dev/null
"$here/configure.sh" "$phone" NODE "127.0.0.1:$relay_port" > /dev/null
echo "demo: $phone is a victim node pointed at the relay"

start board "$run/gw.log" :app:runHeadlessGateway --offline -q \
    -PgwArgs="127.0.0.1 $relay_port $board_port"
await "$board_port" board "$run/gw.log" || exit 1

if [[ "$advisor" == 1 ]]; then
    # The advisor is optional on purpose -- the board is complete without it -- so a missing
    # ollama downgrades the demo instead of ending it.
    if ! curl -sf --max-time 3 http://localhost:11434/api/tags > /dev/null 2>&1; then
        if command -v ollama > /dev/null 2>&1; then
            setsid nohup ollama serve > "$run/ollama.log" 2>&1 &
            for ((i = 0; i < 15; i++)); do
                curl -sf --max-time 2 http://localhost:11434/api/tags > /dev/null 2>&1 && break
                sleep 2
            done
        fi
    fi
    if curl -sf --max-time 3 http://localhost:11434/api/tags > /dev/null 2>&1; then
        start advisor "$run/advisor.log" :core:runAdvisor --offline -q \
            -PadvisorArgs="--model $model --gateway http://localhost:$board_port --port $advisor_port"
        await "$advisor_port" advisor "$run/advisor.log" \
            && echo "demo: advisor answering on $advisor_port (the board's panel finds it there)"
    else
        echo "demo: no ollama on 11434 -- board runs without the advisor panel"
    fi
fi

if [[ "$emulators" -gt 0 ]]; then
    echo "demo: booting $emulators emulated victim(s) -- this is the slow part"
    "$here/field.sh" up "$emulators"
    "$here/field.sh" install
    for serial in $(emulator_serials); do
        # 10.0.2.2 is the host as seen from inside an emulator; the phone's 127.0.0.1 is a
        # tunnel. Different addresses, same relay.
        "$here/configure.sh" "$serial" NODE "10.0.2.2:$relay_port" > /dev/null
    done
fi

raise_all || exit 1
show_board

echo
echo "open   http://localhost:$board_port/"
[[ "$advisor" == 1 ]] && echo "ask    curl -s \"localhost:$advisor_port/ask?q=who+do+I+send+the+boat+to+first\""
echo "watch  tail -f $run/relay.log"
echo "again  $here/demo.sh reset    (empty board, fresh SOS, everything else left up)"
echo "stop   $here/demo.sh down     (restores the phone's own settings)"
