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

## Status — what is actually proven

**Two virtual phones run the app and find each other over Nearby.** Verified on
this machine: `field.sh up 2` boots two emulators sharing one `netsimd`, the
debug APK installs on both, `MeshForegroundService` runs on each, and Google
Play services logs

```
NearbyMediums: Successfully bound ServerSocket for service org.groundzero.mesh_UPGRADE
NearbyConnections: EndpointManager received a KEEP_ALIVE frame (seqNum:6) with ACK
                   from endpoint JWRI on channel ENCRYPTED_WIFI_LAN
```

— our service id, our two nodes, a live connection carrying acked keep-alives,
plus BLE GATT advertisements with a matching service-id hash on both sides. So
**Nearby Connections does work between emulators**, which was the one assumption
this whole approach rested on. The AVD must be a Google Play image (`field_api35`
here is `google_apis_playstore`); install with `-g` so runtime permissions are
pre-granted.

### `-gpu host` is mandatory on an SELinux host — do not "fix" it back

SwiftShader JIT-compiles shaders onto the heap and executes them. An enforcing
SELinux host denies that:

```
AVC avc: denied { execheap } for comm="RenderThread" ... permissive=0
```

and the emulator segfaults ~24s into every boot. This is not subtle to diagnose
from the outside — the log just stops. Ruled out first, one at a time: emulator
36.3.10 and 37.1.11, API 35 and API 36 images, Vulkan on/off, headless and
windowed, bundled and system libs, cameras on/off, KVM and `-no-accel`. The
backtrace is what named it: the faulting frame is inside
`emulator/lib64/gles_swiftshader/libGLESv2.so`, calling into JIT-generated code.

Rendering on the real GPU never loads that JIT. The alternative,
`setsebool -P selinuxuser_execheap on`, loosens policy for every unconfined
process on the machine and is the worse trade.

### Live position control is *not* available from the SDK

`netsimctl.py` speaks correct gRPC — right method paths, real typed responses —
but every `FrontendService` call returns `UNIMPLEMENTED`, whether the daemon is
standalone, in `--dev` mode, or spawned by an emulator (which advertises its port
in `/run/user/$UID/netsim.ini`). The SDK ships a stripped `netsimd`: `no_cli_ui`
and `no_web_ui` are both hardcoded on, and the `netsim` CLI binary is absent.
The control plane is compiled out. Using it needs a `netsimd` built from AOSP.

What *is* available for distance today:

- `adb emu geo fix <lon> <lat>` — real GPS per phone, accepted (returns `OK`).
  Everything in the app that reasons about location responds to this.
- `emulator -netsim-args "--rssi=ble:-90"` — per-emulator signal strength, set at
  launch (documented for 36.5+; not exercised here).

`netsimctl.py` is kept because it is correct against the published protos and
starts working the moment a full `netsimd` is on the path.

**The responder half is verified end-to-end too.** See below.

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
| `NearbyTransport` (L0) | **yes, verified** | two emulators connected over `ENCRYPTED_WIFI_LAN` |
| `MeshForegroundService` | yes | real Android service, real process death |
| Permission matrix | yes | real per-API-level runtime grants |
| `NodeScreen` / Compose UI | yes | real device, real lifecycle |
| `StoreAndForward`, `PeerTable` | yes | once a transport is carrying frames |
| `LanRelayTransport` / `TcpTransport` | yes | `10.0.2.2` is the host from inside an emulator |
| `GatewayServer` + dashboard | yes | `adb forward tcp:8080 tcp:8080` |
| `LoRaBridgeTransport` | no | use Meshtasticator instead, below |

### The question that used to decide whether this was worth it

Whether GMS Nearby completes a connection *between two emulators* is documented
nowhere: Google documents virtual Bluetooth between emulators (netsim) and, from
36.5, a shared virtual Wi-Fi network with NSD and Wi-Fi Direct, but says nothing
about Nearby on top of that. It was the reason to test before building.

It does work — see the log excerpt at the top. Answered, on this machine, with
this AVD.

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
