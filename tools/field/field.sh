#!/usr/bin/env bash
# Bring up a field of virtual phones that share one radio medium.
#
# Every emulator started on this host attaches its virtual BLE / Bluetooth /
# Wi-Fi controllers to a single netsimd process, so N emulators are N radios in
# one scene rather than N isolated machines. netsimctl.py then places them and
# walks them around; see README.md for what that does and does not prove.
#
#   ./field.sh up 2          boot two phones (headless)
#   ./field.sh status        adb + netsim view of the field
#   ./field.sh install       install the debug APK on every booted phone
#   ./field.sh geo <serial> <lon> <lat>   move one phone (real GPS)
#   ./field.sh down          stop them
#
# The AVD must be a Google Play system image, or Nearby has no Play services to
# run on. Two phones on this 16 GB laptop is the comfortable limit -- each costs
# about 2.8 GB of host RSS at the default 1536 MB of guest RAM, and a third gets
# the others OOM-killed mid-boot.
#
# One AVD serves every instance: `-read-only` lets several emulators share one
# AVD image (each gets its own overlay), so there is no need to clone a 7 GB AVD
# per phone. The cost is that snapshots cannot be saved, which does not matter
# for a disposable field.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
emulator="$sdk/emulator/emulator"
adb="$sdk/platform-tools/adb"
avd="${FIELD_AVD:-}"
# -gpu host needs a display to render against, even headless.
export DISPLAY="${DISPLAY:-:0}"
# Guest RAM. Measured cost on the host is roughly 2.8 GB per phone at this
# setting, so two fit on a 16 GB laptop and three do not.
mem="${FIELD_MEM_MB:-1536}"
run="$here/.run"

die() { echo "field: $*" >&2; exit 1; }

pick_avd() {
    [[ -n "$avd" ]] && return
    avd="$("$emulator" -list-avds | head -1)"
    [[ -n "$avd" ]] || die "no AVD found - create one in Android Studio, or set FIELD_AVD"
}

up() {
    local count="${1:-3}"
    pick_avd
    mkdir -p "$run"
    echo "field: $count phones on AVD $avd, ${mem}MB each"

    local i port
    for ((i = 0; i < count; i++)); do
        port=$((5554 + i * 2))
        if "$adb" devices | grep -q "emulator-$port"; then
            echo "  emulator-$port already up, leaving it alone"
            continue
        fi
        # -read-only: many instances, one AVD image.
        # -no-snapshot-*: always a cold, identical device; a field run should not
        #   inherit whatever state the last run left behind.
        # -netsim-args --pcap: every virtual frame is captured for Wireshark.
        #
        # setsid, because a phone should outlive the shell that started it: a
        # bare background child dies with its parent's SIGHUP, which silently
        # kills the emulator mid-boot when this script is itself run detached.
        # -camera-back/front none: without them the emulator loads its virtual
        #   camera scene (a 3D mesh, Toren1BD.obj) and does GPU work no headless
        #   field run has any use for.
        # -gpu host, and NOT swiftshader: SwiftShader JIT-compiles shaders onto
        #   the heap and executes them, which SELinux denies (`execheap` AVC on
        #   the RenderThread) on an enforcing host like Fedora -- the emulator
        #   then segfaults ~24s into boot, every time. Rendering on the real GPU
        #   never loads that JIT. If you must use SwiftShader on such a host,
        #   the alternative is `setsebool -P selinuxuser_execheap on`, which
        #   loosens the policy for every unconfined process and is a worse
        #   trade than simply using the GPU that is already there.
        setsid "$emulator" -avd "$avd" -read-only -port "$port" \
            -no-window -no-audio -no-boot-anim \
            -gpu host \
            -camera-back none -camera-front none \
            -no-snapshot-load -no-snapshot-save \
            -memory "$mem" -cores 2 \
            -netsim-args "--pcap" \
            > "$run/emulator-$port.log" 2>&1 &
        echo $! > "$run/emulator-$port.pid"
        echo "  emulator-$port starting (pid $!)"
    done

    for ((i = 0; i < count; i++)); do
        port=$((5554 + i * 2))
        echo -n "  waiting for emulator-$port to finish booting"
        "$adb" -s "emulator-$port" wait-for-device
        until [[ "$("$adb" -s "emulator-$port" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
            echo -n "."
            sleep 3
        done
        echo " booted"
    done
    status
}

status() {
    echo "--- adb ---"
    "$adb" devices | sed '1d;/^$/d'
    echo "--- netsim ---"
    "$here/netsimctl.py" list || true
}

install_apk() {
    local apk="$repo/app/build/outputs/apk/debug/app-debug.apk"
    [[ -f "$apk" ]] || die "no APK at $apk - build it with ./gradlew :app:assembleDebug"
    local serial
    for serial in $("$adb" devices | awk '/^emulator-/ {print $1}'); do
        echo "field: installing on $serial"
        "$adb" -s "$serial" install -r -g "$apk"
    done
}

# Real GPS, injected per phone through the emulator console. Until a netsimd with
# a control plane is on the path (see README), this is the distance knob that
# actually works: everything in the app that reasons about where a node is --
# zones, incident location, the responder board's map -- follows it.
geo() {
    local serial="${1:?usage: field.sh geo <serial> <lon> <lat>}"
    local lon="${2:?usage: field.sh geo <serial> <lon> <lat>}"
    local lat="${3:?usage: field.sh geo <serial> <lon> <lat>}"
    "$adb" -s "$serial" emu geo fix "$lon" "$lat"
    echo "field: $serial at lon=$lon lat=$lat"
}

down() {
    local serial
    for serial in $("$adb" devices | awk '/^emulator-/ {print $1}'); do
        echo "field: stopping $serial"
        "$adb" -s "$serial" emu kill || true
    done
    rm -rf "$run"
}

case "${1:-}" in
    up)      shift; up "$@" ;;
    down)    down ;;
    status)  status ;;
    geo)     shift; geo "$@" ;;
    install) install_apk ;;
    *) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
