# Running the actual emulation

`SimNetwork` proves the mesh logic on a laptop with no phone in the room. This
document is about the other half: running the **real app on real Android**, over
**real radios**, with a **real phone** as the responder — and doing it without
three handsets and three rooms.

Everything below has been run end to end on this machine. Where a step prints
something, the output shown is what it actually printed.

If you only want the tooling's design rationale, read
[`tools/field/README.md`](../tools/field/README.md). This file is the run-book.

---

## 1. What you are about to build

```
  emulator-5554                laptop                       SM-S931B (USB)
  +--------------+      +--------------------+        +--------------------+
  | the app      |      | :core:runRelay     |        | the app            |
  | role = NODE  |-TCP->| TcpRelayServer     |<-adb-->| role = GATEWAY     |
  | (a victim)   | :7802| star topology hub  | reverse| GatewayServer :8080|
  +--------------+      +--------------------+        +--------------------+
         |                        |
      10.0.2.2            +-------v--------------+
   (host loopback,        | :app:runHeadlessGate |  <- optional: the laptop can
    from inside QEMU)     | way, dashboard :8080 |     be the responder instead
                          +----------------------+
```

Two independent things join the same mesh:

- **Virtual phones.** Every Android emulator started on this host attaches its
  virtual Bluetooth/BLE/Wi-Fi controllers to one `netsimd`, so N emulators are N
  radios in one scene. Two of them find each other over **Nearby Connections** —
  verified, see `tools/field/README.md`.
- **A real phone.** netsim's scene is host-local and cannot reach a USB device,
  so the phone joins the way the repo already supports joining without Nearby:
  `LanRelayTransport` over an `adb reverse` tunnel to the relay on the laptop.

Both are the shipping code paths. Nothing here patches Android, the emulator, or
any module of this repo.

---

## 2. Before the first run

You need, once per machine:

| Thing | Check | If missing |
|---|---|---|
| Android SDK | `ls $HOME/Android/Sdk/platform-tools/adb` | install via Android Studio |
| Emulator >= 36.5 | `$HOME/Android/Sdk/emulator/emulator -version` -> `37.1.11` here | `sdkmanager --install emulator` |
| A **Play Store** AVD | `ls ~/.android/avd` -> `field_api35.avd` | see below |
| KVM | `ls -l /dev/kvm` | enable virtualisation in BIOS |
| `local.properties` | `grep sdk.dir local.properties` | `echo "sdk.dir=$HOME/Android/Sdk" > local.properties` |

The AVD **must** be a Google Play image, or there are no Play services for
Nearby to run on:

```bash
sdkmanager --install "system-images;android-35;google_apis_playstore;x86_64"
avdmanager create avd -n field_api35 \
  -k "system-images;android-35;google_apis_playstore;x86_64" -d pixel_6
```

`local.properties` is gitignored and does not exist in a fresh clone or a fresh
worktree; Gradle fails with *"SDK location not found"* until you create it.

Only if you want `netsimctl.py` (position control — see §8 for why it is
currently inert):

```bash
cd tools/field && ./setup.sh      # venv + generated gRPC stubs, both gitignored
```

### Pick a relay port and keep it

`TcpRelayMain` defaults to **7777** (`RelayHostStore.DEFAULT_PORT`). If something
else on your machine already holds it — another session's relay, for instance —
pick another and use the same number in all three places. The verified run below
used **7802**:

```bash
export FIELD_RELAY_PORT=7802
```

---

## 3. The whole run, top to bottom

Run everything from the repo root unless a step says otherwise.

> **In a hurry, or running this in front of people?** `tools/field/demo.sh` does §3.4
> through §3.7 in one command, in the right order, waiting on each port and naming the one
> that did not come up. `demo.sh reset` gives you an empty board and a fresh SOS between
> run-throughs, and `demo.sh down` hands the phone back its own role and node id. The rest
> of this section is what it does, step by step, for when a step needs to be understood
> rather than repeated — and §3.1–§3.3 (building and installing) still come first.

### 3.1 Build the APK

```bash
./gradlew :app:assembleDebug
```

Expect `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk`.

### 3.2 Boot the virtual phones

```bash
cd tools/field
./field.sh up 2
```

Two headless emulators, one shared radio scene. Cold boot takes 60-90 s. Watch
for them with:

```bash
./field.sh status
```

```
emulator-5554	device
emulator-5556	device
```

> **Two is the limit on a 16 GB laptop.** Each phone costs ~2.8 GB of host RSS
> at the default 1536 MB of guest RAM. A third gets the other two OOM-killed
> mid-boot — the symptom is an emulator that reaches `adb devices` and then
> silently disappears. Close the browser before trying three.

> **`-gpu host` is mandatory on an SELinux host and `field.sh` sets it.** Do not
> "fix" it back to swiftshader: SwiftShader JIT-compiles shaders onto the heap
> and executes them, an enforcing host denies `execheap`, and the emulator
> segfaults ~24 s into every boot with nothing useful in the log. Details in
> `tools/field/README.md`.

### 3.3 Install the app on them

```bash
./field.sh install
```

Installs with `-g`, so runtime permissions are pre-granted and no headless phone
is left sitting on a permission dialog nobody can tap.

### 3.4 Start the relay on the laptop

From the repo root, in its own terminal:

```bash
./gradlew :core:runRelay -PrelayArgs="7802"
```

It prints a running census, which is your main instrument for the rest of the
run:

```
peers=3 relayed=11 duplicates=13 dropped=0
```

`peers` is how many mesh members are connected to it. `relayed` moves every time
a frame crosses it. **If `relayed` does not move when you press SOS, nothing
downstream is real** — fix that before looking at any dashboard.

### 3.5 Choose who the responder is

**Option A — the laptop is the responder.** Simplest; gives you a dashboard in a
browser. In another terminal, from the repo root:

```bash
./gradlew :app:runHeadlessGateway -PgwArgs="localhost 7802 8080"
```

Arguments are `[relayHost] [relayPort] [httpPort]`. Then open
<http://127.0.0.1:8080>.

**Option B — the real phone is the responder.** This is the honest demo: the
board is computed and served by the app on an actual handset.

```bash
cd tools/field
FIELD_RELAY_PORT=7802 ./responder.sh attach
```

```
responder: tcp:7802 tunnelled
responder: tcp:8080 FAILED - in use on the phone by something else
responder: RZCY219DWVM ready - relay 7802, gateway 8080
```

The 8080 failure is **expected and harmless** when the phone is already serving
its own gateway on that port — only the relay port has to tunnel. `adb reverse`
opens the port *on the phone* pointing back at the laptop, which is why the
phone dials `127.0.0.1` and lands here, with no Wi-Fi, hotspot or IP address to
keep in sync.

Install the app there too, if it is not already on:

```bash
./responder.sh install
```

### 3.6 Give each phone its role

The app's first screen is where a person picks victim / relay / responder and
types a relay address. A headless phone has nobody to tap it, and both settings
are plain SharedPreferences, so on a debug build `run-as` can write them:

```bash
cd tools/field
./configure.sh emulator-5554 NODE    10.0.2.2:7802     # a victim
./configure.sh RZCY219DWVM  GATEWAY  127.0.0.1:7802    # the responder
```

`10.0.2.2` is the emulator's alias for host loopback — that is how a phone
inside QEMU reaches the relay. A USB phone uses `127.0.0.1` through the tunnel.

It prints the resulting prefs so you can see what the phone will read:

```xml
<map>
    <long name="node_id" value="255079109261042" />
    <string name="mesh_role">NODE</string>
    <string name="relay_host">10.0.2.2:7802</string>
</map>
```

> `configure.sh` **merges** — it never rewrites the file wholesale. `node_id`
> lives in the same prefs file and *is the phone's identity on the mesh*; dedup,
> corroboration and trust standing all key off it. Overwriting the file hands
> the node a fresh identity on every configure, which shows up on the board as a
> different victim each run. Do not "simplify" this back into an `echo >`.

`MeshForegroundService` reads role and relay host **once, at service start**, so
`configure.sh` force-stops the app and you relaunch afterwards. Changing them
under a running service does nothing — the same caveat the app's own screen
prints.

### 3.7 Raise an SOS

```bash
./sos.sh emulator-5554 drowning        # or: trapped (default), other
```

This is not a back door. It launches the app and taps the same Compose buttons a
trapped person would, found by label in a `uiautomator` dump rather than by
pixel, so it survives a layout change and fails loudly instead of tapping empty
screen:

```
sos: tapped 'victim' at 197 645
sos: tapped 'drowning' at 192 912
sos: tapped 'SEND SOS' at 540 1191
sos: sent from emulator-5554 (drowning), no crash
```

It checks logcat for `FATAL EXCEPTION` afterwards and fails the run if the app
crashed — which is exactly how the `NetworkOnMainThreadException` in
`LanRelayTransport` was found.

### 3.8 Seeing it, for a demo

The field is headless because that is what makes two phones fit on a 16 GB
laptop. When a run has to be *shown*, put the phones on screen without rebooting
anything:

```bash
./mirror.sh                      # every booted emulator, tiled and titled
./mirror.sh emulator-5554        # just one
```

It uses scrcpy over adb, so the emulators and a physical phone mirror the same
way and every device in the demo looks alike on screen. A phone already mirrored
by other tooling is left alone. Closing a window stops the mirror; the phone
keeps running.

---

## 4. Reading the board

The gateway serves four routes: `/` (the dashboard), `/snapshot` (JSON),
`/events` (SSE), and `POST /resolve`.

**Option A, on the laptop:**

```bash
curl -s http://127.0.0.1:8080/snapshot | python3 -m json.tool
```

**Option B, the board computed on the real phone.** Forward a spare local port to
the phone's gateway and read it from here:

```bash
adb -s RZCY219DWVM forward tcp:8099 tcp:8080
curl -s http://127.0.0.1:8099/snapshot | python3 -m json.tool
```

A row from the verified run:

```
clusters: 1
  e7fe-3bb2-76f2  DROWNING_IMMINENT  reports 2  age 15s  hops 2
advice: 1 incident(s) within the action budget, 0 first-hand and dispatchable;
        1 at imminent-drowning severity. Highest: unset (drowning imminent).
```

That single line is the whole point: `e7fe-3bb2-76f2` is the emulator's
persisted `node_id`, the severity was escalated by the responder's own ranking,
`hops 2` means it crossed the relay, and the row was assembled by
`GatewayServer` **running on a physical phone**.

A gateway only ever shows frames that arrived *after* it connected — gossip
carries new traffic, not history. A board that is empty right after you start it
is correct; raise a fresh SOS.

### What each part of the dashboard is

Top bar, left to right: the live/stream indicator (green means the `/events` SSE
stream is connected, not that traffic is flowing), the last update time, and the
theme toggle.

The second strip is the **emulation toolbar** — `SOS · 1 hop`, `clear board`.
Those buttons drive the *simulated* mesh and only work when the gateway was
started with `--sim`, which stands up its control API on `httpPort + 10`. In a
relay bridge run there is no such API and the strip says
`control API unreachable on http://127.0.0.1:8090`. That is correct, not a
fault: in a bridge run you raise an SOS from an actual phone with `sos.sh`.

The map is **schematic, not measured** — it says so in its own legend. Filled
dots are incidents, hollow ones relays, the diamond is this device. An incident
with no GPS is drawn `UNPLACED` in a holding row along the top, so several
unplaced incidents sit at nearly the same spot and their labels overlap. Give a
phone a position with `./field.sh geo <serial> <lon> <lat>` and its dot moves to
a real place.

`ADVISORY` is the rule-based read of the board; `ADVISOR` is the optional local
LLM (`./gradlew :core:runAdvisor` against `ollama serve`). Both are explicitly
**non-binding — neither can reorder the board.** `PERIMETER` is the responder's
own zone, `unset` until one is entered.

`BOARD` is the ranked incident list, `INSPECTOR` the detail of the selected row.
Two rows can share one origin: a cluster is keyed `origin@firstSeen`, so the same
victim raising a second, later SOS is a second incident, not a duplicate report.

---

## 5. Checking it is genuinely working

Four checks, in order. Each one rules out the layer below it.

```bash
adb devices                                    # 1. phones are up
tail -1 <relay terminal>                       # 2. peers=N, relayed climbing
curl -s http://127.0.0.1:8080/snapshot         # 3. a cluster appears
adb -s emulator-5554 logcat -d | grep -c "FATAL EXCEPTION"   # 4. -> 0
```

Two properties worth asserting deliberately, because both have broken before:

- **Identity is stable.** Run `configure.sh` twice and the `origin` on the board
  must not change. If a second victim appears out of nowhere, `node_id` is being
  clobbered.
- **`dropped=0`.** A climbing `dropped` on the relay means frames are arriving
  that it cannot forward.

### The GPS coordinate, and when its absence is correct

A row that reads `no GPS lock` indoors is the design working. `GpsBridge` asks for
`GPS_PROVIDER` only and never `NETWORK_PROVIDER`, because a cell/Wi-Fi position can be
kilometres out and `Envelope.gpsLat` is documented as a real fix or none — so a victim
without a satellite lock sends no coordinate rather than a wrong one. Through a roof,
that is usually the case.

To see real coordinates, put the victim phone by a window or outside for a minute with
the screen on and raise the SOS again; `gpsLat`/`gpsLon` fill in on their own.
`demo.sh` prints which of the two situations you are in before it sends, so an empty
coordinate is never a mystery mid-demo.

Two things that surprise people:

- **A coordinate does not place the incident.** Placement is `zone` and `floor`, which a
  responder enters. A row can carry a real fix and still read `unplaced`.
- **A fix is cached in the app process.** It survives whatever produced it going away, and
  clears only on `am force-stop`. Worth knowing before you trust a coordinate you are no
  longer feeding.

---

## 6. Tearing down

```bash
cd tools/field
./responder.sh detach            # remove the adb reverse tunnels
./field.sh down                  # stop the emulators
```

Then Ctrl-C the relay and gateway terminals. The emulators run `-read-only` with
no snapshot save, so there is nothing to clean up — a field is disposable by
design.

---

## 7. When it goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| Gradle: *"SDK location not found"* | no `local.properties` (gitignored, absent in a fresh worktree) | `echo "sdk.dir=$HOME/Android/Sdk" > local.properties` |
| Emulator dies ~24 s into boot, log just stops | SELinux denies `execheap` to SwiftShader's JIT | already fixed by `-gpu host` in `field.sh`; do not remove it |
| Third emulator kills the other two | ~2.8 GB host RSS each | run two, or close the browser |
| `Permission denied` running a script | lost `+x` | `chmod +x tools/field/*.sh` |
| `responder: tcp:8080 FAILED` | the phone serves its own gateway there | expected; only the relay port matters |
| `no physical device attached` | phone not in `adb devices`, or several are | `FIELD_RESPONDER=<serial> ./responder.sh ...` |
| Relay `relayed` never moves | role/relay-host not applied | `configure.sh` then **relaunch** — the service reads them only at start |
| Board empty but relay is counting | gateway started after the SOS | raise a fresh one; gossip has no history |
| `origin` changes between runs | `node_id` clobbered | check `configure.sh` still merges |
| `sos: '<label>' is not on screen` | app not on the victim screen, or a label was renamed | the script prints the labels it can see |
| dashboard: `control API unreachable` | not a `--sim` run | expected; raise the SOS from a phone instead |
| `netsimctl.py`: `UNIMPLEMENTED` | the SDK's `netsimd` has its control plane compiled out | expected — see §8 |

---

## 8. What this proves, and what it does not

**Proven on this machine:**

- Two emulators run the app and complete a **Nearby Connections** link over
  `ENCRYPTED_WIFI_LAN`, with BLE GATT advertisements on both sides. This is
  documented nowhere by Google and was the assumption the whole approach rested
  on.
- `MeshForegroundService`, the runtime permission matrix, and the Compose UI on
  real Android.
- A victim's SOS, raised through the **real UI**, crossing
  `LanRelayTransport` -> `TcpRelayServer` -> a **physical phone's**
  `GatewayServer` and appearing on its board, severity-ranked, at `hops 2`.

**Not covered:**

- **Live distance.** `netsimctl.py` speaks correct gRPC against the published
  protos, but every `FrontendService` call returns `UNIMPLEMENTED`: the SDK ships
  a stripped `netsimd` with `no_cli_ui` and `no_web_ui` hardcoded on and the
  `netsim` CLI absent. Real position control needs a `netsimd` built from AOSP,
  or Cuttlefish + `wmediumd`. What *does* work today is GPS —
  `./field.sh geo <serial> <lon> <lat>` — which every location-aware part of the
  app follows.
- **LoRa.** `LoRaBridgeTransport` is not exercised here; Meshtasticator is the
  right tool, one `meshtasticd` per TCP port from 4403 with a real pathloss
  model.

---

## 9. The tools, in one place

```
tools/field/field.sh       up / down / status / install / geo   (emulators)
tools/field/responder.sh   attach / detach / install / status   (a real phone)
tools/field/configure.sh   set role + relay host headlessly     (any phone)
tools/field/sos.sh         raise an SOS through the real UI     (any phone)
tools/field/mirror.sh      put the phones on screen for a demo  (scrcpy)
tools/field/demo.sh        up / reset / down                    (the whole live demo)
tools/field/netsimctl.py   place / walk / scenario / list / reset
tools/field/setup.sh       venv + generated netsim gRPC stubs
```
