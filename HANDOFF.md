# Handoff — GPS location feature (landed end to end) + one open bug

Written mid-session, stopped on request, then committed and pushed to `main` as `c981f38`
(`feat: GPS wire-format support (core only, not wired to a sender yet)`) — **since
resumed twice**: `NodeAgent.buildEnvelope` now reads `lastGpsFix` (see "Closed" below), and
everything under the old "Not started at all" section has since landed too — app-side
capture, permissions, `MeshStack` passthrough, `ClusterJson`, dashboard rendering,
fixtures. See the GPS ledger entry in `docs/architecture.md` for the full account; this
file is kept for the original session narrative below, not as the live status any more.
Also since this was written: `3c65715` (`fix: mesh service never started after granting
permissions in-app`), a `MainActivity` fix from a collaborator, landed on `main` first and
was merged in cleanly before any of the above — no collision, but worth knowing the GPS
grant UI added to `VictimScreen` mirrors that exact fix's nudge pattern on purpose.

**Not yet done:** verified on a real device, or compiled at all — this environment has no
Android SDK configured, so `core` is tested and green but the `app`-side GPS changes are
reviewed, not built. Do that first when a device/SDK is available.

## Decision made (user, confirmed)

SOS location: **GPS when available, honestly nullable.** Try for a fix at broadcast time;
send null if none (indoors, no signal, timeout, no permission). Dashboard shows a real
coordinate when present, falls back to hop-count/zone when not. Never fabricate one — this
was chosen specifically because GPS fails exactly where victims usually are (indoors,
trapped, underground), and the codebase already has a strong, repeated stance against
fake precision (`TODO.md` open assumptions, `docs/architecture.md`, `MeshForegroundService`
comments).

## Done — `core` (compiles, `./gradlew :core:test` green including new tests)

- **`Envelope.kt`** — added `gpsLat: Float?` / `gpsLon: Float?`. Validated both-null-or-both-set,
  range `-90..90` / `-180..180`. Tests in `EnvelopeTest.kt`
  (`rejectsAPartialGpsFix`, `rejectsGpsOutOfRange`, `acceptsAValidGpsFix`).
- **`CompactCodec.kt`** — wire format bumped to version `0x03` (was `0x02`). New layout:
  1-byte GPS presence header + 8 bytes (two big-endian f32) between the flags byte and the
  zone-length byte. `frameSize()`/`encode()`/`decode()` all updated; layout doc comment at
  the top of the file updated. Tests in `CodecTest.kt`:
  `compactRoundTripsGpsExactly` (exact f32 round-trip, no quantisation loss unlike the
  feature vector), `compactCarriesGpsForEightBytes` (frame-size delta).
- **`JsonCodec.kt`** — `gpsLat`/`gpsLon` added to the JSON shape, nullable. Added
  `JsonObj.numOrNull()` so an older JSON payload missing the keys entirely decodes as null
  rather than throwing. Test: `jsonRoundTripsWithGps` in `CodecTest.kt`.
- **`IncidentCluster.kt`** — `gpsLat`/`gpsLon` fields. Merge rule in `DedupCluster.ingest`:
  `envelope.gpsLat ?: existing.gpsLat` — a later report with no fix never blanks one already
  held; a later report *with* a fix (Stage 3 is often better than Stage 0) replaces it. Same
  pattern as `slmSummary`. Tests in `TrustAndClusterTest.kt`:
  `a later report without a GPS fix never blanks the one we have`,
  `a later, better GPS fix replaces the earlier one`.
- **`NodeAgent.kt`** — added `lastGpsFix: GpsFix?` (nullable data class holding `lat`/`lon`)
  and `fun updateGpsFix(lat: Float, lon: Float)`. Mirrors the existing `lastVector`/
  `lastEvent` pattern exactly: the platform layer calls `updateGpsFix` whenever a location
  update arrives, asynchronously, and whatever envelope gets built next just reads whatever
  is currently held. **`raiseSos` never waits on a fix** — this is the mechanism that makes
  that true without any signature change to `raiseSos`/`heartbeatTick`/
  `completeSensoryWindow`.

## Closed — `NodeAgent.buildEnvelope()` now reads `lastGpsFix`

Was: tracked but never read, so `core` silently dropped any fix an app ever provided.
Fixed with the two-line change this section originally specified; `NodeAgentTest` now has
`a GPS fix taken before the SOS reaches the broadcast envelope` and `no GPS fix means the
envelope honestly carries none`. `./gradlew :core:test` green.

## Closed — everything that was "Not started at all"

1. **App-side capture — `GpsBridge` (new).** `LocationManager.GPS_PROVIDER` only, no new
   Gradle dependency (decided against `FusedLocationProviderClient` — not needed, keeps the
   footprint minimal), never `NETWORK_PROVIDER` (would violate "never a fallback or an
   estimate"). Mirrors `SensorBridge`'s start/stop-on-`MeshRole.NODE` shape and its "do
   nothing, don't throw, when the precondition is missing" contract for a missing
   permission. `AndroidManifest.xml`'s `ACCESS_FINE_LOCATION` `maxSdkVersion="31"` cap is
   removed. Permission is requested through a **separate flow**, not folded into
   `MeshPermissions.runtimePermissions()` — see `MeshPermissions.LOCATION_PERMISSION` and
   `VictimScreen`'s own "Add GPS" button/launcher. Wired into `MeshForegroundService`
   alongside `SensorBridge`, including a retry path (`onStartCommand`) for a permission
   granted after the service is already running.
2. **`ClusterJson.kt`** — emits `gpsLat`/`gpsLon`, `null` when absent, six-decimal
   precision (not the three decimals every other numeric field gets — see the ledger entry
   for why).
3. **Dashboard UI** — built as suggested: a captioned "GPS fix" Inspector row with a
   `geo:lat,lon` link, distinct from hop-count and the schematic position, plus a compact
   board-row "GPS" tag.
4. **`fixtures.json`** — two of eight clusters now carry a real fix; the rest explicit
   `null`.
5. **`docs/architecture.md`** — ledger entry updated with the full app-side landing.
6. **`TODO.md`** — the GPS follow-up marked done; the "Localisation is not solved"
   assumption reworded to "mostly not solved" now that a real fix exists for the minority
   of incidents where GPS is available.

## Update (later same session): the camera bug, and two more real bugs from live testing

The camera-centering report below turned out to have a simple cause, fixed as a side
effect of adding a "you are here" marker (see next section): there was no responder-node
marker at all, so the camera's always-fixed orbit target `(0,0,0)` was centering on empty
space. The marker now sits at that exact point. **Not independently re-verified on
device** — flagged `[x]` provisionally in `TODO.md`, please confirm the view now reads
correctly.

Two more real bugs surfaced running an actual two-phone (victim + responder, no relay)
session, both fixed and tested (`./gradlew test` green):

- **Repeated SOS presses were raising a new incident each time.** `NodeAgent.raiseSos`
  minted a fresh dedup-key timestamp on every call; a re-press before `clearIncident`
  should update the same incident instead — see `docs/architecture.md`'s entry on this. A
  second bug caught in the same fix: the emitted envelope was still stamping the raw press
  time even after the dedup-key logic changed, which would have silently defeated it.
- **No visible connection between the responder and its own board's incidents**, on a
  topology with no relay. Side effect of the earlier `DigitalTwin` carrier-exclusion fix:
  excluding the origin from its own corroborator list means a *direct* link now draws zero
  carrier lines. Fixed with an explicit `self` marker (`MeshStack.localNodeId()` threaded
  through `GatewayServer`) plus a distinct solid connection line per incident, separate
  from the dashed relay-corroboration lines.

Original report, for the record: **"also in ui its centering to someother place and not
to responders node"** — about the 3D dashboard view's canvas renderer. Camera state lives
in the `camera` object (`yaw/pitch/dist/panX/panY`) inside `assets/dashboard/index.html`'s
`<script>` block, if this needs revisiting.

## Where things physically stand (hardware test session)

Two Android phones connected via USB to this laptop this session:
- **S25 (Samsung SM_S931B, adb serial `RZCY219DWVM`)** — responder role. `adb forward
  tcp:8080 tcp:8080` is (or was) active so `http://localhost:8080/` on this laptop tunnels
  to its `GatewayServer` over USB, no Wi-Fi/hotspot needed.
- **Realme RMX3660 (adb serial `3f2d6f59`)** — victim role.

Two real bugs found and **already fixed and pushed to `main`** earlier this session (not
part of the uncommitted GPS work above):
- Missing `INTERNET` permission — `GatewayServer`'s `NanoHTTPD` socket creation failed
  with `EPERM` without it. Fixed in `AndroidManifest.xml`.
- NanoHTTPD auto-gzip on `/events` (SSE) — compressed output never flushed for a stream
  that's designed to never close, so the dashboard connected but silently never updated.
  Fixed via `GatewayServer.useGzipWhenAccepted` override.

Also shipped and pushed this session: real `reportCount` fold, and a hop-count "distance
(hops)" field/UI row (`minHops` was already tracked, just never surfaced).

The "multiple alerts from one press" and "trapped/drowning not carried properly" concerns
raised earlier were run down empirically (clean single-press test on real hardware) and
turned out to be noise from repeated app reinstalls/reconnects during debugging — heartbeat
ticks replaying a stale severity across multiple Nearby sessions — not an app bug. A single
clean SOS press produces exactly one incident, confirmed live on the S25's board.
