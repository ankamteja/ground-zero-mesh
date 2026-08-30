# Operating the mesh itself

## The three roles

- **Node (victim)** — a phone that senses and can raise an SOS. It originates reports.
- **Relay** — a phone or a laptop that only carries. It never originates and never has
  first-hand knowledge, which is why anything crossing it is downgraded to relayed testimony.
- **Gateway (responder)** — the phone serving the board. A laptop joins that phone's Wi-Fi
  hotspot and opens the dashboard in a browser; nothing is installed on the laptop.

Every role keeps relaying. A gateway is a relay that also serves a board.

## Reaching the board

The gateway phone opens its own hotspot by hand — Android does not let the app open it
programmatically without system permissions. Join it from the laptop and browse to the
phone's address on port 8080.

Over USB instead of Wi-Fi: `adb -s <serial> forward tcp:8080 tcp:8080`, then open
`http://localhost:8080/`. That path needs no hotspot at all and is the more reliable one for
a demonstration.

## When the board goes quiet

A quiet board is ambiguous and should be read as ambiguous. It can mean:

- Nobody is reporting.
- The gateway lost its peers — check whether any carrier is still listed.
- A relay went down and a whole branch of the mesh is now unreachable, with its reports
  buffered rather than lost.

Reports are held and replayed when a link comes back (store-and-forward, TTL'd), so a
reconnect can deliver a burst of reports about things that happened minutes ago. Check the
`last heard` age on each row before treating a burst as a new emergency.

## Placing a relay

A relay exists to shorten the longest hop. Put it where the gap is, physically: between the
part of the site people are reporting from and the gateway. Two consequences on the board:

- Incidents that were unreachable start appearing.
- Incidents that were already there may show a smaller hop distance.

A relay does not improve evidence quality. A report through a new relay is still testimony.

## Battery

Continuous advertising and scanning over a multi-day operation is real drain and it is not a
solved number in this system. Assume the victim phones are the constraint: they are the ones
nobody can recharge. Prefer moving a relay closer over asking a victim's phone to work harder.

## No raw media, ever

Audio, images and video never cross this mesh. What crosses is an 8-bit evidence flag byte
and optionally a 16-slot feature vector, both computed on the reporting device. Nobody at the
perimeter can listen to anything, and no request from the board can make a phone send media.

## The GPS field

GPS is captured when the reporting phone actually has a fix, and left null when it does not.
It is never estimated. Expect it to be absent for most indoor, trapped and underground
casualties — those are exactly the places where GPS fails, and they are exactly where the
victims are. A present fix is a real location; an absent one means fall back to the zone tag
and the hop distance.
