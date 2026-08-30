# Triage and dispatch at the perimeter station

## Why the order is what it is

The board is ordered lexicographically, not by a weighted score: severity first, then how the
incident is known, then confidence, then recency, then a stable tie-break.

Severity comes first and is not tradeable. A weighted sum would let a very confident
structural entrapment outrank a drowning, and that is a trade nobody should be allowed to
make implicitly inside a scoring function. If a drowning sits above a better-evidenced
entrapment, the board is working.

Recency is last and it is a tie-break, not a decay: an old report is not a resolved one.

## Reading the top of the board

Take the top row and ask three questions in this order:

1. **Severity** — is it drowning-imminent? Those outrank everything regardless of how
   confident the other reports are.
2. **Standing** — is it `confirmed — first-hand`? Only that commits a team.
3. **Placement** — is it placed on a floor, or unplaced? An unplaced casualty needs a search
   assignment, not a point to drive to.

## What to do with rows that are not dispatchable

Testimony and single unconfirmed reports are not noise and they are not lies. They are
reports that have not yet earned a boat. Useful responses:

- Task a passing team already working that zone to confirm on the way.
- Move a relay closer to that zone so the origin can reach the board directly.
- Watch whether corroboration climbs, which happens as more nodes carry the same report.

What not to do: send the last available team on hearsay while a first-hand drowning is still
on the board. That is precisely the trade the first-hand gate exists to prevent.

## The budget boundary

Rows inside the budget are what current capacity can act on. Rows past it are not deferred
forever — the boundary moves as teams come free, and a row past the budget can also move
inside it when the rows above are resolved or when a higher-severity report arrives and
reshuffles nothing above it.

If everything on the board is beyond the budget, the honest report upward is "we are short
of teams by this many", with the count from the board.

## Marking someone found

`found / safe` on the inspector marks that peer resolved on **this device only**. There is no
mesh-wide resolution message yet, so a victim the board knows about only through a relay is
unaffected by the tap. Say so on the radio; do not assume the other stations' boards updated.

## Repeated presses

A person pressing SOS repeatedly raises `reports folded`, not new rows, and does not raise
their rank. It is a sign of someone conscious and able to press a button — which is
information — but it is not corroboration and it is not a reason to reorder.
