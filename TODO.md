# Shared plan

Markers: `[A]` JVM logic owner · `[B]` Android/device owner · `[AB]` both.
Tick items in the same PR that does the work.

---

## Phase 0 — the contract

- [x] `[A]` `core` pure-JVM module scaffold
- [x] `[A]` `NodeId`, `EpistemologyTier`, `Severity`, `Peer`
- [x] `[A]` `Envelope` with in-constructor byte ceilings, `dedupKey`, `effectiveTier`
- [x] `[A]` `EnvelopeCodec` / `JsonCodec` / `CompactCodec` / `Codecs.forFrameBudget`
- [x] `[A]` `Transport` seam + `SimNetwork` / `SimTransport` (virtual clock, adjacency, latency, loss)
- [x] `[A]` `DangerScore` (EMA + two thresholds) / `ScoreExplanation` / `AgentState` / `SosInput`
- [x] `[AB]` `./gradlew :core:test` green (150 tests in `core`, 196 across `core` + `app` as of PR #17 — grows with every phase, re-check before trusting this number)

> Note: this baseline was seeded by [B] from the handover so the `app/` module has
> something to compile against. Types match the handover signatures verbatim. [A] owns
> the canonical Phase 0 and the L1/L2 logic on top — treat any overlap as a merge, the
> shapes are meant to hold still.

## Phase 2 — NearbyTransport  `[B]`  (highest risk, in progress)

- [x] `[B]` `app/` module — AGP + Compose, `minSdk 24`, `implementation(project(":core"))`
- [x] `[B]` uncomment `include(":app")` in `settings.gradle.kts` (same PR)
- [x] `[B]` `NearbyTransport : Transport` over `P2P_CLUSTER` — advertise / discover / connect / send / receive
- [x] `[B]` codec via `Codecs.forFrameBudget(maxFrameBytes)`, never hardcoded (`Gossip` derives it from `transport.maxFrameBytes`)
- [x] `[B]` per-API-level permission matrix + runtime request + rationale UI (`MeshPermissions`)
      - pre-12: `ACCESS_FINE_LOCATION`
      - 31+: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
      - 33+: `NEARBY_WIFI_DEVICES`
- [x] `[B]` foreground service + wake lock — scanning survives screen-off
- [x] `[B]` `PeerTable` — decay toward neutral, never drop on silence (uses `core` `Peer`)
- [x] `[B]` honour `Peer.SILENT_AFTER_MS`; `SILENT` vs `GONE` distinct; only `SILENT` from a timer
- [x] `[B]` `StoreAndForward` — TTL'd buffer keyed `SHA256("zone:" + zoneId)`
- [ ] `[B]` VERIFY on 2 physical phones (oldest + newest): force-kill one, reconnect, prove replay. Record hardware + Android versions in the PR body.

## Phase 1 — Compose NodeScreen  `[B]` UI · `[A]` logic

- [x] `[B]` SOS button + severity picker (drowning / entrapment / other)
- [x] `[B]` live `DangerScore` + `AgentState`
- [x] `[B]` "why" panel — render `ScoreExplanation` verbatim
- [x] `[B]` runtime role switcher Node / Relay / Gateway
- [x] `[A]` agent loop wiring the screen to `core`

## Phase 4 — L3 Responder Gateway  `[B]`

- [x] `[B]` `GatewayServer` — NanoHTTPD + SSE on the gateway phone
- [ ] `[B]` gateway phone opens its own Wi-Fi hotspot; laptop joins, browses in, zero install (device test — Phase 6 Step 5)
- [x] `[B]` `dashboard/index.html` — static, inline JS, no build step
- [x] `[B]` ranked clusters (not raw report spam) by severity + trust + recency; `effectiveTier` shown per entry — now `core`'s `ResponderRanking` (Phase 6)
- [x] `[B]` explicit scarcity budget (~14 actions / window) — `ResponderRanking.BUDGET_ACTIONS`
- [x] `[B]` `AiAdvisor` — advisory summary on top of deterministic rank; stubbable; offline; never gates — now `GatewayServer.advise()` one-liner (Phase 6)

## Phase 6 — wire the gateway to core  `[B]`

- [x] `[B]` `GatewayServer` / `GatewayController` take `() -> List<RankedIncident>` (core type)
- [x] `[B]` `ClusterJson` serialises `RankedIncident` to the dashboard field names + `standing` / `dispatchable` / `priority` / `reasons`
- [x] `[B]` delete app-side `ClusterRanker` / `SurvivorCluster` / `SurvivorReport` / `AiAdvisor` — `core` is canonical
- [x] `[B]` advisory is a deterministic one-liner in the gateway, offline, never reorders
- [x] `[B]` dashboard detail row for `standing` + `reasons`; `fixtures.json` updated to the live shape
- [x] `[AB]` tests: hand-built `IncidentCluster` -> `ResponderRanking.rank` -> assert JSON shape + order
- [x] `[B]` Step 2 — `MeshForegroundService` builds `Gossip` + `NodeAgent`, feeds `GatewayController.start(ctx, MeshStack::rankedBoard)`
  - [x] `[B]` `GossipOriginTransport` — the agent's own sends go through `Gossip.originate`, one frame to the radio, no self-echo re-forward
  - [x] `[B]` `MeshStack` process singleton — service installs, UI + gateway borrow, serialised access
  - [x] `[B]` SOS button reaches the agent; the screen says when a press did *not* reach the wire
  - [ ] `[B]` `NodeAgent.livenessTick` still not driven (`PeerTable.decayTick` covers peer decay) — see `docs/architecture.md`
- [x] `[B]` Step 3 — `SensorBridge`: accel / light -> `senseVector`; window peaks -> `completeSensoryWindow`
  - [x] `[B]` microphone landed (2026-08-30) — `core`'s `AudioFeatures` (hand-rolled radix-2
        FFT, spectral flatness, crest factor -> water / voice / structural, 19 tests against
        synthetic signals) plus `app`'s `AudioBridge` (`AudioRecord` at 16 kHz, pull-model),
        `RECORD_AUDIO` + `FOREGROUND_SERVICE_MICROPHONE` + `foregroundServiceType=
        "connectedDevice|microphone"`, an optional grant on `VictimScreen`. Went in as
        spectral features, not RMS: a single loudness scalar cannot separate rushing water
        from a shout from a crack, and those are three different tokens on a responder's
        board. **`core` is tested and green; the `app` half has never compiled here** — no
        Android SDK in this environment, same limitation as the GPS and LAN-relay landings.
- [x] `[B]` Step 4 — role switch controls what runs (Gateway server / Node agent+sensors / Relay gossip-only)
  - [ ] `[B]` gateway hotspot still cannot be opened programmatically without system permissions — responder opens it by hand
- [ ] `[B]` Step 5 — 3-phone field test; per-API permission matrix on oldest + newest phone; record hardware in PR body
  - [x] `[B]` stand-in: `MeshFieldSimulationTest` — A—B—C over `SimNetwork`, SABDA at the gateway, store-and-forward replay after a partition
  - [x] `[B]` demo kit: per-role screens (victim SOS / relay traffic log / responder server) + `MeshStack` activity log — see the runbook in `README.md`
  - [ ] `[B]` the real 3-phone run — still not done, and Nearby's discovery/permission path is untested by the stand-in

## Phase 5 — LoRa bridge  `[B]`

- [x] `[B]` verify Meshtastic payload figure — it is **233**, not 237 (`docs/research/meshtastic-payload.md`); `CompactCodec.LORA_MAX_FRAME` set to 233
- [x] `[B]` `LoRaBridgeTransport : Transport` — ESP32 / Meshtastic over BLE-to-serial, `CompactCodec` frames (BLE GATT plumbing unverified on hardware)
- [ ] `[B]` bench against `SimTransport`-driven envelopes before real radio

## Follow-ups from the post-Part-II audit (2026-08-30)

- [ ] `[A]` no mesh-wide "resolved" broadcast exists yet. `MeshStack.markPeerFound` /
      `GatewayServer`'s `POST /resolve` only reach the gateway phone's own direct
      `PeerTable` — a victim known to the board only through relay is unaffected by a
      responder's "found / safe" tap. Needs a new envelope/gossip message type that
      propagates a GONE signal hop-by-hop.
- [ ] `[B]` `Peer.healthy` still has no production reader — related to the above; likely
      resolves once something downstream actually consumes peer liveness/health.
- [x] `[A]` `IncidentCluster.reportCount` (closed 2026-08-30) — a real per-`ingest` fold
      count, distinct from `corroborators.size`. `ClusterJson`'s `reportCount` field now
      uses it.
- [x] `[B]` hop-count "distance" surfaced on the board (closed 2026-08-30) —
      `IncidentCluster.minHops` was already tracked but only ever buried inside the
      `reasons` text. `ClusterJson` now emits it as its own `minHops` field; the dashboard
      shows a `distance (hops)` row in the Inspector and a compact tag on each board row.

## Follow-ups from the first real-hardware test session (2026-08-30)

- [x] `[B]` missing `INTERNET` permission — `GatewayServer`'s `NanoHTTPD` raw `ServerSocket`
      failed with `SocketException: EPERM` on every real device; Nearby itself never needed
      it (IPC to Play Services, not a socket), so it was never in the manifest. Added.
- [x] `[B]` NanoHTTPD auto-gzips any `text/*`/`*/json` response once the client accepts it
      (every browser; not `curl` without `--compressed`) — `GZIPOutputStream` only flushes
      on `finish()`, which never fires for `/events`' intentionally-never-closing SSE
      stream, so the dashboard connected and then silently never updated. Disabled gzip
      server-wide via `GatewayServer.useGzipWhenAccepted`.
- [x] `[A]`/`[B]` **GPS location — landed end to end.** `core`: `Envelope.gpsLat`/`gpsLon`,
      `CompactCodec` wire format `0x03` (8 bytes, real f32, no quantisation), `JsonCodec`,
      `IncidentCluster` merge (`envelope.gpsLat ?: existing.gpsLat`), `NodeAgent.
      updateGpsFix`/`lastGpsFix`/`buildEnvelope`. `app`: `GpsBridge` (new — `LocationManager
      .GPS_PROVIDER` only, no network-location fallback, no new Gradle dependency), `MeshStack
      .updateGpsFix` passthrough, `MeshForegroundService` wiring (incl. the permission-
      granted-after-service-already-running retry via `onStartCommand`), `MeshPermissions
      .LOCATION_PERMISSION` (deliberately separate from `runtimePermissions()` — GPS is
      optional, Nearby is not), `VictimScreen`'s own grant UI, `AndroidManifest.xml`'s
      `ACCESS_FINE_LOCATION` cap removed, `ClusterJson` + dashboard rendering (a captioned
      "GPS fix" Inspector row with a `geo:` link, a board-row tag), `fixtures.json` sample
      data. Chosen approach, confirmed with the user: GPS **when available, honestly
      nullable** — never required, never fabricated, falls back to the zone tag / hop count.
      Reasoning: GPS fails exactly where victims usually are — indoors, trapped, underground
      — so a sometimes-null-sometimes-wrong location field is worse than none, because a
      responder trusts a number on a screen. See the GPS ledger entry in
      `docs/architecture.md` for the full landing. **Not yet verified on a real device or
      compiled in CI** — this environment has no Android SDK configured; `core` is tested
      and green, the `app`-side changes are reviewed but unbuilt.
- [x] `[B]` **3D dashboard camera centering, likely resolved as a side effect (2026-08-30) —
      please confirm on device.** Original report: "in ui its centering to someother place
      and not to responders node." Root cause: there was no responder-node marker at all —
      the camera's orbit target (`camera` object, `assets/dashboard/index.html`) was always
      the schematic origin `(0,0,0)`, but nothing was ever drawn there, so the view read as
      centering on empty space. The "you are here" marker added below now sits at that exact
      point, so the camera's existing orbit target and the responder's own position are the
      same point by construction — no camera code changed. Flagged `[x]` provisionally;
      confirm the view now reads correctly rather than treating this as fully closed.
- [x] `[B]` **repeated SOS presses were raising a new incident each time, not updating one
      (2026-08-30).** Found on the first real two-phone run. `NodeAgent.raiseSos` now reuses
      the active incident's dedup-key timestamp on a re-press (same mechanism the Stage 3
      re-broadcast already used, now also triggered by the person, not only the sensory
      window); a press after `clearIncident` still starts a genuinely new incident. A second
      bug caught in the same fix: the emitted envelope was stamping the raw press time even
      after this change, which would have silently defeated it — see `docs/architecture.md`.
- [x] `[B]` **no visible connection between the responder and the reports on its own board
      (2026-08-30).** Side effect of the digital-twin carrier fix above: excluding the
      origin from its own corroborator list means a direct link (no relay) now draws zero
      carrier lines, so a two-phone run with no relay showed no connection at all. Fixed by
      adding an explicit `self` marker (`MeshStack.localNodeId()` -> `GatewayServer.payload`)
      plus a distinct solid connection line from it to every incident, separate from the
      dashed relay-corroboration lines. See `docs/architecture.md`.
- [ ] `[A]` **Laptop-as-relay, landed and now build-verified, still not run on real
      hardware.** The hardware session had only two phones (victim, responder), no third
      for the relay role. `core`: `TcpTransport`/`TcpRelayServer` (a real TCP transport,
      star topology, genuinely tested end to end with real sockets — `TcpTransportTest`)
      and `:core:runRelay` (`TcpRelayMain`, a runnable laptop program, no `NodeAgent`
      needed since a relay only carries). `app`: `RadioTransport` interface,
      `LanRelayTransport` (thin wrapper, Android-free, its own real-socket test),
      `RelayHostStore`, a "Laptop relay" field on `MainActivity`,
      `MeshForegroundService.buildRadio` branching on it. Confirmed with the user: replace
      Nearby for this test, not run alongside it — a star topology is the only way to
      guarantee one path between victim and responder without physically separating two
      phones by 15–30m. **Now compiled and tested for the first time** (this session got
      an Android SDK): `LanRelayTransportTest` was written against `kotlin.test`, which
      only `core` carries — `:app` only has JUnit4 — so it failed to compile until rewritten
      to match every other app test's convention. That surfaced a real bug underneath:
      `LanRelayTransport`'s peer-table bookkeeping only happened as a side effect of the
      *caller* registering `onReceive`/`onPeerConnected`, unlike `NearbyTransport`, which
      populates its own peer table unconditionally from its SDK callbacks. Dormant in the
      one production call site (`MeshForegroundService` always registers both before
      `start()`), but a real "wired but consumer-order-dependent" gap — fixed by moving the
      registration into `LanRelayTransport`'s own `init`. See the ledger entry in
      `docs/architecture.md`. `./gradlew test` (both modules) and `:app:assembleDebug` are
      green. **Still not run on real hardware** — first real test: `./gradlew
      :core:runRelay`, point both phones' "Laptop relay" field at it, press SOS, watch the
      relay terminal's `relayed=` counter move.

## Open assumptions (name them in the deck and in UI copy)

- **Localisation is mostly not solved.** No RSSI / trilateration code exists anywhere. A
  real, optional GPS fix now captures and sends end to end (see the follow-up above) — when
  one is available. It stays the minority case by design: GPS fails exactly where victims
  usually are (indoors, trapped, underground), so the zone tag / hop-distance proxy remains
  the primary signal for most incidents, not a fallback for a feature that mostly works.
- **Battery.** Continuous advertise + scan over 24–30h is real drain. Duty-cycling is a documented trade-off, not a solved number.

## Docs

- [x] `[B]` log every ported formula in `docs/architecture.md` as it lands (L0/L3 wiring logged; keep appending)
