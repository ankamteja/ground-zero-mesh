#!/usr/bin/env bash
# Attach a real phone to the field as the responder.
#
# netsim's radio scene is host-local: it reaches emulators, never a USB-attached
# phone. So a real device joins the field the way the repo already supports one
# joining a mesh without Nearby -- `LanRelayTransport` over `TcpTransport` to a
# `TcpRelayServer`, the star topology `docs/architecture.md` describes.
#
# `adb reverse` is what makes that work over USB: it opens a port *on the phone*
# that tunnels back to the laptop, so the phone dials 127.0.0.1 and lands on the
# relay running here. No Wi-Fi, no hotspot, no IP address to keep in sync -- and
# the phone's own radios stay untouched, so what it does is real device
# behaviour rather than anything simulated.
#
#   ./responder.sh attach            reverse-tunnel the relay + gateway ports
#   ./responder.sh install           install the debug APK
#   ./responder.sh detach            remove the tunnels
#
# After `attach`, set the relay host inside the app to 127.0.0.1 (RelayScreen);
# `RelayHostStore` persists it, and `MeshForegroundService` picks
# `LanRelayTransport` over `NearbyTransport` whenever it is non-blank.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="$sdk/platform-tools/adb"

# RelayHostStore.DEFAULT_PORT, and the gateway's own port.
relay_port="${FIELD_RELAY_PORT:-7777}"
gateway_port="${FIELD_GATEWAY_PORT:-8080}"

die() { echo "responder: $*" >&2; exit 1; }

# The one real device among the emulators. Named explicitly rather than "first
# device", so a stray emulator can never be mistaken for the responder.
serial() {
    local found
    found="$(FIELD_RESPONDER="${FIELD_RESPONDER:-}"; \
        if [[ -n "$FIELD_RESPONDER" ]]; then echo "$FIELD_RESPONDER"; else
            "$adb" devices | awk '$2 == "device" && $1 !~ /^emulator-/ {print $1}'
        fi)"
    [[ -n "$found" ]] || die "no physical device attached (set FIELD_RESPONDER=<serial>)"
    [[ "$(wc -l <<< "$found")" == 1 ]] || die "several physical devices: $found - set FIELD_RESPONDER"
    echo "$found"
}

case "${1:-}" in
    attach)
        s="$(serial)"
        # reverse, not forward: the phone connects out to the laptop.
        #
        # A port can already be bound on the phone -- another session's tunnel,
        # or a leftover from a previous run. That is not a reason to abort the
        # whole attach, so each port is reported on its own and an existing
        # binding is left alone rather than torn down (it may not be ours).
        for port in "$relay_port" "$gateway_port"; do
            if "$adb" -s "$s" reverse "tcp:$port" "tcp:$port" > /dev/null 2>&1; then
                echo "responder: tcp:$port tunnelled"
            elif "$adb" -s "$s" reverse --list 2>/dev/null | grep -q "tcp:$port tcp:$port"; then
                echo "responder: tcp:$port already tunnelled, left as is"
            else
                echo "responder: tcp:$port FAILED - in use on the phone by something else" >&2
            fi
        done
        echo "responder: $s ready - relay $relay_port, gateway $gateway_port"
        echo "responder: start the relay here with  ./gradlew :core:runRelay"
        echo "responder: then set the relay host in the app to 127.0.0.1"
        ;;
    detach)
        s="$(serial)"
        "$adb" -s "$s" reverse --remove-all
        echo "responder: $s tunnels removed"
        ;;
    install)
        s="$(serial)"
        apk="$repo/app/build/outputs/apk/debug/app-debug.apk"
        [[ -f "$apk" ]] || die "no APK at $apk - build it with ./gradlew :app:assembleDebug"
        "$adb" -s "$s" install -r -g "$apk"
        ;;
    status)
        s="$(serial)"
        echo "responder: $s ($("$adb" -s "$s" shell getprop ro.product.model | tr -d '\r'))"
        "$adb" -s "$s" reverse --list || true
        ;;
    *) sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
