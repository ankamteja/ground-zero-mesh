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
- [x] `[AB]` `./gradlew :core:test` green (41 tests)

> Note: this baseline was seeded by [B] from the handover so the `app/` module has
> something to compile against. Types match the handover signatures verbatim. [A] owns
> the canonical Phase 0 and the L1/L2 logic on top — treat any overlap as a merge, the
> shapes are meant to hold still.

## Phase 2 — NearbyTransport  `[B]`  (highest risk, in progress)

- [ ] `[B]` `app/` module — AGP + Compose, `minSdk 24`, `implementation(project(":core"))`
- [ ] `[B]` uncomment `include(":app")` in `settings.gradle.kts` (same PR)
- [ ] `[B]` `NearbyTransport : Transport` over `P2P_CLUSTER` — advertise / discover / connect / send / receive
- [ ] `[B]` codec via `Codecs.forFrameBudget(maxFrameBytes)`, never hardcoded
- [ ] `[B]` per-API-level permission matrix + runtime request + rationale UI
      - pre-12: `ACCESS_FINE_LOCATION`
      - 31+: `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`
      - 33+: `NEARBY_WIFI_DEVICES`
- [ ] `[B]` foreground service + wake lock — scanning survives screen-off
- [ ] `[B]` `PeerTable` — decay toward neutral, never drop on silence (uses `core` `Peer`)
- [ ] `[B]` honour `Peer.SILENT_AFTER_MS`; `SILENT` vs `GONE` distinct; only `SILENT` from a timer
- [ ] `[B]` `StoreAndForward` — TTL'd buffer keyed `SHA256("zone:" + zoneId)`
- [ ] `[B]` VERIFY on 2 physical phones (oldest + newest): force-kill one, reconnect, prove replay. Record hardware + Android versions in the PR body.

## Phase 1 — Compose NodeScreen  `[B]` UI · `[A]` logic

- [ ] `[B]` SOS button + severity picker (drowning / entrapment / other)
- [ ] `[B]` live `DangerScore` + `AgentState`
- [ ] `[B]` "why" panel — render `ScoreExplanation` verbatim
- [ ] `[B]` runtime role switcher Node / Relay / Gateway
- [ ] `[A]` agent loop wiring the screen to `core`

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
- [ ] `[B]` Step 2 — `MeshForegroundService` builds `Gossip` + `NodeAgent`, feeds `GatewayController.start(ctx) { ResponderRanking.rank(gossip.clusters(), now) }`
- [ ] `[B]` Step 3 — `SensorBridge`: mic RMS / accel / light -> `senseTick`; snapshot -> `completeSensoryWindow`
- [ ] `[B]` Step 4 — role switch controls what runs (Gateway server+hotspot / Node agent+sensors / Relay gossip-only)
- [ ] `[B]` Step 5 — 3-phone field test; per-API permission matrix on oldest + newest phone; record hardware in PR body

## Phase 5 — LoRa bridge  `[B]`

- [x] `[B]` verify Meshtastic payload figure — it is **233**, not 237 (`docs/research/meshtastic-payload.md`); `CompactCodec.LORA_MAX_FRAME` set to 233
- [x] `[B]` `LoRaBridgeTransport : Transport` — ESP32 / Meshtastic over BLE-to-serial, `CompactCodec` frames (BLE GATT plumbing unverified on hardware)
- [ ] `[B]` bench against `SimTransport`-driven envelopes before real radio

## Open assumptions (name them in the deck and in UI copy)

- **Localisation is not solved.** No coordinate / RSSI / trilateration code exists. The cluster key is a coarse proxy — zone tag, RSSI ordering, hop-distance, or a responder-entered zone.
- **Battery.** Continuous advertise + scan over 24–30h is real drain. Duty-cycling is a documented trade-off, not a solved number.

## Docs

- [x] `[B]` log every ported formula in `docs/architecture.md` as it lands (L0/L3 wiring logged; keep appending)
