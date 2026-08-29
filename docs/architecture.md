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

---

## L3 ranking (Phase 6, app half — wiring the gateway to core)

Phase 4 shipped an app-side ranker (`ClusterRanker` / `SurvivorCluster` / `SurvivorReport`)
and an app-side `AiAdvisor` while `core` had none. `core` now owns the canonical
`ResponderRanking` + `FirstHandGate`, so the app-side copies were a second implementation
of the same rule drifting on its own — deleted in `phase6/wire-gateway`.

- `GatewayServer` / `GatewayController` now take `() -> List<RankedIncident>` (the `core`
  type). The gateway is expected to pass `ResponderRanking.rank(gossip.clusters(), now)`.
- `ClusterJson` serialises `RankedIncident` straight to the field names
  `assets/dashboard/index.html` already reads, and additionally surfaces `standing`
  (the `FirstHandGate` label), `dispatchable`, `priority` and `reasons` — the dashboard
  grew a plain detail row for the last two.
- The advisory line is now a deterministic one-liner built inside `GatewayServer.advise()`.
  It summarises what the ranking already decided; it cannot reorder or veto an entry. No
  model, no internet. A real `AiAdvisor` seam, if wanted, belongs in `core` next to
  `ResponderRanking`, not in the app.

### Open gap flagged to Claude A

`RankedIncident` / `IncidentCluster` carry no count of raw reports folded in —
`corroborators` is a `Set<NodeId>` of distinct relayers. The dashboard's `reportCount`
therefore uses `corroborators.size` as the closest honest proxy (distinct nodes that
carried the incident, first included), and `corroboration` uses `corroborationCount`
(`size - 1`). If a true fold count is wanted on the board, `IncidentCluster` needs a
`reportCount` field maintained in `DedupCluster.ingest` — a `core` change, so it is a PR
conversation, not a local edit.

---

## Wiring the mesh stack into the device (Phase 7, Step 2)

`core` composes an agent and a gossip layer around one `Transport`. On a device both live in
the same process, which the `core` seam does not by itself account for, so three decisions
were needed. None of them changed `core`.

**The agent must not talk to the radio directly.** `NodeAgent` holds a `Transport` and sends
its own envelopes down it. With `Gossip` on the same device that is wrong twice over: the
propagation key is never marked seen, so the first echo of the node's own report comes back
as news and is re-forwarded; and the envelope never enters the local cluster store, so a
gateway phone does not show the SOS its own user just raised. `GossipOriginTransport`
decorates the transport for the agent only — its `send` decodes the frame and calls
`Gossip.originate`, while `Gossip` holds the real transport. One frame reaches the radio, the
key is marked, the envelope is folded in on the way past.

**One owner for the stack's lifetime.** `MeshForegroundService` builds
`Gossip(radio) + NodeAgent(GossipOriginTransport(radio, gossip))` and publishes them through
the `MeshStack` process singleton, mirroring `GatewayController`. The Activity and the
gateway server borrow it; neither can own it. Every `MeshStack` call is inert while nothing
is installed, so the UI works before the service is up.

**Serialised, not thread-confined.** `NodeAgent` and `Gossip` are not thread-safe and three
threads want in: the UI thread (SOS), a Nearby callback thread (inbound frames) and a
NanoHTTPD worker (the dashboard reading the board). `MeshStack` takes a lock rather than
requiring every caller to hop to one looper — a rule that would be silently broken by the
first new call site. Contention is negligible at mesh message rates.

Ticker cadences follow `NodeAgent`'s split-ticker design: the heartbeat runs at
`NodeAgent.HEARTBEAT_INTERVAL_MS` (10 s) on its own `Handler` post, separate from the 30 s
maintenance tick that decays the `PeerTable` and sweeps store-and-forward. A calm node's
heartbeat emits nothing, so the ticker is nearly free when there is nothing to say.

### Chosen defaults

- **`saltFingerprint`** = first 32 hex chars of `SHA-256(nodeId.canonical())`, derived in
  `NodeIdStore` rather than stored. It is a fingerprint of the identity, not a secret: it
  distinguishes two nodes claiming the same id and nothing more. A real per-device salt —
  one that makes the id itself unlinkable — needs its own store and a decision about who may
  re-link it. Not made yet.
- **`addressZone`** = `"unset"`. Localisation is not solved, and a fabricated coordinate
  would read as solved on the dashboard. A responder-entered zone is the intended fix.

### Known gap

`NodeAgent.livenessTick` is not driven. It expects a `MutableMap<NodeId, Peer>` and the app
keeps peers in `PeerTable`, whose own `decayTick` runs in the maintenance tick — peers still
decay and go SILENT on time. The agent's copy of that concern is unused on device.

---

## The Math Engine and the flag byte (Phase 8)

Ported from the finalized architecture spec: the deterministic compute engine, the 16-float
SLM vector, the 8-bit sensory flags, and the cascade's stage boundaries.

**Why the model is confined to a vector.** On-device inference is restricted to producing
`v_SLM`, 16 floats in `0..1`. No autoregressive text generation. Text on a phone at 4%
battery is unbounded in time and produces output no deterministic layer can check; a fixed
vector is bounded in time and memory and feeds a linear projection whose contribution can be
read off by a human. `MathEngine.explain()` names the feature that moved the number. That
cannot be said of a sentence a model wrote.

**The projection.** `Signal_t = W · v_SLM + w_IMU · a_mag`, clamped to `0..1`. `W` sums to
exactly 1.0 and `w_IMU = 0.25`, so a device saturated on every channel saturates the signal
rather than running past it — letting the raw number exceed 1.0 would silently re-scale every
threshold downstream. Weights are ordered by how *specific* the evidence is to a person in
danger, not by how loud it is: water and a pinned device are strong; a voice is weaker (a
voice is also a rescuer's voice); enclosure is weakest, because a phone in a pocket is dark
too. The IMU term sits outside the vector because it is the one channel still honest when
every other sensor is blind — a phone buried in rubble hears nothing and sees nothing.

**Where the EMA lives.** The spec writes the smoothing next to the projection:
`DangerScore_t = DangerScore_{t-1}·(1−α) + Signal_t·α`. That EMA already existed, once, in
`DangerScore`, which also owns the thresholds and the human-readable explanation. It is
**not** duplicated in `MathEngine`: two EMAs over the same signal would double the smoothing
and halve the responsiveness α was chosen for. `MathEngine` produces `Signal_t`;
`DangerScore` consumes it. **α = 0.35** (`DangerScore.DEFAULT_ALPHA`), up from 0.4 — high
enough that an IMU spike is visible within two or three ticks, low enough that one noisy
reading cannot alarm a node alone. The SOS path does not touch it: `raiseSos` sets 1.00
directly so no smoothing can delay a person who told us themselves.

**The flag byte.** `SensoryFlags` — bit 0 audio water, 1 audio screaming, 2 IMU pinned,
3 IMU impact, 4 low light, 5 manual SOS, 6 stage-2 enriched, 7 reserved. One byte on every
envelope including LoRa frames. The full vector is *optional* on the wire and costs 17 bytes
of a 233-byte frame, so only the Stage 3 enriched broadcast carries it: the flags are what a
responder triages on, the vector is what the dashboard inspector shows. `MANUAL_SOS` is set
from the agent's incident state, never from a sensor — no feature vector can assert that a
human pressed the button, and no classifier may clear it.

**Wire format.** `CompactCodec` version `0x02` inserts the flag byte after `ttl` and appends
an optional `v_SLM` block (one `u8` per slot, `round(value * 255)`, so a slot survives to
within 1/255). Nothing speaks `0x01`; there are no deployed nodes to be compatible with.

**Stage boundaries in `NodeAgent`.** Stage 0 (t=0) `raiseSos`; Stage 1 the sensory window
filling via `senseVector`; Stage 2 the projection plus flag compilation, sub-millisecond;
Stage 3 `completeSensoryWindow` re-broadcasting with the incident's original timestamp so the
same incident is updated rather than a second alert raised for the same person.

---

## Asymmetric trust decay (Phase 9)

`TrustConsensus` moves from the reference implementation's 0.02 / 0.05 to **+0.05 gain,
−0.35 loss** — a seven-to-one asymmetry.

Both numbers were raised for the same reason: the timescale of a real incident. At 0.02 a
node needed dozens of clean relays before its opinion counted for anything, which over a
twenty-minute rescue means it never counts at all; at 0.05 loss, a node that contradicts
first-hand observation keeps its influence for the whole event. Earning trust has to be
possible inside one incident, and losing it has to be fast enough to matter inside one too.
Both remain proportional — gain scales with the room left (`1 − t`, so nobody is ever fully
believed) and loss scales with the trust held (a well-trusted node has more to lose, which is
what makes a long con expensive rather than free).

### The decay was inert until now

`DedupCluster.judge` existed and was called by exactly one thing: a test. Nothing in the
production path ever moved a trust value, so the asymmetry defended nothing. `ingest` now
judges, but only where there are grounds to:

- **Only on a relay of an incident already held.** A first sighting corroborates nothing, and
  a node must not be rewarded merely for talking.
- **Only a relayer, never the origin.** Severity is the victim's own statement of how fast
  they die; penalising the person in trouble for restating it would be exactly backwards.
- **Agreement on severity is the test.** A matching severity is corroboration; a different
  one is conflicting telemetry. `judge` stays public so a layer with better grounds — a
  gateway holding a responder's confirmation, say — can say so with more authority than
  severity agreement carries.

The incident itself is unaffected by a conflicting report: severity still takes the most
urgent claim ever seen and never walks back to a calmer one.

---

## Digital Twin, Radmin advisory and the CLI simulation (Phase 10)

### The twin is schematic, and the type says so

`DigitalTwin` builds a spatial state model from the ranked board. There is still no
trilateration, no RSSI ranging and no GPS anywhere in this project, so positions are derived
from the **zone tag alone** and then spread deterministically around that zone's ring (angle
from the tag's hash plus the slot index) so two incidents in one zone do not stack. The same
data always lays out the same way — a view that reshuffles on every refresh cannot be read
under pressure — but stable is not the same as surveyed.

`TwinNode.placed` is the field that keeps this honest. A zone tag that names no floor
(`"unset"`, the app's default) produces `placed = false` and parks the node 30 m clear of the
building rather than drawing it on the ground floor. An unplaced casualty shown as unplaced
costs a responder a question; one drawn confidently in the wrong place costs them a search.

`TwinLink` is named `carrier`, not `link`, for the same reason: all that is known is that a
peer handed us a report. Whether it heard the origin directly or three hops away is not in
the envelope, and drawing it as measured topology would claim more than the data supports.

### The advisory cannot misbehave, structurally

`TacticalSummarizer` takes an **already-ranked** board and returns **text**. It is handed the
decision after it has been made and can only describe it — no return path exists by which it
could reorder, promote, hide or delay anything. That is a stronger guarantee than a comment
asking a future implementer to behave: a real 8B model dropped in behind this seam cannot
misbehave in the one way that would matter, whatever it generates.

`RadminLlmSummarizer` is the deterministic stand-in, the same role
`DeterministicSensoryClassifier` plays at L1. It states what is *not* known as plainly as
what is: unplaced casualties are reported as unplaced, single-sourced reports are called
single-sourced, and every summary ends by saying the ordering was not its doing.

### `IncidentCluster` now carries the flags

Flags are OR-ed across every report for an incident rather than last-write-wins. A device
that reported water and is now too damaged to report it was still in water; evidence
accumulates and does not expire because the next frame was quieter. The feature vector is
last-seen, since it is a snapshot rather than a claim.

### `./gradlew :core:runSim`

The honest demo of a disaster mesh needs three phones, two rooms and a volunteer willing to
be trapped. `SimulationRunner` runs the same `core` code the phones run — the only
substitution is `SimNetwork` for a radio — over an A—B—C topology with a fourth node one
floor down, and prints every stage: the t=0 override, `v_SLM` and `Signal_t` with the
contributing feature named, the enriched re-broadcast and its LoRa frame size, the trust
penalty for a contradicting relay, the ranked board with reasons, the twin, and the advisory.
`--json <path>` writes the twin snapshot for `docs/simulation_dashboard.html`.

`SimulationRunnerTest` asserts it still runs and that the enriched frame still fits 233
bytes — cheap to assert, expensive to discover on a projector.
