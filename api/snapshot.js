// Serverless port of GwHeadless --sim's board, for the Vercel deploy where no JVM runs.
//
// The JVM emulation runs a real SimNetwork mesh; this computes the same deterministic
// board directly. Nothing is invented that the JVM path would not also produce: three
// victims on chains of length 1 / 2 / 3 to the gateway, zone "unset" (no responder has
// entered one), tier SABDA (anything past a relay is testimony), corroboration 0 (one
// path each). State is the `sos` query param — the page tracks which buttons were pressed
// and sends the list every poll, so the function stays stateless.
//
//   GET /api/snapshot?sos=A,B,C   ->  board with 1-, 2- and 3-hop incidents
//   GET /api/snapshot             ->  empty board

const FLAG_BITS = [
  "rushing water", "screaming", "pinned", "impact",
  "enclosed / dark", "manual SOS", "enriched", "reserved",
];
const SLOT_NAMES = [
  "audio:water", "audio:voice", "audio:structural", "audio:silence",
  "imu:pinned", "imu:shock", "imu:motion", "imu:still",
  "light:enclosed", "light:flicker", "event:weight", "event:persistence",
  "slot:12", "slot:13", "slot:14", "slot:15",
];
const GATEWAY = "0000-0000-00d5";

// victim key -> chain. `carrier` is the last relay before the gateway (null for the direct one).
const VICTIMS = {
  A: { node: "0000-0000-0a01", ts: 1700000001, hops: 1, carrier: null },
  B: { node: "0000-0000-0a02", ts: 1700000002, hops: 2, carrier: "0000-0000-0b01" },
  C: { node: "0000-0000-0a03", ts: 1700000003, hops: 3, carrier: "0000-0000-0b03" },
};

function cluster(key, pressCount, rank) {
  const v = VICTIMS[key];
  // A reaches the gateway with no relay, so every press folds in; B and C go through a
  // relay that suppresses an identical re-broadcast, so their count stays 1 — exactly the
  // JVM behaviour.
  const reportCount = key === "A" ? pressCount : 1;
  return {
    clusterId: `${v.node}@${v.ts}`,
    origin: v.node,
    zone: "unset",
    severity: "STRUCTURAL_ENTRAPMENT",
    effectiveTier: "SABDA",
    corroboration: 0,
    dangerScore: 1.0,
    lastSeenSecondsAgo: 0,
    reportCount,
    minHops: v.hops,
    gpsLat: null,
    gpsLon: null,
    recommendedActionRank: rank,
    priority: 0.6,
    standing: "single unconfirmed report",
    dispatchable: false,
    flags: "0x20",
    evidence: ["manual SOS"],
    vector: [],
    floor: null,
    floorLabel: "unplaced",
    placed: false,
    position: { x: 30, y: 0, z: 0 },
    reasons: [
      "structural entrapment",
      "single unconfirmed report",
      `nearest report ${v.hops} hop(s) away in unset`,
      "last heard 0s ago",
    ],
  };
}

function board(sosList) {
  const counts = {};
  for (const raw of sosList) {
    const k = String(raw).trim().toUpperCase().slice(0, 1);
    if (VICTIMS[k]) counts[k] = (counts[k] || 0) + 1;
  }
  const keys = Object.keys(counts).sort(); // A,B,C -> hops 1,2,3
  const clusters = keys.map((k, i) => cluster(k, counts[k], i + 1));
  const links = [];
  for (const k of keys) {
    const v = VICTIMS[k];
    if (v.carrier) links.push({ carrier: v.carrier, incidentKey: `${v.node}@${v.ts}` });
  }

  let advice = "No incidents yet.";
  if (clusters.length) {
    advice =
      `${clusters.length} incident(s) within the action budget, 0 first-hand and dispatchable. ` +
      `Highest: unset (structural entrapment).`;
  }

  return {
    advice,
    self: { nodeId: GATEWAY },
    flagBits: FLAG_BITS,
    slotNames: SLOT_NAMES,
    clusters,
    links,
  };
}

export default function handler(req, res) {
  const raw = (req.query && req.query.sos) || "";
  const list = String(raw).split(",").filter(Boolean);
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Cache-Control", "no-store");
  res.status(200).json(board(list));
}
