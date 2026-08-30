# The responder board, field by field

Every row on the board is one **incident cluster**: all the reports the mesh has folded
together about one person, not one radio message. What follows is what each field means and,
just as importantly, what it does not claim.

## Severity

Three values, and they are a time-to-death ordering, not a scale of importance:

- `DROWNING_IMMINENT` — minutes, not hours.
- `STRUCTURAL_ENTRAPMENT` — trapped by collapse, debris or a pinned limb.
- `OTHER` — reported in distress with no more specific category.

Severity is set by the person pressing SOS on their own phone. It never walks back down once
raised: if someone reported drowning and later pressed a calmer severity, the board keeps the
worst statement they made of how fast they are dying. A row that looks "stuck" at a high
severity is behaving as designed, not showing a stale value.

## Evidence tier

How the holder of this report knows it. Anything that has crossed a relay is downgraded — a
relay cannot pass on first-hand knowledge it does not have.

- `PRATYAKSA` — first-hand. A node observed this directly.
- `ANUMANA` — inferred. Derived from sensor signals, not observed directly.
- `SABDA` — relayed testimony. Heard from another node, not yet confirmed.

## Standing, and the first-hand gate

Standing is the label a responder should read before committing anyone:

- `confirmed — first-hand` — **dispatchable**.
- `corroborated — testimony` — several nodes agree, still not first-hand, not dispatchable.
- `single unconfirmed report` — one source, no corroboration, not dispatchable.
- `below reporting floor` — danger score under 0.45, not actionable at any tier.

Only a first-hand report above the floor may commit a team. Testimony is capped below that
level structurally, so no amount of corroboration can arithmetically promote hearsay into
something that dispatches a boat. Corroboration raises rank; it does not change what kind of
knowledge a report is.

## Action rank and the dispatch budget

`#1`, `#2`, `#3`… is position within the **dispatch budget** — about 14 actions per window,
because boats and teams are finite. Rows past the budget still appear, still in order,
labelled as beyond current capacity. They are not dropped and they are not less real; there
is simply nothing left to send them right now.

## Corroboration, report count, distance

- **corroborating relays** — distinct relaying peers beyond the first. Zero means
  single-sourced: exactly one path has ever carried this.
- **reports folded** — how many individual reports merged into this row. A high count with
  zero corroboration means one person pressing SOS repeatedly, not many witnesses.
- **distance (hops)** — the fewest radio hops this report has ever reached us in. It is the
  only distance proxy a mesh with no ranging has. One hop means the phone was in direct
  range of this device; it does not mean metres.

## Danger score and priority

`dangerScore` is the on-device signal: sensor features projected through a fixed weighting,
smoothed, with two thresholds. `priority` is a display number derived from the same facts as
the ordering. Neither of them decides the order — the order is lexicographic on severity,
then standing, then confidence, then recency — so a lower-priority row above a higher one is
severity doing its job, not a bug.

## Sensory evidence flags

Eight bits, set on the reporting device before anything crosses the wire: `rushing water`,
`screaming`, `pinned`, `impact`, `enclosed / dark`, `manual SOS`, `enriched`,
`structural crack`.

`structural crack` is a sharp broadband transient — an impact or a structural failure near
the device. It is the third-heaviest channel in the danger score, above a voice.

No raw audio, image or video ever crosses the mesh. What arrives is these flags plus,
optionally, a 16-slot feature vector. `screaming` means a spectral classifier on that phone
matched a voice-like signal — it is not a recording, and nobody has heard it.

## Location fields

- **zone** — a coarse, human-entered tag. `unset` means nobody has entered one.
- **floor / placed** — derived from the zone tag's wording alone. `UNPLACED` means the tag
  named no floor, and the position shown is a parking slot off to one side, not a location.
- **GPS** — a real device fix, present only when the sending phone actually had one. It is
  never estimated and never filled in from anything else. Most indoor, trapped and
  underground casualties will have none, which is the normal case, not a fault.
