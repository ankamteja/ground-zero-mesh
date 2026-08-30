# Multi-node relay chain — runbook

Written for the SDK box (the machine that can build `:app` and drive phones). Everything here
is on `main` as of the multi-node relay work; `:core` is green.

**What changed:** the laptop relay used to be a lone hub — a star, two hops wide. It can now
chain. `TcpRelayMain --link host:port` makes a relay serve its own port *and* dial the next
relay along, with `CompositeTransport` fanning one `Gossip` over both.

**Why you care:** a genuine multi-hop board reading no longer needs three phones or a working
emulator. **Two phones and this laptop are enough.** That routes around the emulator SIGSEGV
entirely — no Setup B, no `sdkmanager`, no Vulkan.

---

## The short version

```
victim phone ──► relay A (7801) ──► relay B (7802) ──► responder phone
                 hops=1             hops=2             hops=3
```

Both relays run on this one laptop as two separate JVM processes. The responder's board shows
`3 hops`, tier `SABDA`, and relayed corroboration — the evidence the dashboard was built to
display, which a two-hop star cannot produce.

---

## Run it

### 1. Start the chain (two terminals on the laptop)

```bash
./gradlew :core:runRelay -PrelayArgs="7801"
./gradlew :core:runRelay -PrelayArgs="7802 --link localhost:7801"
```

Start them in either order — `TcpTransport` reconnects on its own, so a relay dialled before
it exists connects when it appears. Relay B prints `linked to relay <id> at localhost:7801`
once the chain closes; relay A prints `connected: <B's id>`. If you do not see both lines, the
chain is not formed and nothing below will work.

Each relay persists its own id in `.relay-node-id-<port>` — keyed by port on purpose, because
two relays sharing one id file would be two nodes claiming one `NodeId`, which quietly breaks
corroboration counting and the peer table.

### 2. Point the phones at *different* relays

This is the whole trick. Both phones on the same relay is still a star.

| Device | "Laptop relay (optional)" field | Role |
|---|---|---|
| Realme (victim) | `<laptop-LAN-ip>:7801` | victim |
| S24 (responder) | `<laptop-LAN-ip>:7802` | responder |

`ip -4 addr` for the laptop's address on the phone hotspot — it was `172.20.10.2` last time,
re-check after re-pairing.

### 3. The two gotchas that will cost you an hour

- **Relay host is read once, at service `onCreate`.** Set the field, then **force-stop and
  reopen the app**. Changing it in a running app does nothing.
- **Set the relay host _before_ granting Nearby permissions** — the service starts on grant.
- LAN-relay mode **replaces** Nearby. Every device in the test needs the field set, or it
  talks to the radio and sees nobody.

### 4. Press SOS, then check three places

```
relay A terminal:  relayed= climbs, duplicates= climbs by one per report
relay B terminal:  relayed= climbs
responder board:   incident shows 3 hops, tier SABDA, evidence "relayed"
```

`duplicates=` climbing on relay A is correct and is the loop suppression working —
`TcpRelayServer` echoes to the connection a frame arrived on, and `Gossip`'s `propagationKey`
kills the echo. A ring is safe for the same reason; `RelayChainTest` pins it.

### Longer chains

Add relays the obvious way. Three gives `hops=4`:

```bash
./gradlew :core:runRelay -PrelayArgs="7803 --link localhost:7802"
```

---

## Expected numbers, and the off-by-one

For `victim -> A -> B -> C -> responder` with a starting `ttl=8`, the responder receives
**`hops=4`, `ttl=5`**.

`hops` counts links. `ttl` counts **forwards, not links** — the victim's own transmission
spends none, so three relays cost three. If you see `ttl=4` and expected it, you have the
off-by-one backwards; `MeshPropagationTest` and `RelayChainTest` both pin the real one.

Anything arriving at `hops > 0` is `SABDA` (testimony), never first-hand — the receiver
decides that from its own position rather than trusting the sender's stamped `hops`. So a
multi-hop chain never delivers first-hand standing to the responder. That is correct, not a
bug, but it is worth knowing before you read the board.

---

## Verified before this was written

- `RelayChainTest` — 7 tests, real sockets on ephemeral ports: two- and three-relay chains
  with per-link hop counts, TTL exhausting mid-chain, ring echo suppression (each relay
  forwards exactly once), and `CompositeTransport`'s fan-out / shared-id / narrowest-budget
  rules.
- Three real JVM relay processes on `7801 <- 7802 <- 7803`, driven by a Python client speaking
  `TcpFraming` and the `JsonCodec` shape directly — deliberately not our own transport code —
  delivering `hops=4 ttl=5`, `relayed=1` on every relay, zero duplicate deliveries.

**Not verified:** any of it with real phones. `:core` is green here; this box has no Android
SDK, so `:app` has never compiled on it. Step 2 onward is yours.

---

## Also on this branch, unrelated to the chain

The microphone landed — `AudioFeatures` (core, spectral: FFT, flatness, crest factor →
water / voice / structural, 19 tests) and `AudioBridge` (app, `AudioRecord` at 16 kHz). Three
of the sixteen feature slots carrying 0.45 of the weight vector had no writer before; they
were always `0.0`.

Two things to check on your box, since they are `:app` and I could not build them:

1. `AndroidManifest.xml` now needs `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, and
   `foregroundServiceType="connectedDevice|microphone"`. Without the type, background
   `AudioRecord` returns **silence rather than an error** — the same silent-failure shape as a
   missing Nearby permission.
2. `VictimScreen` has an "Add mic" grant row mirroring the GPS one. If the headless grant is
   easier: `adb shell pm grant org.groundzero.mesh android.permission.RECORD_AUDIO`.

The mic also partly mitigates a real problem worth knowing about: a phone that is still and
silent falls out of `ALARM` within ~1.5s of the SOS and its heartbeat goes quiet ~10s later,
because `heartbeatTick` gates on sensor posture rather than on `activeIncident != null`. Audible
water or voices keep the score above the watch threshold and the heartbeats flowing. Silence
does not. The real fix is still open.
