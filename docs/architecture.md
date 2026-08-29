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
