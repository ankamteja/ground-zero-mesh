# Field emulation — many virtual phones, real distance between them

`SimNetwork` proves the mesh *logic* without a device. It does not prove the
*device* half: Nearby's discovery, the foreground service surviving a real
Android, permissions, two Compose UIs reacting to each other. That half needed
three phones and three rooms, which is why it was never run.

This harness replaces the rooms, not the phones. Every Android emulator started
on one host attaches its virtual Bluetooth, BLE, Wi-Fi and UWB controllers to a
single `netsimd` — the radio simulator that already ships inside the Android
emulator (`$ANDROID_HOME/emulator/netsimd`). N emulators become N radios in one
scene, and each device in that scene carries a 3D position that netsim uses when
deciding what reaches whom.

So "how far apart are these phones" becomes a coordinate you can set, and change
while the mesh is running.

Nothing here patches Android, the emulator, or any module of this repo. It is a
launcher plus a client for an API the emulator already exposes.

## Status on this machine — read before using

**The emulator half does not currently run here.** `qemu-system-x86_64` segfaults
about 24 seconds into every boot, at the same stage each time. Ruled out, one at
a time: emulator 36.3.10 and 37.1.11, system images API 35 and API 36, GPU modes
`auto` / `swiftshader_indirect` / `guest`, Vulkan on and off, headless and
windowed, bundled and system libs, cameras on and off, KVM and `-no-accel`
(which only moved the crash from 24s to 102s — the same point in a slower boot),
and adb contact during boot. A `NVRM: ... Out of memory [NV_ERR_NO_MEMORY]`
appears in the kernel log around the same time, so the host GPU driver is the
best remaining suspect; a reboot is the cheapest next thing to try.

So `field.sh` and `netsimctl.py` are written and syntax-checked but **not
verified against a booted emulator**. `netsimctl.py` does talk real gRPC to a
`netsimd` (verified — it connects and gets a typed response), but a standalone
`netsimd` answers `UNIMPLEMENTED`: it forces `no_cli_ui` on and the `netsim` CLI
binary is not in the SDK package, so the frontend service only registers when
the daemon is started by an emulator. That path could not be reached.

**The responder half does work, and is verified end-to-end.** See below.

## Use

```bash
./setup.sh                       # once: venv + generated netsim gRPC stubs
./field.sh up 3                  # three headless phones, one radio scene
./netsimctl.py list              # what netsim sees, and where each phone is
./netsimctl.py place emulator-5554 0 0
./netsimctl.py walk emulator-5554 0 0 --to 150 0 --seconds 30
./netsimctl.py scenario scenarios/collapse.json
./field.sh install               # push app/build/outputs/apk/debug/app-debug.apk
./field.sh down
```

### A real phone as the responder

netsim's scene is host-local, so a USB-attached phone cannot join it. It joins
the way the repo already supports a device joining without Nearby — over
`LanRelayTransport`, through an `adb reverse` tunnel to a relay on the laptop:

```bash
./responder.sh attach            # tunnel 7777 (relay) and 8080 (gateway)
./responder.sh install
./gradlew :core:runRelay         # from the repo root
# then, in the app: set the relay host to 127.0.0.1
./responder.sh detach
```

Verified on an SM-S931B (Android 16): the tunnel was created and the phone
reached a server running on the laptop through it. `responder.sh` targets the
physical device only and never an emulator, so it is safe to run with a field
up; set `FIELD_RESPONDER=<serial>` when more than one phone is plugged in.

`field.sh up` runs one AVD N times with `-read-only`, so three phones cost one
7 GB AVD image rather than three. Each is headless, 1536 MB by default
(`FIELD_MEM_MB=`), cold-booted, and captures every virtual radio frame for
Wireshark (`-netsim-args --pcap`).

Budget honestly: three phones at 1536 MB plus a Gradle daemon is roughly 6 GB.
On a 16 GB laptop, close the browser first or run two.

## What a run actually exercises

| Layer | Covered here | Notes |
|---|---|---|
| `NearbyTransport` (L0) | **unverified** | needs Play services; see the caveat below |
| `MeshForegroundService` | yes | real Android service, real process death |
| Permission matrix | yes | real per-API-level runtime grants |
| `NodeScreen` / Compose UI | yes | real device, real lifecycle |
| `StoreAndForward`, `PeerTable` | yes | once a transport is carrying frames |
| `LanRelayTransport` / `TcpTransport` | yes | `10.0.2.2` is the host from inside an emulator |
| `GatewayServer` + dashboard | yes | `adb forward tcp:8080 tcp:8080` |
| `LoRaBridgeTransport` | no | use Meshtasticator instead, below |

### The caveat that decides whether this is worth it

Nearby Connections is a Play services API. The AVD must be a Google Play system
image, and whether GMS Nearby completes a connection *between two emulators* is
not documented either way — Google documents virtual Bluetooth between emulators
(netsim) and, from emulator 36.5, a shared virtual Wi-Fi network with NSD and
Wi-Fi Direct, but says nothing about Nearby on top of that.

Test that first, before building anything on this harness. Everything else in
the table degrades gracefully: `LanRelayTransport` already gives a working
transport that does not touch Nearby, so a field run stays useful even if Nearby
turns out to need real hardware.

The emulator here was upgraded 36.3.10 → 37.1.11 for this, well past the 36.5
that added the shared virtual Wi-Fi network and `-netsim-args --rssi`. An
`android-35;google_apis_playstore;x86_64` image and a `field_api35` AVD were
added alongside the existing `Medium_Phone_API_36`, which is untouched.

## The other two tools, and when to reach for them

**Cuttlefish + wmediumd** — full Android VMs instead of the QEMU emulator.
`launch_cvd --num_instances=N` shares one Wi-Fi and Bluetooth medium, and
`wmediumd_control set_position MAC X Y` / `set_snr MAC1 MAC2 SNR` model
propagation properly. This is the strongest distance model available, and the
right escalation if emulator Wi-Fi turns out to be too coarse. Cost: much more
RAM, and public AOSP Cuttlefish images carry no Play services at all.

**Meshtasticator** (`meshtastic/Meshtasticator`) — the LoRa half. It runs real
`meshtasticd` firmware instances, one TCP port each from 4403, and forwards a
packet only to nodes in range under a chosen pathloss model (log-distance, four
Okumura-Hata variants, two 3GPP). `LoRaBridgeTransport` can point at a simulated
node's TCP port and speak real `MeshtasticFrame`s to it, with the 233-byte
payload limit that a real Meshtastic hop imposes.

## Layout

```
proto/        netsim + rootcanal .proto, vendored from AOSP (Apache 2.0)
setup.sh      venv + generated gRPC stubs (gen/, gitignored)
netsimctl.py  place / walk / scenario / list / reset
field.sh      up / down / status / install    (emulators)
responder.sh  attach / detach / install / status  (a real phone)
scenarios/    timelines a run can replay
```
