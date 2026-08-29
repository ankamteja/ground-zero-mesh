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

## Locked decisions

- **Kotlin + Jetpack Compose, one Android app**, three runtime-selectable roles: Node / Relay / Gateway.
- **Android only.** Nearby Connections (Android) and Multipeer Connectivity (iOS) do not interoperate — scoped out on purpose.
- **Google Nearby Connections, `P2P_CLUSTER`.** BLE discovery that auto-upgrades the data channel to Wi-Fi Direct / local WLAN.
- **AI is advisory only.** It never gates, delays, or reorders the deterministic relay and consensus path.
- **LoRa is in scope.** Hence the hard byte budget on the wire.
- **Wire format:** JSON on phone-to-phone hops, compact binary on LoRa hops — one `Envelope`, two projections, chosen from the transport's frame budget.
- **No raw audio / images / video on the mesh, ever.** Rich sensory data is turned into text before it crosses the wire.

## Modules

```
core/   pure JVM — no Android, no third-party runtime dependency.
        Tests run on any JDK 21 with no SDK.
app/    the Android app (enabled from phase2/nearby-transport onward).
```

## Build

```bash
./gradlew :core:test        # must stay green on every branch
```

Requires JDK 21. The `app/` module additionally needs the Android SDK.

## Contributing

Never push to `main`. One phase-ish concern per PR. `./gradlew :core:test` green before
opening one. Do not change `core/` without saying so in the PR title. See
[`TODO.md`](TODO.md) for the shared plan and [`docs/architecture.md`](docs/architecture.md)
for the ported-formula ledger.
