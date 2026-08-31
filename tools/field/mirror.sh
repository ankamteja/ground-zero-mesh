#!/usr/bin/env bash
# Put the virtual phones on screen, so a run can be demonstrated rather than described.
#
# `field.sh` boots emulators with `-no-window`: a field is normally something you
# drive from scripts and read on the responder board, and a headless phone costs
# noticeably less RAM. For a demo you want to *see* the app react, which is what
# this does -- without rebooting the field, and without giving up the headless
# default that makes two phones fit on a 16 GB laptop.
#
#   ./mirror.sh                     every booted emulator, tiled
#   ./mirror.sh --all               every attached device, the physical phone too
#   ./mirror.sh emulator-5554       one of them
#   ./mirror.sh RZCY219DWVM         a real phone, if it is not mirrored already
#
# scrcpy rather than the emulator's own window because it is the same tool for
# the emulators and for the physical phone, so every device in the demo looks
# and behaves the same on screen. It mirrors over adb, so a phone already
# attached needs nothing new.
#
# Windows are tiled left to right and titled with the serial, because the whole
# point of the demo is which phone did what -- three identical unlabelled phone
# windows are worse than none.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="$sdk/platform-tools/adb"
run="$here/.run"
mkdir -p "$run"

# The vendored scrcpy the phone tooling already uses, else whatever is on PATH.
scrcpy="${SCRCPY:-$HOME/tools/scrcpy/scrcpy-linux-x86_64-v4.1/scrcpy}"
if [[ ! -x "$scrcpy" ]]; then
    scrcpy="$(command -v scrcpy || true)"
    [[ -n "$scrcpy" ]] || {
        echo "mirror: no scrcpy found - set SCRCPY=/path/to/scrcpy" >&2
        exit 1
    }
fi

# -gpu host already needs one; scrcpy needs one for the same reason.
export DISPLAY="${DISPLAY:-:0}"

all=0
if [[ "${1-}" == "--all" ]]; then
    all=1
    shift
fi

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then
    if [[ "$all" == 1 ]]; then
        # Every attached device, physical phone included: what you want when the
        # demo is the whole mesh on one screen rather than the emulators alone.
        mapfile -t targets < <("$adb" devices | awk '$2 == "device" {print $1}')
    else
        # Emulators only by default: the real phone is usually already mirrored by
        # its own tooling, and a second window fighting for the same device is not
        # something to do by accident.
        mapfile -t targets < <("$adb" devices | awk '$2 == "device" && $1 ~ /^emulator-/ {print $1}')
    fi
fi

[[ ${#targets[@]} -gt 0 ]] || {
    if [[ "$all" == 1 ]]; then
        echo "mirror: no device is attached" >&2
    else
        echo "mirror: no emulator is running - ./field.sh up 2, or --all to include the phone" >&2
    fi
    exit 1
}

x=40
for serial in "${targets[@]}"; do
    if pgrep -f "scrcpy.*$serial" > /dev/null 2>&1; then
        echo "mirror: $serial already mirrored, left alone"
        x=$((x + 420))
        continue
    fi
    setsid "$scrcpy" \
        --serial "$serial" \
        --window-title "$serial" \
        --window-x "$x" --window-y 40 \
        --max-size 720 \
        --no-audio \
        --stay-awake \
        > "$run/scrcpy-$serial.log" 2>&1 &
    echo "mirror: $serial"
    x=$((x + 420))
done

echo "mirror: close a window to stop mirroring that phone; the phone keeps running."
