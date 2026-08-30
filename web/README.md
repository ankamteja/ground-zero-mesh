# web — the responder dashboard on Vercel

A public, zero-backend deploy of the L3 responder dashboard
(`app/src/main/assets/dashboard/index.html`), running the **emulation** only.

## What works here

- The dashboard UI, the schematic 3D view, the ranked board, the inspector.
- **Emulation**: the `SOS · 1 / 2 / 3 hop` and `clear board` buttons. Each press adds a
  real incident to the board with the real propagation result — hop count by chain
  length, tier `SABDA` past a relay, zone `unset` (no responder has entered one),
  corroboration `0` (one path each). Same output the JVM emulation
  (`../run-emulation.sh`) produces; the board is computed by `../api/snapshot.js`
  instead of by running a `SimNetwork` mesh.
- State is the `sos` query param — the page tracks which buttons were pressed and sends
  the list on every poll, so the serverless function stays stateless.

## What does not, and cannot

- **Real phones.** Nearby Connections is BLE / Wi-Fi Direct between physical devices;
  there is nothing for it to run on here. The `view: live` toggle is hidden on this
  deploy. For the phone-to-phone demo see `../run-phone-mesh.sh` and
  `../docs/relay-chain-runbook.md`.
- **SSE `/events`.** The Vercel build has no long-lived gateway process; the page polls
  `/api/snapshot` every 3s instead.

## Layout

| path | role |
|---|---|
| `../vercel.json` | build config — copies the dashboard + `fixtures.json` + `web-config.js` into `web/dist/` |
| `../api/snapshot.js` | serverless function: `GET /api/snapshot?sos=A,B,C` → board JSON |
| `web/web-config.js` | sets `window.__WEBSIM__` — the only thing that distinguishes this deploy from the app's own copy of the dashboard. 404s harmlessly everywhere else. |
| `web/dist/` | build output (git-ignored) |

## Deploy

Connected to the GitHub repo through the Vercel dashboard — every push to the branch
redeploys. Project root is the **repo root** (not `web/`), so the build can read the
dashboard from `app/src/main/assets/dashboard/`.

To preview locally without Vercel: `vercel dev`, or serve `web/dist/` after running the
build command and route `/api/snapshot` to `api/snapshot.js`.
