# Handoff — where things stand (2026-08-30, night)

Written fresh, replacing the earlier version — everything it tracked is now either landed or
superseded below. `main` is at `ebb0649` (merges in `worktree-field-emulation`, built in
parallel this session — see below). `./gradlew test` (**378 tests**, core 304 / app 74) and
`:app:assembleDebug` are both green. For how to run any of this, see `docs/RUNNING.md`.

The **Samsung S25 / SM_S931B** ran the current build as the responder throughout this session.
No victim phone was on hand for the second half — the Realme's contribution (a real incident,
`5298-ba60-0c40`) stands, but nothing after it was re-verified with a live SOS.

## What's fully landed and verified on real hardware

- **The core mesh loop, repeated-press dedup, and the responder's own marker** — unchanged
  since the last handoff, still working: SOS travels over Nearby → the board via
  `GatewayServer`, a re-press updates the existing incident (`reportCount` climbing, not new
  entries), severity never walks back down once raised.
- **GPS location, end to end.** A real fix when available, honestly nullable — never required,
  never fabricated, falls back to the zone tag / hop count when absent. The reason no fix ever
  arrived on a real phone was never in the Kotlin: the foreground service type needs
  `location` to keep receiving fixes once the app is backgrounded on Android 10+, and on 14+
  the type mask has to be computed at runtime from what is actually granted or promoting
  throws. Both fixed; `GpsBridge` also seeds from `getLastKnownLocation` so the first
  coordinate does not wait a full time-to-first-fix.
- **A laptop as the mesh relay over TCP**, for when a third phone isn't on hand — replaces
  Nearby for the session, not alongside it. `:core:runRelay` is a real, tested transport.
- **The AI advisor.** A local model (`core/llm/` + `core/advisor/`) behind the
  `TacticalSummarizer` seam, with BM25 retrieval over a rescue corpus. No new Gradle
  dependency — `core` stays pure JVM. Model choice was settled by measurement: on one RTX
  4060 against a real board, `qwen3:8b` with reasoning off answered in **1.6s** and held the
  format; `mistral:7b-instruct` took 55s and restated the board as its own priority list — the
  one thing the prompt forbids. Verified against the real S25 gateway with a real incident:
  5s, grounded, four citations.
- **The board opens on the phone that serves it.** No second device needed to see the whole
  output of the system. Verified with "No internet connection" in the status bar.
- **The board survives a process restart.** `GatewayController` used to be reachable only from
  the UI button, so a reclaimed process came back relaying and serving nothing while the
  phone still read "responder". Fixed and confirmed on device: after a force-stop, a new
  process id starts the gateway with nobody touching the phone.
- **The fresh-install crash is fixed.** No `RECORD_AUDIO` → promoting the foreground service
  with a `microphone` type threw `SecurityException` and the app died before any screen
  appeared — what a judge installing the APK would have hit. Reproduced by revoking the
  permission, confirmed fixed with it still revoked.
- **A zone entered after the SOS now reaches the board.** This was the cause of every real run
  showing `unset` forever: the first envelope's zone was frozen because `DedupCluster`'s merge
  never updated it. Now follows the same "informative wins" rule as the GPS fix.
- **Real Nearby Connections, without physical phones.** `tools/field/` (built on a parallel
  worktree this session, merged in) drives virtual phones over Android's `netsim` — a real
  Bluetooth protocol stack in software, not a mock — so the app runs against the genuine
  Nearby API and permission model. Verified: two virtual phones find each other over real BLE
  GATT and an `ENCRYPTED_WIFI_LAN` endpoint; an SOS raised on a virtual victim crossed the
  laptop TCP relay and landed on a **real physical phone's** board at `hops=2`. Found and fixed
  three real bugs: a crash on the relay send path (`NetworkOnMainThreadException`), the last
  queued frame silently dropped on service stop, and three unplaced casualty ids overlapping
  into one unreadable board label. See `docs/RUNNING.md` §7½ and `tools/field/README.md`.
- **Structural audio reaches the board as evidence, not just a number.** Bit 7 was reserved
  and unused while the channel it should have named was already the third-heaviest weight in
  the danger score. Now `structural crack`.

## What was found and fixed this session, with the evidence

Full detail and how each was proven is in
`~/Downloads/ground-zero-mesh-session-2026-08-30.md` (outside the repo, not tracked). Short
version:

- **The LoRa byte budget never closed.** The largest envelope the schema can express is
  *exactly* 233 bytes; the transport advertised 225 and threw in `send()`. Now
  `LORA_USABLE_FRAME = 223`, and a test re-derives the largest envelope by search so the gap
  cannot reopen silently.
- **Node identity was corrupted by every LoRa hop** — `NodeId` truncated 48→32 bits and back.
  Fixed; the framing now carries the full id.
- **No integrity check existed between the LoRa and BLE CRCs.** A frame reassembled one byte
  out of step decoded into a *valid* envelope with the wrong severity, and nothing downstream
  could tell. Now CRC-8.
- **The reassembler could stack-overflow on ordinary line noise**, and a one-byte sync word
  could stall the channel indefinitely — the second one caught by my own test failing first.
- **An honest relay lost trust for carrying a victim's own severity upgrade** — measured at
  0.5 → 0.325. The spoof-detection penalty this shares code with is untouched; only the
  false-positive case (escalation, not contradiction) was removed, and two existing tests
  caught the first, too-broad attempt at the fix.
- **`StoreAndForward` could silently lose a buffered frame** under a concurrent sweep — proven
  by reverting the fix and watching the new test catch the loss.
- **My own RAG corpus told a responder something false** — it still called bit 7 "reserved"
  after the code changed, and the advisor would have cited it as a source. A test now pins
  every claim in the corpus that is really a fact about the code.
- **Math Engine hardening**: shared-by-reference default weights, an unenforced saturation
  contract, infinite/NaN weights, a zero vector asserting every flag at threshold 0, locale.
- **`LanRelayTransport`'s `PeerTable` bookkeeping only updated after a caller registered
  `onReceive`/`onPeerConnected`**, unlike `NearbyTransport` which populates its own peer table
  unconditionally. Fixed by moving registration into `init`.

## Open items, unchanged or newly surfaced

- **The actual run over real physical Nearby (BLE / Wi-Fi Direct hardware)** — `tools/field`
  proves the app against the genuine Nearby API and permission model with virtual radios; it
  does not prove real antennas, real interference, or the permission dialogs a person taps
  through on physical silicon. A live multi-device run with a real GPS fix and the laptop
  relay over a real network still hasn't happened.
- **Gateway hotspot still can't be opened programmatically** — a responder opens it by hand
  (Android permission limitation, not a bug).
- **`Envelope.peers` is populated and never read.** Up to 48 bytes per frame — over 20% of the
  LoRa budget — write-only. A protocol decision, not touched.
- **`hops` saturates at `MAX_HOPS` (15)** while TTL keeps forwarding, so hop count silently
  stops being truthful past that.
- **Store-and-forward replay can still cost an honest relay standing** in one case: a
  genuinely old, lower-severity frame delivered after a partition heals. Not resolvable at
  that layer — the envelope timestamp is the incident's, not the report's — and documented
  rather than guessed at.
- **The mic → board evidence path is verified by simulation only.** No victim phone in the
  second half of this session; `:core:runSim` now prints "structural crack" as real evidence,
  confirming the new bit through the whole pipeline, but a live SOS with actual sound is
  untested.
- **No mesh-wide "resolved" broadcast** — `POST /resolve` only reaches the gateway's own
  direct peer table.
- **`Peer.healthy` still has no production reader.**
- **The pitch deck is stale.** Slides 1/8/11 say `201 core tests` (actual 304) and slides 1/8
  say `233 byte envelope ceiling` (233 is still correct as the *on-air* frame; the
  *application* ceiling is now 223). Not edited — slide edits are destructive and it is not
  mine to change. Exact corrections are in the Downloads file above.

## Practical notes for whoever picks this up

- **The USB forward drops.** On the S25, the ADB link re-enumerates periodically, silently
  clearing every `adb forward` while `adb devices` still lists the phone connected. If a
  dashboard goes dead mid-session, re-forward before assuming the phone crashed. `docs/
  RUNNING.md` §5 has a keeper loop.
- If a `git push` gets rejected because someone else pushed in the meantime: `git fetch
  origin main`, then `git merge origin/main` (or `merge --ff-only` if there's nothing to
  reconcile) — never force-push.
