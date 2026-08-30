# Running everything

Every way to run this system, from "a laptop and nothing else" to three phones and a LoRa
radio. Each section says **what you need**, **what you will see**, and **how to tell it
worked** — because several of these look like they are running when they are not.

Commands are written from the repo root.

---

## 0. What you need

| For | You need |
|---|---|
| `core` alone — tests, the simulation, the relay, the advisor | JDK 21. Nothing else |
| Building the app | Android SDK (`local.properties` → `sdk.dir`) |
| Running on phones | Android 7+ (`minSdk 24`), two if you want a real mesh |
| The AI advisor | [ollama](https://ollama.com) and one pulled model |

`core` has **no third-party runtime dependency** and needs no Android SDK. If you only want to
see the logic work, §1 needs a JDK and nothing more.

---

## 1. The simulation — laptop only, ~10 seconds

The whole stack over a virtual network: victims, relays, a gateway, the danger score, the
trust decay, the digital twin.

```bash
./gradlew :core:runSim
```

Prints the cascade, the ranked board and the twin projection. To also write the 3D snapshot
the standalone dashboard reads:

```bash
./gradlew :core:runSim -PsimArgs="--json docs/simulation-twin.json"
open docs/simulation_dashboard.html      # xdg-open on Linux
```

**Worked if:** you see a board with incidents ranked severity-first, and evidence tokens like
`rushing water, structural crack, pinned`.

---

## 2. The interactive emulation — a real dashboard, no phones

The actual `GatewayServer`, the actual dashboard asset, an in-process mesh behind it.

```bash
./gradlew :app:runHeadlessGateway -PgwArgs="--sim 8080"
```

Open **http://localhost:8080/**. The board starts **empty** — nothing is fabricated. Press
`SOS · 1 hop` / `2 hops` / `3 hops` to raise real gossip frames with real hop counts, and
`clear board` to reset.

- Dashboard: `8080`
- Control API: `8090` (always dashboard port **+10**)

**Worked if:** the header says `emulation (stream)` and pressing an SOS button adds a row.

> Needs `:app` to compile, so this one does want the Android SDK even though no phone is
> involved.

---

## 3. The AI advisor

A local model that answers questions about the board and cites the rescue corpus. **Entirely
optional** — the board is complete without it, and says so when it is absent.

```bash
ollama serve &                 # once, if not already running
ollama pull qwen3:8b           # or mistral, llama3, gemma…
./gradlew :core:runAdvisor
```

It prints which model it chose and preloads it. Then open any dashboard (§2, §4, §5) — the
**Advisor** panel finds it on `localhost:8787` by itself.

### Flags

| flag | default | meaning |
|---|---|---|
| `--port` | `8787` | port this service listens on |
| `--ollama` | `http://localhost:11434` | model server; may be another machine |
| `--model` | auto | pin a model; auto follows `LlmAdvisor.MODEL_PREFERENCE` |
| `--gateway` | none | phone gateway root, for `/brief` and `GET /ask` |
| `--corpus` | none | a directory of extra `.md` retrieved alongside the bundled corpus |
| `--ask` | none | answer one question on stdout and exit |

```bash
./gradlew :core:runAdvisor -PadvisorArgs="--model qwen3:8b --gateway http://localhost:8081"
```

### Checking it without a browser

```bash
curl -s localhost:8787/health
curl -s localhost:8787/brief                          # needs --gateway
curl -s "localhost:8787/ask?q=who+do+I+send+the+boat+to+first"
```

**Worked if:** `/health` reports a model and `passages: 43`, and the panel shows
`qwen3:8b · 43 reference passages`.

**Model choice matters.** Measured on one RTX 4060 against a real eight-row board: `qwen3:8b`
with reasoning off answers in ~1.6s and holds the format; `mistral:7b-instruct` took 55s and
restated the board as its own priority list, which is the one thing the prompt forbids.
Reasoning is disabled per-request for any model that advertises it.

---

## 4. One phone

Enough to demo the whole responder side. **No laptop, no hotspot, no network.**

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone: grant permissions → **responder** → **Start responder server** → **Open the
board on this phone**.

The board is an HTTP server on the phone itself, so the phone's own browser reads it over
loopback. It works in airplane mode.

**Worked if:** the browser shows the ranked board and the 3D view with a teal *this device
(responder)* marker.

---

## 5. Two phones — the real mesh

One victim, one responder, over Nearby Connections (BLE / Wi-Fi Direct). No infrastructure.

1. Install on both (§4).
2. Grant all permissions on both. **The app needs them before the mesh service can start.**
3. Phone A → **victim**. Phone B → **responder** → **Start responder server**.
4. Press **SEND SOS** on A.

**Worked if:** a row appears on B's board within a second or two, `1 hop away`, with the
evidence flags A actually sensed.

### Reading B's board from a laptop

Either join the phone's hotspot and use the address the responder screen now prints, or over
USB:

```bash
adb -s <serial> forward tcp:8081 tcp:8080
# then open http://localhost:8081/
```

> **The USB forward drops.** On some handsets the link re-enumerates every few minutes, which
> silently clears every forward while `adb devices` still lists the phone. If the dashboard
> goes dead, re-run the `forward` command — the phone is usually fine. To keep it alive:
>
> ```bash
> while true; do
>   adb forward --list | grep -q tcp:8081 || adb -s <serial> forward tcp:8081 tcp:8080
>   sleep 3
> done
> ```

---

## 6. Two phones and a laptop relay

When there is no third phone. A laptop cannot join Nearby, so this is a **second, real
transport** over TCP — it replaces Nearby for the session rather than running alongside it.

```bash
./gradlew :core:runRelay                     # port 7777
```

On each phone, **before** granting Nearby permissions: type the laptop's `host` or
`host:port` into **Laptop relay** on the first screen, then pick the role. Kill and reopen the
app after setting it — it is read once at service start.

**Worked if:** the relay terminal's `relayed=` counter moves when you press SOS.

---

## 7. A multi-hop chain

Genuine multi-hop without a phone per hop. Each relay serves its own port *and* dials the
next.

```bash
./gradlew :core:runRelay                                          # hub on 7777
./gradlew :core:runRelay -PrelayArgs="7778 --link localhost:7777"
./gradlew :core:runRelay -PrelayArgs="7779 --link localhost:7778"
```

```
victim ──► relay A (7777) ──► relay B (7778) ──► relay C (7779) ──► responder
           hops=1             hops=2             hops=3             hops=4
```

Start them in any order — a relay dialled before it exists connects when it appears. See
`docs/relay-chain-runbook.md` for the full walkthrough.

**Worked if:** the board shows a hop count above 1 and the tier reads `SABDA` (relayed
testimony), because anything past a relay is downgraded by design.

---

## 8. Tests

```bash
./gradlew test                    # 378: core 304, app 74
./gradlew :core:test              # pure JVM, no Android SDK needed
./gradlew :app:testDebugUnitTest
```

Everything runs offline. The advisor tests drive a fake model server on a real socket, so no
model is required.

---

## Ports

| Port | What |
|---|---|
| `8080` | responder dashboard (phone or headless) |
| `8090` | emulation control API (dashboard port + 10) |
| `8787` | AI advisor |
| `7777+` | TCP relays |
| `11434` | ollama |

---

## When it does not work

**The app crashes on launch.** Fixed — but if you are on a build older than `b3a9d36`: with no
`RECORD_AUDIO` granted, promoting the foreground service with a `microphone` type throws
`SecurityException` and the app dies before any screen appears. Grant permissions, or update.

**The board is empty on the responder.** Expected with no victim. Check the responder screen
says the board is being served, and that a victim phone has actually pressed SOS.

**Every incident says `unset`.** Correct when nobody has entered a zone tag — the zone is
human-entered and there is no localisation in this system. A zone entered *later* now reaches
the board (it used to be frozen at the first value).

**The dashboard connected then stopped updating.** Almost always the USB forward, not the
phone — see §5.

**The advisor panel says "no local advisor".** `ollama serve` and `:core:runAdvisor` on the
laptop. The board is unaffected; the panel falls back to a deterministic brief and labels it
as one.

**The board shows emulation SOS buttons on a live phone board.** A stale cached page — hard-
reload. The live board has one source and no emulation controls.

**Permissions will not grant over adb** on some handsets (`SecurityException: … requires
android.permission.GRANT_RUNTIME_PERMISSIONS`). Grant them by hand in the app.
