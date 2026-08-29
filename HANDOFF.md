# Handoff — GPS location feature (in progress) + one open bug

Written mid-session, stopped on request. `core` is green and compiles; this is a snapshot
of exactly where the GPS feature stops, plus one unrelated UI bug reported but not yet
investigated. Not committed — working tree has the `core` changes below, uncommitted.

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

## Stopped here — the actual gap

**`NodeAgent.buildEnvelope()` does not yet pass `gpsLat`/`gpsLon` through to the
`Envelope(...)` it constructs.** `lastGpsFix` is tracked but never read. This is a
two-line fix:

```kotlin
private fun buildEnvelope(
    ...
) = Envelope(
    ...
    gpsLat = lastGpsFix?.lat,
    gpsLon = lastGpsFix?.lon,
)
```

Do this first when resuming — everything above is dead weight until this line exists, and
`core` currently silently drops any fix an app ever provides.

## Not started at all

1. **App-side capture.** Nothing calls `NodeAgent.updateGpsFix` yet. Needs:
   - `ACCESS_FINE_LOCATION` in `AndroidManifest.xml` currently reads
     `android:maxSdkVersion="31"` (it was only there for Nearby's old BLE-scan permission
     history, pre-API-32). **That cap needs removing** for a real GPS feature to work on
     API 32+, or the fix should be requested through a separate flow — decide which.
   - A location source: `android.location.LocationManager` (no new Gradle dependency,
     already in the Android SDK) vs `com.google.android.gms:play-services-location`
     (`FusedLocationProviderClient`, more accurate/battery-friendly, but a new dependency —
     the app already depends on Nearby's Play Services artifact, so this isn't
     unprecedented, but is a build-system change worth flagging, not silently adding).
   - Wire it into `MeshForegroundService`/`SensorBridge` (wherever the sensing tickers
     live) calling `agent.updateGpsFix(lat, lon)` on each location update — permission
     runtime request UI TBD (`MeshPermissions.kt` is the existing pattern to extend).
2. **`ClusterJson.kt`** — no `gpsLat`/`gpsLon` fields yet in the dashboard JSON.
3. **Dashboard UI** (`assets/dashboard/index.html`) — no rendering. Suggested (not started):
   a labelled row in the Inspector, likely a `<a href="geo:lat,lon">` link so a responder
   can open it in whatever maps app they have, offline-safe (we just supply the
   coordinate, not a map render). Caption it clearly as "GPS fix" distinct from the
   schematic 3D view / hop-count distance, so the three "where" signals (schematic
   position, hop count, real GPS) are never conflated.
4. **`fixtures.json`** — no GPS values added to the preview data.
5. **`docs/architecture.md`** — no ledger entry logging this yet (matches the file's own
   "log every ported formula/mechanism as it lands" rule — do this once the feature is
   actually wired end to end, not before).
6. **`TODO.md`** — the "Localisation is not solved" open-assumption line should probably
   get a note that a real (optional, nullable) GPS fix now exists alongside the zone-tag/
   hop-count proxies, once shipped.

## Separate, unrelated: reported UI bug, not investigated

User, verbatim: **"also in ui its centering to someother place and not to responders
node"** — about the 3D dashboard view (`assets/dashboard/index.html`'s canvas renderer).
Sounds like the camera's initial orbit target / pan origin doesn't line up with any real
node — possibly centering on the schematic building's `(0,0,0)` origin rather than the
responder's own device or the incident cluster. Not looked into at all yet. Camera state
lives in the `camera` object (`yaw/pitch/dist/panX/panY`) inside the `<script>` block —
start there.

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
