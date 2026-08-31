#!/usr/bin/env bash
# One command for the whole live demo: a real phone raising a real SOS, a laptop relaying
# it, the responder board serving it, and a local model answering questions about it.
#
#   ./demo.sh                    one phone (the plugged-in one) as the victim
#   ./demo.sh --emulators 2      two emulated victims as well, if a field is worth booting
#   ./demo.sh --show 2           THE DEMO: two emulated victims on screen, your phone as the
#                                responder computing and serving the board itself
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
#
# ### --show, and why it is the one worth demonstrating
#
# The default puts the board on the laptop, which is convenient and proves the mesh but not
# the claim. `--show` inverts it: the emulators are the victims, and **your phone is the
# responder** -- it holds the gossip state, runs the ranking, and serves the dashboard from
# its own NanoHTTPD. The laptop is reduced to a relay and a screen, and the board you point
# at during the demo was computed on a handset.
#
# The emulators come up windowed via `mirror.sh`, so an audience watches a real Compose SOS
# button being pressed on one phone and the row appearing on another. Nothing is injected.
#
# netsim's radio scene is host-local, so a USB phone cannot join the emulators over Nearby.
# The laptop relay is the join: emulators reach it on 10.0.2.2, the phone reaches it through
# an `adb reverse` tunnel on its own loopback. Different addresses, one relay, one mesh.
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
show=0
phone_board_port="${FIELD_PHONE_BOARD_PORT:-8099}"
# 1024, not field.sh's 1536: two emulators plus a relay plus scrcpy has to fit beside a
# desktop on a 16 GB laptop, and the app is not what needs the extra half gig.
mem_mb="${FIELD_MEM_MB:-1024}"
while [[ $# -gt 0 ]]; do
    case "$1" in
        down)         action=down; shift ;;
        reset)        action=reset; shift ;;
        --emulators)  emulators="${2:-2}"; shift 2 ;;
        --show)       show=1; emulators="${2:-2}"; shift 2 ;;
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

# Refuse a run that cannot fit, before it costs five minutes and takes the desktop with it.
#
# An emulator that runs out of memory does not fail politely: the kernel kills whatever it
# reaches first, which on a 16 GB laptop has meant the browser, the Gradle daemon, and both
# emulators at once. Checking first is the difference between "not enough memory, close a
# browser" and a demo that dies in front of an audience.
#
# Available, not free: the kernel will hand back page cache under pressure, so "free" alone
# understates what a run can actually have.
require_memory_for() {
    local count="$1" need available
    # Per emulator: its own RAM plus roughly 400 MB of qemu, and ~1.2 GB for the relay,
    # the Gradle daemon that builds it and the scrcpy windows.
    need=$(( count * (mem_mb + 400) + 1200 ))
    available=$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo)
    echo "demo: $count emulator(s) at ${mem_mb} MB needs about ${need} MB; ${available} MB available"
    if [[ "$available" -lt "$need" ]]; then
        echo "demo: not enough memory. Close a browser, or lower it:" >&2
        echo "demo:   FIELD_MEM_MB=1024 $0 --show $count      (or run --show 1)" >&2
        return 1
    fi
}

# The Gradle daemon is ~1.5 GB doing nothing once the build is done, and the demo needs that
# more than it needs a warm daemon.
free_build_memory() {
    "$root/gradlew" --stop > /dev/null 2>&1 || true
    echo "demo: stopped the Gradle daemon to leave that memory to the emulators"
}

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

# Press "Start responder server" on the phone.
#
# Nothing starts the board from a role change: GatewayController.start is wired to that
# button and only to it, so a phone configured headlessly as GATEWAY holds the mesh state
# but serves nothing, and the laptop gets "Connection refused" on the forwarded port with
# no clue why. Tapped rather than worked around, for sos.sh's reason -- the demo should go
# through the same surface a responder uses.
serve_board_on_phone() {
    local dump="$run/ui-$phone.xml" coords
    "$adb" -s "$phone" shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
    "$adb" -s "$phone" shell monkey -p "$pkg" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1
    sleep 8

    refresh_ui() {
        "$adb" -s "$phone" shell uiautomator dump /sdcard/gzm-ui.xml > /dev/null 2>&1
        "$adb" -s "$phone" shell cat /sdcard/gzm-ui.xml > "$dump" 2>/dev/null
    }
    tap_text() {  # tap_text <label> -> 0 if found and tapped
        local at
        at="$(python3 - "$dump" "$1" <<'PY'
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
)"
        [[ -n "$at" ]] || return 1
        "$adb" -s "$phone" shell input tap $at
        sleep 2
        refresh_ui
    }

    refresh_ui
    # The role in prefs is what the *stack* comes up as; the UI still opens on whichever
    # screen its own tile selection shows, which after a fresh launch is the victim one.
    # Tapping the tile is what puts the responder screen -- and its start button -- on
    # screen at all, and it is what a person standing at the phone would do.
    if ! grep -qE 'Start responder server|Stop responder server' "$dump" 2>/dev/null; then
        tap_text responder || {
            echo "demo: no 'responder' tile on $phone -- is the app on its main screen?" >&2
            return 1
        }
    fi

    if grep -q 'Stop responder server' "$dump" 2>/dev/null; then
        echo "demo: the phone is already serving its board"
        return 0
    fi
    if ! tap_text "Start responder server"; then
        echo "demo: could not find 'Start responder server' on $phone." >&2
        echo "demo: labels on screen:" >&2
        grep -oE 'text="[^"]+"' "$dump" 2>/dev/null | sed 's/text=//;s/"//g' | sed 's/^/  /' | head -14 >&2
        return 1
    fi
    echo "demo: pressed 'Start responder server' on $phone"
    sleep 2
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
    [[ -n "$phone" ]] && "$adb" -s "$phone" forward --remove "tcp:$phone_board_port" > /dev/null 2>&1
    # scrcpy windows belong to this demo; the emulators may not, so they are left to
    # field.sh down, which is the thing that knows it started them.
    pkill -f 'scrcpy.*emulator-' > /dev/null 2>&1
    pkill -f 'TcpRelayMain'  > /dev/null 2>&1
    pkill -f 'GwHeadlessKt'  > /dev/null 2>&1
    pkill -f 'AdvisorMain'   > /dev/null 2>&1
    rm -f "$run"/demo-*.pid
    sleep 2
    echo "demo: down. Emulators are still up on purpose -- they cost minutes to boot and a"
    echo "demo: second run-through usually wants them. ./field.sh down when you are finished."
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

# ------------------------------------------------------------------------------ show

if [[ "$show" == 1 ]]; then
    echo "demo: $emulators emulated victim(s), $phone as the responder serving the board"
    require_memory_for "$emulators" || exit 1

    "$adb" -s "$phone" shell "run-as $pkg cat $prefs" > "$saved" 2>/dev/null
    [[ -s "$saved" ]] && echo "demo: saved this phone's settings to $saved"

    # Before the relay, never after: `gradlew --stop` stops every daemon of this Gradle
    # version, and :core:runRelay is a build that lives inside one. Stopping afterwards
    # killed the relay it had just started -- nothing listening on the port, every SOS
    # dropped, and a board that stayed empty while the tunnels all looked healthy.
    free_build_memory

    start relay "$run/relay.log" :core:runRelay --offline -q -PrelayArgs="$relay_port"
    await "$relay_port" relay "$run/relay.log" || exit 1

    echo "demo: booting $emulators emulator(s) -- the slow part, several minutes from cold"
    FIELD_MEM_MB="$mem_mb" "$here/field.sh" up "$emulators" || exit 1
    FIELD_MEM_MB="$mem_mb" "$here/field.sh" install || exit 1

    mapfile -t victims < <(emulator_serials)
    [[ ${#victims[@]} -gt 0 ]] || { echo "demo: no emulator came up" >&2; exit 1; }

    # Where each emulated victim says it is: plan units and a zone from
    # app/src/main/assets/siteplan/plan.txt, one per emulator, in boot order.
    victim_marks=(
        "230 170 block-a-north"
        "750 430 block-b-west"
        "480 400 courtyard"
        "490 670 car-park"
    )

    i=0
    for serial in "${victims[@]}"; do
        # 10.0.2.2 is the host as seen from inside an emulator.
        "$here/configure.sh" "$serial" NODE "10.0.2.2:$relay_port" > /dev/null

        # An emulator ships a synthetic GPS provider that reports a Californian
        # coordinate whether or not anything is there. Left on, that arrives at the
        # board as FixSource.SATELLITE -- a measurement's authority behind a number
        # nobody measured, and betterFix() would rightly let it outrank the mark
        # below. Switching it off is the honest state: this device cannot see a
        # satellite, so it says where it is instead of pretending to know.
        "$adb" -s "$serial" shell settings put secure location_mode 0 > /dev/null 2>&1 || true

        # Marks are re-applied every run on purpose: the AVD boots -read-only with
        # -no-snapshot-save, so a phone's prefs do not survive its shutdown.
        if [[ $i -lt ${#victim_marks[@]} ]]; then
            # shellcheck disable=SC2086
            "$here/mark.sh" "$serial" ${victim_marks[$i]} > /dev/null
            echo "demo: $serial is a victim node, marked in ${victim_marks[$i]##* }"
        else
            echo "demo: $serial is a victim node (unplaced -- no mark left in the list)"
        fi
        i=$((i + 1))
    done

    # The phone dials its own loopback; adb reverse carries that to the laptop relay.
    "$adb" -s "$phone" reverse "tcp:$relay_port" "tcp:$relay_port" > /dev/null
    "$here/configure.sh" "$phone" GATEWAY "127.0.0.1:$relay_port" > /dev/null
    echo "demo: $phone is the responder, pointed at the relay"

    # Relaunches (configure.sh force-stopped it) and presses the button that starts serving.
    serve_board_on_phone || exit 1

    # The phone's own board, read from the laptop. forward, not reverse: this direction is
    # the laptop asking the phone, which is the opposite of how the phone reaches the relay.
    "$adb" -s "$phone" forward "tcp:$phone_board_port" tcp:8080 > /dev/null
    for ((i = 0; i < 20; i++)); do
        curl -sf --max-time 2 "http://127.0.0.1:$phone_board_port/" > /dev/null 2>&1 && break
        sleep 1
    done
    if curl -sf --max-time 3 "http://127.0.0.1:$phone_board_port/" > /dev/null 2>&1; then
        echo "demo: the phone is serving its board, readable at http://localhost:$phone_board_port/"
    else
        echo "demo: the phone is not answering on $phone_board_port -- is the server started?" >&2
        exit 1
    fi

    "$here/mirror.sh" || echo "demo: mirroring failed -- the run is fine, it is just not on screen" >&2

    # Everything above can look healthy while the relay is gone: the tunnels stay open, the
    # phone keeps serving, and the only symptom is a board that never fills. Say so here
    # rather than letting an empty board be discovered in front of an audience.
    listening "$relay_port" || {
        echo "demo: the relay died after starting -- nothing is listening on $relay_port." >&2
        echo "demo: no SOS can reach the board. Last of $run/relay.log:" >&2
        tail -15 "$run/relay.log" >&2
        exit 1
    }

    for serial in "${victims[@]}"; do
        "$adb" -s "$serial" logcat -c > /dev/null 2>&1
    done
    "$here/sos.sh" "${victims[0]}" drowning || exit 1
    [[ ${#victims[@]} -gt 1 ]] && { "$here/sos.sh" "${victims[1]}" trapped || true; }

    board_port="$phone_board_port"   # so show_board reads the phone, not the laptop
    show_board

    echo
    echo "open   http://localhost:$phone_board_port/     <- served by $phone, not this laptop"
    echo "again  $here/sos.sh ${victims[0]} drowning"
    echo "stop   $here/demo.sh down"
    exit 0
fi

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
