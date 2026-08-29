# Architecture & ported-formula ledger

Every formula or mechanism borrowed from a reference implementation is logged here as it
lands: what it came from, what changed, and why. Two read-only conceptual sources:

- **ANW** — an earlier mesh in Go.
- **Pramana** — an earlier risk-manager in Python.

Nothing is forked; everything below is reimplemented from scratch.

---

## L0 Transport

### `Transport` seam

Byte-oriented, not envelope-oriented. One `Envelope` has two wire forms, so codec choice
lives above the transport and is driven by `maxFrameBytes`. Implementations pick a codec
with `Codecs.forFrameBudget(maxFrameBytes)` and never hardcode one.

### `SimNetwork` / `SimTransport`

New. Deterministic in-memory network: virtual clock (no wall-clock sleeps), configurable
adjacency, fixed per-hop latency, uniform packet-loss rate. Mesh logic is developed
against this before a real transport is swapped in.

---

## Propagation

### `Envelope` byte ceilings — enforced in the constructor

New rule, no reference precedent. An envelope that could not fit a LoRa frame fails at
construction, not at transmit time. `CompactCodec.frameSize()` computes the exact encoded
size from the fields; the constructor rejects anything over `LORA_MAX_FRAME`.

### `dedupKey = nodeId + timestamp`

From ANW's dedup approach. Unchanged in spirit: the refined Stage-3 broadcast deliberately
reuses the original incident timestamp so downstream layers update the existing cluster
instead of raising a second alert for the same person.

### `EpistemologyTier` and `effectiveTier`

Concept from Pramana (Nyaya pramanas: pratyaksa / anumana / sabda). Reimplemented as a
three-value enum. New addition: `effectiveTier` downgrades any claim held at `hops > 0` to
`SABDA` (testimony) — the envelope keeps the origin's claim in `tier`, but a downstream
holder only has hearsay. UIs show the effective tier.

### `asReceived()` — the receiver decides its own epistemic position

New rule, no reference precedent. `effectiveTier` downgrades a claim held at `hops > 0`,
which is correct but was not reachable: an envelope arrives over a link carrying whatever
hop count the *sender* stamped, and the origin stamps zero. A first-hand SOS therefore
arrived at its direct neighbour reading `effectiveTier == PRATYAKSA`, so the first-hand
gate failed open on the first hop — the most common hop in the mesh. Demonstrated over a
`SimNetwork` link before the fix, and now pinned by `FirstHandGateTest`.

`Envelope.asReceived()` is called once on ingest and guarantees `hops >= 1`. The guarantee
deliberately does not come from the hop count alone, because `hops` is a sender-controlled
field: a node that is faulty, spoofed, or simply never increments it would otherwise
arrive looking like direct observation. The first-hand gate is precisely the tier a bad
actor wants to get past — it is the one that authorises the irreversible action — so the
receiver decides from the fact that the bytes crossed a radio, not from a number the
sender chose.

### TTL counts forwards, not links

The origin's own first transmission does not spend TTL, so a TTL of *n* reaches *n+1*
links out. Pinned by `MeshPropagationTest` rather than left implicit, because it is the
off-by-one that decides whether a TTL chosen in the field covers the building it was meant
to.

### `Severity` vs `dangerScore`

Kept strictly orthogonal (Pramana collapsed neither, and neither do we). `dangerScore` is
confidence that something is wrong; `severity` is how fast the person dies. A ranking that
multiplied them would sort a certain observation of a submerging rooftop below a panicking
report from a dry stairwell.

### `CompactCodec` — LoRa projection

New. Fixed-layout big-endian binary, no self-describing overhead. `LORA_MAX_FRAME = 233`
(see `docs/research/meshtastic-payload.md` — the handover assumed 237; the firmware
constant is 233).

### `JsonCodec` — phone-to-phone projection

New. Hand-rolled minimal JSON reader/writer because `core` carries no third-party runtime
dependency. Covers exactly the `Envelope` shape.

---

## Agent

### `DangerScore` — EMA + two thresholds

Inspired by Pramana's risk EMA; simplified. Score is an exponential moving average of
signals in `0..1` (`alpha = 0.4`). A slow baseline tracks it at ~1/20 the rate. Two
thresholds (`watch = 0.35`, `alarm = 0.70`) map the score to `CALM` / `WATCH` / `ALARM`.
No AI at this layer. `explain()` returns the score, baseline, last raw signal and a
plain-language reason for the "why" panel.

### `Peer` liveness — `SILENT_AFTER_MS`

From ANW's peer table (`{node_id, address, last_seen, ok, fail}`, health rule
`fail < 5 || ok > 0`). **Changed:** ANW declared a peer dead after 6 seconds of silence.
Here `SILENT_AFTER_MS = 4 minutes`, and `SILENT` and `GONE` are separate states — only
`SILENT` is reachable by a timer. A trapped, OEM-throttled phone on 4% battery is silent
for minutes and its owner is alive; reporting it as gone is the worst error this system
can make. Peers decay toward neutral on silence, never dropped.

---

## L3 Responder Gateway

### `ClusterRanker` — clusters, then a ranked list under a scarcity budget

New, no reference precedent. Raw reports are folded by `zone` (the coarse localisation
proxy) into one `SurvivorCluster` each: severity = most urgent report present, tier =
strongest evidence present, `corroboration` = distinct origin nodes, `dangerScore` = max,
`lastSeenSecondsAgo` = freshest.

Rank order (strict, deterministic, AI plays no part):

1. `severity.rank` ascending — imminent drowning first.
2. trust descending, where `trust = corroboration + tierWeight(effectiveTier)`
   (`PRATYAKSA` 2, `ANUMANA` 1, `SABDA` 0).
3. `lastSeenSecondsAgo` ascending — freshest next.
4. `dangerScore` descending — confidence as the final tiebreak.

The first `actionBudget` (default **14**) clusters get `recommendedActionRank = 1..N`; the
rest are still listed with `null`. Responders have finite boats and teams — an unbounded
alert list is not a triage tool.

### `AiAdvisor` — advisory only

Layered on top of the finished ranking. `NoopAiAdvisor` is deterministic, offline, and the
default. It never gates, delays, or reorders what `ClusterRanker` produced; the dashboard
renders the ranked list first and folds the advice string in beside it.

### Dashboard `effectiveTier`

The dashboard shows `effectiveTier` (already downgraded for relay hops), never the origin's
claimed `tier`, so a responder can tell corroborated from single-unconfirmed at a glance.

---

## L0 LoRa bridge

### `MeshtasticFrame` — stopgap serial framing

New, explicitly temporary. Wraps each opaque `CompactCodec` frame as
`[0xA5 0x5A][srcNodeNum u32][len u16][payload]` so the BLE-to-serial bridge can carry a
sender identity and delimit frames. `Reassembler` accumulates BLE-notification chunks and
resynchronises on the magic bytes.

**Changes once the Meshtastic protobufs are linked:** a real Meshtastic `MeshPacket`
carries `from`/`to` (32-bit node numbers) in its own header, *outside* the 233-byte
`Data.payload`. At that point this 8-byte header goes away and
`LoRaBridgeTransport.maxFrameBytes` rises from `233 - 8` to the full `233`.

### `LoRaBridgeTransport`

`Transport` over BLE GATT to the Nordic UART Service (`6e400001-…`), RX = `…0002…`,
TX notify = `…0003…`. MTU negotiated to 247; outbound datagrams chunked to the negotiated
payload and written one acked op at a time. Node numbers map to `NodeId` by zero-extending
the 32-bit value into the 48-bit space. The GATT plumbing is unverified against hardware —
every such point is marked `VERIFY(hardware)` in the source.
## L1 Node Agent (Phase 1)

### Three independent tickers

Ported from the reference implementation's per-concern tickers rather than a single
lockstep loop. `NodeAgent` exposes `senseTick`, `heartbeatTick` and `livenessTick`
separately, and sensing deliberately does **not** transmit.

The reason is not tidiness. A lockstep loop couples how fast the device notices things to
how fast it talks, so duty-cycling the radio to save battery would also blind the sensor.
On a phone at 4% battery those two rates *will* be tuned differently.

`core` carries no coroutine dependency and the agent owns no threads: whoever holds the
schedule drives the ticks. That keeps the module pure-JVM and makes the agent
deterministically testable, with no wall-clock sleeps anywhere in the suite.

### Immediate override — divergence from the reference implementation

`raiseSos()` broadcasts synchronously at `dangerScore = 1.00`, bypassing the EMA entirely,
and forces the hysteresis gate to `ALARM`. Ambient sensing has to earn its way up the
score machine; a person pressing the button does not. Measured in `NodeAgentTest`: a
maximal sustained reading needs several sense ticks to reach `ALARM`, and those ticks are
the entire margin for someone going under water.

### `HysteresisGate` — ours, not ported

`DangerScore.state()` maps a score to a posture with bare thresholds, which is correct for
reading the score at an instant but not for *driving* anything: a score resting near a
threshold crosses it repeatedly, and every crossing is a state change the agent would
gossip. Flapping at 0.70 turns one event into a broadcast storm, on battery, in a
blackout. Transitions therefore use the plain thresholds on the way up and
`threshold - deadband` on the way down. Thresholds are the reference implementation's; the
0.05 deadband is ours.

### Event taxonomy — structure ported, weights deliberately inverted

The taxonomy is the reference implementation's. The weights are not, and the inversion is
the point: a **gradual drift** upward is water rising, and a **sudden drop** is a device
that died or a person who stopped moving. Both outrank any spike here, where in a
neighbourhood-watch mesh a sustained spike is the loud event and a drift is background.
Getting these the wrong way round would make the agent quietest exactly when it should be
loudest, so the ordering is asserted in a test rather than left to constants nobody
rereads.

---

## Phase 3.5 — two-stage sensory pipeline

### The classifier is a seam, and that is the design

`SensoryClassifier` is a single-method interface with a deterministic implementation
(`DeterministicSensoryClassifier` — thresholds, no model). Stage 2 is the only part of the
pipeline that might need a quantised model, and the part most likely to run out of RAM,
battery or time. Behind a seam, the deterministic version ships now and a real model drops
in later without touching the protocol, the envelope, or anything downstream.

`NodeAgent.completeSensoryWindow` treats a classifier that returns null, blocks, or
**throws** as the same thing: no enrichment. The Stage 1 broadcast has already gone.
Asserted directly — a classifier that throws leaves the override standing and the loop
untouched.

### Max-fuse, not sum

`SensorySummary.fusedConfidence` is the **maximum** across channels. Ported as a measured
finding, not a stylistic choice: each channel is near-blind exactly where another is
strong, so summing or averaging lets two channels that saw nothing outvote the one that
saw something. A phone pinned face-down in a dark basement has a blind camera and a
deafened microphone; its IMU is the only witness.

---

## L2 Propagation (Phase 3)

### Propagation dedup is not incident dedup

The most consequential thing found while building this layer. `Envelope.dedupKey`
identifies the *incident*, and the Stage 3 enrichment reuses it on purpose so downstream
layers update the same person rather than inventing a second one.

Suppressing forwards on incident identity alone therefore has a nasty consequence: the
first relay has already seen the incident, so it drops the refined report on the floor and
**the enrichment never reaches the responder** — the entire point of the sensory window,
lost silently, one hop from the victim. Caught by the end-to-end test, not by any unit
test, because each layer was individually correct.

`Gossip.propagationKey()` therefore keys on incident identity *plus the content that can
change* (severity, score, summary, views), excluding `hops` and `ttl` since those change on
every forward. Same incident with new evidence travels once more; a byte-identical repeat,
however many paths deliver it, does not.

### Trust asymmetry, exercised rather than assumed

`TrustConsensus` ports the reinforcement rule with gain 0.02 against loss 0.05. The
handover is explicit that this must be *exercised adversarially* rather than assumed
correct because the constants were copied accurately — copying a formula right and wiring
it up wrong is the failure mode that looks most like success. `TrustConsensusTest` asserts
the behaviour: a node cannot out-earn its own bad conduct, and a discredited node barely
moves the consensus.

### The first-hand gate is structural, not a threshold

`FirstHandGate.ADVISORY_CAP = 0.98` holds testimony below the tier that commits a team, no
matter how many nodes repeat it. It has to be a structural ceiling rather than a high
threshold, or a sufficiently chatty cluster eventually crosses it arithmetically.
Corroboration raises rank; it does not change what kind of knowledge something is.

### Clustering stops at the zone boundary, deliberately

`DedupCluster` merges by `dedupKey` and does not attempt to merge separate reports within
a zone. Under-merging shows a responder two entries for one person, costing them a moment.
Over-merging loses someone. With no reliable indoor position signal there is nothing that
could justify the second risk — see the localisation entry in the open assumptions.

---

## L3 ranking (Phase 4, core half)

`ResponderRanking` orders **lexicographically** — severity, then how it is known, then
confidence, then recency — not by a weighted sum. Severity is a time-to-death ordering, so
no amount of confidence about a structural entrapment may outrank a drowning. A weighted
sum would permit exactly that trade, and it is not one a scoring function should make
implicitly on a responder's behalf.

The rolling action budget (14 actions) is ported and fits better here than in its origin:
responders have finite boats and teams, so the board marks where capacity runs out.
Nothing is hidden for being beyond it — everything is still shown and still ordered.

`AiAdvisor` will layer on top of this. The ranking is deterministic and complete on its
own, and the dashboard is fully functional with no model present and no internet at the
perimeter.
