#!/usr/bin/env bash
# Emulation: the real L3 responder dashboard on :8080, fed by a SIMULATED mesh running
# in-process on this laptop — three victims, three relays, one gateway, over SimNetwork.
# No phones, no radios. Hop counts, corroboration and relay carriers on the board are real;
# the nodes are not.
#
# The dashboard's "live | emulation" toggle (top bar) switches between this and the phone
# board — run ./run-phone-mesh.sh for that, it forwards the phone to :8081.
#
#   ./run-emulation.sh          start it (leaves it running)
#   ./run-emulation.sh --stop   stop it
set -uo pipefail
cd "$(dirname "$0")"

RUN_DIR=".emulation"
PIDS="$RUN_DIR/pid"
mkdir -p "$RUN_DIR"

stop() {
  # anything serving :8080 that is our headless gateway
  local p
  p=$(ss -tlnpH 2>/dev/null | grep ':8080 ' | grep -oP 'pid=\K[0-9]+' | sort -u)
  [ -f "$PIDS" ] && p="$p $(cat "$PIDS")"
  for x in $p; do kill "$x" 2>/dev/null && echo "  killed $x"; done
  # the gradle JavaExec forks a JVM; make sure the GwHeadless main is gone
  pkill -f 'org.groundzero.mesh.app.gateway.GwHeadlessKt --sim' 2>/dev/null || true
  rm -f "$PIDS"
}
[ "${1:-}" = "--stop" ] && { stop; echo "stopped"; exit 0; }

if ss -tlnH 2>/dev/null | grep -q ':8080 '; then
  echo "==> :8080 busy — stopping whatever holds it first"
  stop
  sleep 2
  if ss -tlnH 2>/dev/null | grep -q ':8080 '; then
    echo "!! still busy. if it's a phone board: adb -s <serial> forward --remove-all"
    exit 1
  fi
fi

echo "==> building"
./gradlew -q :app:compileDebugKotlin :core:jar

echo "==> starting emulation gateway on :8080"
nohup ./gradlew -q :app:runHeadlessGateway -PgwArgs="--sim 8080" > "$RUN_DIR/gateway.log" 2>&1 &
echo $! > "$PIDS"

for _ in $(seq 1 40); do curl -sf -o /dev/null --max-time 2 http://localhost:8080/snapshot && break || sleep 1; done
if ! curl -sf -o /dev/null --max-time 3 http://localhost:8080/snapshot; then
  echo "!! gateway did not come up — see $RUN_DIR/gateway.log"
  tail -20 "$RUN_DIR/gateway.log"
  exit 1
fi

echo
echo "dashboard: http://localhost:8080/     (opens in 'emulation' mode; toggle 'live' -> phone board on :8081)"
echo
echo "the board starts EMPTY. raise an SOS with the buttons in the page, or:"
echo "  curl -X POST 'http://localhost:8090/sos?v=A'   # 1 hop  (victim straight to gateway)"
echo "  curl -X POST 'http://localhost:8090/sos?v=B'   # 2 hops (one relay)"
echo "  curl -X POST 'http://localhost:8090/sos?v=C'   # 3 hops (two relays)"
echo "  curl -X POST 'http://localhost:8090/clear'     # empty the board"
echo
echo "nodes: vA vB vC victims, R1 R2 R3 relays, G gateway. zone is 'unset', nothing fabricated."
echo "stop with: ./run-emulation.sh --stop"
