# Ground-Zero Mesh

A decentralised disaster-communication mesh for a **ground-zero communication blackout** —
cell towers down, substations submerged, fiber cut, emergency numbers unreachable after a
severe earthquake followed by flash flooding.

It behaves like an immune system: no single node is in charge. Every node observes
locally, scores its own situation, shares with neighbours, and the useful global
picture — where the surviving clusters are, who is closest to dying — emerges from that.

## Four layers

| Layer | Role |
|---|---|
| **L0 Transport** | Offline peer discovery + store-and-forward (Nearby Connections, LoRa bridge) |
| **L1 Node Agent** | observe → score → share → adjust → act |
| **L2 Propagation** | gossip, trust consensus, epistemology tiers, dedup |
| **L3 Responder Gateway** | perimeter dashboard, ranked survivor view |

## The cascade

- **Stage 0, t=0ms** — manual SOS. Synchronous broadcast at full danger, before anything is
  inferred.
- **Stage 1** — sensors (accelerometer, ambient light) fill a 16-float feature vector,
  `v_SLM`.
- **Stage 2, <1ms** — the deterministic Math Engine projects `v_SLM` into one signal
  (`Signal = W · v_SLM + w_IMU · a_mag`), an EMA smooths it (α = 0.35), and an 8-bit flag
  byte is compiled.
- **Stage 3, ~500ms–30s** — the enriched report re-broadcasts on the *same* incident
  timestamp, so it updates the existing incident rather than raising a second alert.

On-device inference never produces text — only the 16-float vector. The score comes from a
linear projection whose contributing feature can always be named.

## Locked decisions

- **Kotlin + Jetpack Compose, one Android app**, three runtime-selectable roles: Node / Relay / Gateway. The switch gates origination and sensing, not relaying — every role keeps carrying other people's reports.
- **Android only.** Nearby Connections (Android) and Multipeer Connectivity (iOS) do not interoperate — scoped out on purpose.
- **Google Nearby Connections, `P2P_CLUSTER`.** BLE discovery that auto-upgrades the data channel to Wi-Fi Direct / local WLAN.
- **AI is advisory only, structurally.** The L1 classifier and the L3 `RadminLlmSummarizer` both sit behind seams that take a decision already made and can only describe it — neither has a return path to gate, delay or reorder anything.
- **Trust decays far faster than it builds.** +0.05 gain, −0.35 loss — a corroborated relay earns influence slowly; one report that contradicts first-hand observation costs it about seven relays' worth of standing.
- **Localisation is not solved, and nothing pretends otherwise.** The Digital Twin places incidents from the zone tag alone; an incident whose zone names no floor renders as explicitly unplaced rather than being drawn on the ground floor.
- **LoRa is in scope.** Hence the hard byte budget on the wire.
- **Wire format:** JSON on phone-to-phone hops, compact binary on LoRa hops — one `Envelope`, two projections, chosen from the transport's frame budget. Every envelope carries the 8-bit sensory flag byte; the full feature vector is optional and rides only on the Stage 3 enriched broadcast.
- **No raw audio / images / video on the mesh, ever.** Rich sensory data is reduced to an 8-bit flag byte plus, optionally, a quantised 16-float `v_SLM` vector before it crosses the wire — never the raw signal itself.

## Modules

```
core/   pure JVM — no Android, no third-party runtime dependency.
        Tests run on any JDK 21 with no SDK.
        agent/       NodeAgent (4-stage cascade), MathEngine, DangerScore, SensoryClassifier
        propagation/ Envelope, Gossip, TrustConsensus, DedupCluster/IncidentCluster, codecs
        gateway/     ResponderRanking, DigitalTwin, RadminLlmSummarizer
        simulation/  SimulationRunner — the whole stack over a simulated mesh, no device needed
app/    the Android app.
        transport/   NearbyTransport, LoRaBridgeTransport, GossipOriginTransport, StoreAndForward
        sensors/     SensorBridge, SensorNormalisation — accelerometer + light into the agent
        mesh/        MeshStack — the process-wide handle the service publishes
        gateway/     GatewayServer (NanoHTTPD) + the on-phone responder dashboard
docs/   docs/architecture.md — the ported-formula ledger.
        docs/simulation_dashboard.html — the 3D digital-twin viewer for SimulationRunner's output.
```

## Build

```bash
./gradlew :core:test                                    # must stay green on every branch
./gradlew :app:testDebugUnitTest                         # app unit tests, no device needed
./gradlew :core:runSim                                   # the whole stack, printed
./gradlew :core:runSim -PsimArgs="--json docs/simulation-twin.json"   # + a dashboard snapshot
```

Requires JDK 21. The `app/` module additionally needs the Android SDK.

## The three-phone demo

Three phones and a laptop, in one room about 30 m long. Each phone picks its role once on
the first screen; the choice survives a restart (`RoleStore`), so it does not need setting
again mid-demo.

| Where | Role to pick | What the screen does |
|---|---|---|
| One end of the room | **victim** | Severity, then one large SOS button. Details (score, "why", sensory slider) are behind a disclosure. |
| Middle, ~15 m along | **relay** | No SOS. Live counters — received / relayed onward / duplicates suppressed / undecodable / buffered — and a log of recent frames. |
| Other end, with the laptop | **responder** | Starts the board server. The laptop reads it. |

The spacing is the demo, not staging. Victim and responder must be far enough apart that
they cannot hear each other directly, so the SOS only arrives by way of the relay — which is
what makes the board's "relayed" reading true rather than decorative.

1. Install on all three phones, grant the Nearby permissions, pick the roles above.
2. On the responder phone, open its Wi-Fi hotspot **by hand** (Android does not allow an app
   to open one without system permissions), then press *Start responder server*.
3. Join that hotspot from the laptop and browse to `http://<responder-phone-ip>:8080/`.
4. Press SOS on the victim phone.

What to point at, on the one dashboard page:

- The relay's counters move — the report passed through a phone the responder cannot hear.
- In the **Board** list, the incident's evidence reads `relayed`, not `first-hand`: the
  gateway is holding testimony, and the first-hand gate says so.
- In the **3D view**, the relay appears as a carrier on the outer ring with a packet running
  the edge to the incident. A victim phone whose zone is still `unset` renders parked outside
  the building and labelled `unplaced` rather than being drawn on the ground floor.
- Tap a marker (or a board entry) to open the **Inspector** — the flag byte, the `v_SLM`
  bars, and the reasons the incident ranks where it does.

## What's real vs. simulated right now

Every layer above is implemented and unit-tested (`core` alone is 150+ tests). What has
**not** been run is the actual three-phone field test: no Android hardware is attached to
the development machine, and Nearby Connections cannot run between emulators (no real BT /
Wi-Fi Direct radios). `MeshFieldSimulationTest` stands in for it over a simulated network and
proves the mesh logic; it proves nothing about Nearby's discovery or permission behaviour,
which is where a live demo is most likely to fail silently. See `TODO.md` for the exact gap.

## Contributing

Never push to `main`. One phase-ish concern per PR. `./gradlew :core:test` green before
opening one. Do not change `core/` without saying so in the PR title. See
[`TODO.md`](TODO.md) for the shared plan and [`docs/architecture.md`](docs/architecture.md)
for the ported-formula ledger.
