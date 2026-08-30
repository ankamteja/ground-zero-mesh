# Handoff — where things stand (2026-08-30, evening)

Written fresh, replacing the earlier version of this file — everything it tracked is now
either landed or superseded below. `main` is at `d07bea4`. `./gradlew test` (373 tests
across `core` + `app`) and `:app:assembleDebug` are both green in this environment (which
now has an Android SDK — several things below were build-verified here for the first time).

Both test phones — **Realme RMX3660** (victim) and **Samsung S25 / SM_S931B** (responder) —
are running the current build as of this write-up.

## What's fully landed and verified on real hardware

- **The core mesh loop.** Victim presses SOS → travels over Nearby Connections (BLE / Wi-Fi
  Direct) → lands on the responder's board, live, over the responder's `GatewayServer`
  (NanoHTTPD + SSE). Confirmed with real presses on real phones this session.
- **A repeated press updates the same incident, not a new one.** `NodeAgent.raiseSos` reuses
  the active incident's dedup-key timestamp on a re-press. Confirmed live: `reportCount`
  climbing (27→32→...) on one unchanging `clusterId` across repeated presses, not new board
  entries. Severity intentionally never walks back down once set (the person's own worst
  statement of how fast they're dying stands) — confirmed this is by design, not a bug, when
  it looked like a later, calmer severity press "wasn't updating."
- **The responder's own position and connections are visible.** A teal diamond "you" marker
  fixed at the schematic centre, plus a distinct connection line from it to every incident —
  separate from the dashed lines that represent an actual relay's corroboration. Fixes the
  original "centering on empty space" report as a side effect (there was no marker to center
  on before).
- **Two infra bugs found and fixed on real devices, neither reachable from a JVM test:**
  missing `INTERNET` permission (`GatewayServer`'s raw socket failed `SocketException:
  EPERM` without it — Nearby itself never needed this permission), and NanoHTTPD's
  auto-gzip silently breaking `/events`' SSE stream (gzip never flushes without a stream
  close, and SSE is designed to never close).
- **`minHops` surfaced as a "distance (hops)" board field** — was already tracked, only ever
  buried in free text.
- **The dashboard's 3D-view legend** — cut from three explanatory sentences to one line of
  short labels next to their swatches.

## What your friend landed today, now build-verified in this environment

Two features arrived as pushes while this session was mid-work; both were merged in
cleanly (git rebase / fast-forward, nothing lost or overwritten — see `docs/architecture.md`
for the full ledger entries).

### GPS location, end to end

Chosen approach (confirmed with the user earlier): a real fix when available, honestly
nullable — never required to send an SOS, never fabricated, falls back to the zone tag / hop
count when absent. `core`: `Envelope.gpsLat`/`gpsLon`, `CompactCodec` wire version `0x03`
(8 bytes, real `f32`, no quantisation loss), `JsonCodec`, `IncidentCluster` merge rule
(`envelope.gpsLat ?: existing.gpsLat`, same pattern as `slmSummary`), `NodeAgent.
updateGpsFix`/`lastGpsFix`/`buildEnvelope` actually reading it onto the wire. `app`:
`GpsBridge` (new — `LocationManager.GPS_PROVIDER` only, no network-location fallback, no new
Gradle dependency), `MeshStack.updateGpsFix` passthrough, `MeshForegroundService` wiring
(including a retry path for a permission granted after the service is already running),
`MeshPermissions.LOCATION_PERMISSION` (deliberately separate from `runtimePermissions()` —
GPS stays optional), `VictimScreen`'s own grant UI, `AndroidManifest.xml`'s
`ACCESS_FINE_LOCATION` `maxSdkVersion="31"` cap removed, `ClusterJson` + a captioned "GPS
fix" Inspector row with a `geo:` link, `fixtures.json` sample data covering both a present
fix and an honest null.

**Then it was tried on a real phone and no fix ever arrived** — for a reason that was never
in the Kotlin. The service declared `foregroundServiceType="connectedDevice|microphone"`, and
since Android 10 a foreground service only keeps receiving location if its type includes
`location`. This service exists to run with the screen off, so the platform silently stopped
delivering fixes the moment the app was no longer in front. Fixed by adding the `location`
type and `FOREGROUND_SERVICE_LOCATION`, and — because on 14+ promoting with a type whose
permission is missing throws and would kill the service — by computing the type mask at
runtime in `MeshForegroundService.enterForeground()` from what is actually granted, with
`onStartCommand` re-promoting so a later grant widens the mask. Two smaller repairs in
`GpsBridge`: it no longer bails out (unrecoverably) when the GPS provider happens to be off
at start, and it seeds from `getLastKnownLocation` when that fix is under two minutes old, so
the first coordinate does not have to wait a full GPS time-to-first-fix. Full reasoning in
`docs/architecture.md`. **Still unwatched on hardware** — the diagnosis is from the platform
contract, not from a coordinate seen landing on the board.

### A laptop as the mesh relay over TCP, for when a third phone isn't on hand

Nearby Connections is BLE/Wi-Fi Direct; a laptop can't join it as a peer. This is a second,
real transport — `core`'s `TcpTransport`/`TcpRelayServer`/`TcpRelayMain` (a runnable
`:core:runRelay` laptop program, star topology, genuinely tested end to end with real
localhost sockets), `app`'s `LanRelayTransport` (thin wrapper adding `PeerTable`
bookkeeping), `RelayHostStore`, a "Laptop relay" field on `MainActivity`,
`MeshForegroundService.buildRadio` branching on whether that field is set. Confirmed with
the user: this *replaces* Nearby for the session rather than running alongside it — a star
topology through one laptop is the only way to guarantee a single path between victim and
responder without physically separating two phones by 15–30m the way the three-phone kit
does.

**This is where the two real bugs from today were, both now fixed:**

1. `LanRelayTransportTest.kt` was written against `kotlin.test` (`Test`/`AfterTest`/
   `assertEquals`), which only `core`'s Gradle module carries — `:app` only has JUnit4,
   matching every other test in the module. Wouldn't compile. Rewritten to
   `org.junit.Test`/`After`/`Assert.assertEquals`/`Assert.assertTrue`.
2. Fixing that surfaced a real one underneath: `LanRelayTransport` wired its `PeerTable`
   bookkeeping as a side effect *inside* `onReceive`/`onPeerConnected` — the methods a
   caller registers a listener through — so peer state only updated if and when the app
   called those methods, and only for events after that registration. `NearbyTransport`
   doesn't have this gap; it populates its own peer table from its SDK's callbacks
   unconditionally. Dormant in the one real call site (`MeshForegroundService` always
   registers both before `start()`), but a real "wired but consumer-order-dependent" bug —
   fixed by moving the registration into `LanRelayTransport`'s own `init`, unconditional and
   one-time.

`./gradlew test` (both modules, 373 tests) and `:app:assembleDebug` are green as of this
write-up — the first time either has been verified in an environment with the Android SDK.
**Still not run on two real phones and a real laptop over a real network.** Runbook is in
`README.md`'s "Two phones and a laptop relay" section:

```
# On the laptop:
./gradlew :core:runRelay              # port 7777 by default

# On each phone, before granting Nearby permissions:
#   type the laptop's host or host:port into "Laptop relay" on the first screen
#   pick victim / responder as usual — no relay role needed, the laptop is the relay
#   (kill and reopen the app after setting it — this is read once at service onCreate)

# Press SOS on the victim phone; watch the laptop terminal's `relayed=` counter move.
```

## Open items, unchanged or newly surfaced (see `TODO.md` for the full, exact text)

- **The actual 3-phone (or 2-phone-plus-laptop) field run itself** — everything above is
  build- and unit-verified; the live multi-device runs that would close out `TODO.md`'s
  Phase 2/6 VERIFY lines haven't happened yet.
- **GPS with a real fix on a real phone** — the foreground-service-type bug that would have
  blocked this is fixed (see above), but nobody has watched a real coordinate arrive yet.
  Note `:app` cannot be compiled in the current environment (no Android SDK), so the app-side
  change is reviewed but not built.
- **The laptop-relay path on a real network** — same: code compiles and unit-tests clean,
  no live two-phone-through-a-laptop run yet.
- **Gateway hotspot still can't be opened programmatically** — a responder opens it by hand
  (Android permission limitation, not a bug).
- **No mesh-wide "resolved" broadcast** — `markPeerFound`/`POST /resolve` only reach the
  gateway's own direct `PeerTable`; a victim known only through relay is unaffected by a
  responder's "found / safe" tap. Needs a new envelope/gossip message type.
- **`Peer.healthy` still has no production reader.**

## Practical notes for whoever picks this up

- Local `adb` setup this session: `3f2d6f59` = Realme (victim), `RZCY219DWVM` = Samsung S25
  (responder). `adb -s RZCY219DWVM forward tcp:8080 tcp:8080` tunnels the responder's
  dashboard to `http://localhost:8080/` on this laptop over USB — no Wi-Fi needed for that
  part. The laptop-relay feature above is a *separate* thing (TCP over the shared Wi-Fi/
  hotspot network, not USB/adb) and hasn't been tried with this USB setup yet.
- If a `git push` gets rejected because someone else pushed in the meantime: `git fetch
  origin main`, then `git rebase origin/main` (or `merge --ff-only` if there's nothing to
  reconcile) — never force-push. Two of today's merges needed conflict resolution in
  `TODO.md` and `docs/architecture.md` (both append-heavy files that two people were adding
  entries to near the same spot); resolved by keeping both sides' content, never dropping
  either author's work.
