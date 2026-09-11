#!/usr/bin/env bash
# ============================================================================
# e2e-verify.sh - run the full autonomous-recovery loop against the real stack
# and capture the live events on every Kafka topic at each milestone.
#
# Usage (on a machine with Docker and JDK 17+):
#   ./scripts/e2e-verify.sh             # happy path:  restart -> verified -> resolved
#   ./scripts/e2e-verify.sh --stubborn  # rollback path: verify fails -> auto-rollback
#   ./scripts/e2e-verify.sh --keep      # do not tear the stack down at the end
#
# Flow verified:
#   error-spike -> raw.events -> anomaly -> incident -> proposal -> approve
#   -> action.commands -> execute -> verify -> resolved (or rolled back)
# ============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="happy"
KEEP=""
for arg in "$@"; do
  case "$arg" in
    --stubborn) MODE="stubborn" ;;
    --keep)     KEEP="1" ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

SERVICE="checkout-service"
API="http://localhost:8082/api"
SIM="http://localhost:8080/api"

command -v python3 >/dev/null || { echo "python3 is required for JSON parsing" >&2; exit 1; }

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
ok()  { printf '\033[1;32mOK\033[0m  %s\n' "$*"; }
die() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# --- helpers ---------------------------------------------------------------

wait_http() { # url name
  for _ in $(seq 1 90); do
    curl -sf "$1" >/dev/null 2>&1 && { ok "$2 up"; return 0; }
    sleep 1
  done
  die "$2 did not come up ($1)"
}

compose_healthy() { # service
  for _ in $(seq 1 60); do
    docker compose ps --format '{{.Name}} {{.Status}}' 2>/dev/null \
      | grep -q "^.*$1.*healthy" && return 0
    sleep 2
  done
  die "container $1 not healthy"
}

kafka_dump() { # topic label
  local topic="$1" label="$2"
  log "kafka topic [$topic] - $label"
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic "$topic" \
    --from-beginning --timeout-ms 4000 2>/dev/null | tail -n +2 || true
}

py() { python3 -c "$1"; }

incident_status() {
  curl -sf "$API/incidents" | py "import sys,json; incs=json.load(sys.stdin); print(incs[0]['status'] if incs else 'NONE')"
}
first_incident_id() {
  curl -sf "$API/incidents" | py "import sys,json; print(json.load(sys.stdin)[0]['externalId'])"
}
first_proposal_id() { # incidentId
  curl -sf "$API/incidents/$1/proposals" | py "import sys,json; print(json.load(sys.stdin)[0]['externalId'])"
}
timeline_types() { # incidentId
  curl -sf "$API/incidents/$1/events" | py "import sys,json; print(','.join(e['eventType'] for e in json.load(sys.stdin)))"
}

await_status() { # expected timeout_seconds
  local expected="$1" timeout="${2:-120}" got=""
  for _ in $(seq 1 "$timeout"); do
    got="$(incident_status)"
    [ "$got" = "$expected" ] && return 0
    sleep 1
  done
  die "incident stuck at '$got', expected '$expected'"
}

# --- main ------------------------------------------------------------------

log "mode: $MODE"
log "starting infrastructure (kafka, postgres, redis)"
docker compose up -d
compose_healthy kafka
compose_healthy postgres
compose_healthy redis
ok "infrastructure healthy"

log "starting services (logs in /tmp/aegis-*.log)"
if [ "$MODE" = "stubborn" ]; then
  export ACTION_VERIFY_WINDOW=10s ACTION_VERIFY_POLL_INTERVAL=2s
fi
./gradlew :simulator:bootRun >/tmp/aegis-simulator.log 2>&1 &
SIM_PID=$!
./gradlew :ingest-svc:bootRun >/tmp/aegis-ingest.log 2>&1 &
INGEST_PID=$!
./gradlew :incident-svc:bootRun >/tmp/aegis-incident.log 2>&1 &
INCIDENT_PID=$!

cleanup() {
  kill "$SIM_PID" "$INGEST_PID" "$INCIDENT_PID" 2>/dev/null || true
  if [ -z "$KEEP" ]; then
    log "tearing down (use --keep to leave the stack running)"
    docker compose down -v
  fi
}
trap cleanup EXIT

wait_http "http://localhost:8080/actuator/health" "simulator"
wait_http "http://localhost:8081/actuator/health" "ingest-svc"
wait_http "http://localhost:8082/actuator/health" "incident-svc"

log "scenario: error spike on $SERVICE (errorRate 8.5, 120s)"
curl -sf -X POST "$SIM/scenarios/error-spike" \
  -H 'Content-Type: application/json' \
  -d "{\"service\":\"$SERVICE\",\"errorRate\":8.5,\"durationSeconds\":120}"
if [ "$MODE" = "stubborn" ]; then
  log "scenario: stubborn failure on $SERVICE (recovery actions will not heal it)"
  curl -sf -X POST "$SIM/scenarios/stubborn" \
    -H 'Content-Type: application/json' \
    -d "{\"service\":\"$SERVICE\",\"durationSeconds\":120}"
fi
ok "scenario active"

log "waiting for anomaly -> incident -> proposal (buffer 5s + 2-window confirm)"
await_status "AWAITING_APPROVAL" 90
INCIDENT_ID="$(first_incident_id)"
ok "incident $INCIDENT_ID awaiting approval"
kafka_dump "anomalies"   "confirmed anomaly for $SERVICE"
kafka_dump "incidents"   "incident state changes so far"

log "approving the proposal"
PROPOSAL_ID="$(first_proposal_id "$INCIDENT_ID")"
curl -sf -X POST "$API/proposals/$PROPOSAL_ID/approve" \
  -H 'Content-Type: application/json' -d '{"approver":"e2e"}'
ok "proposal $PROPOSAL_ID approved -> action.commands"

log "watching execute -> verify"
if [ "$MODE" = "happy" ]; then
  await_status "RESOLVED" 90
else
  await_status "RESOLVED" 90   # rolled back incidents also end RESOLVED
fi
kafka_dump "action.commands" "approved command executed by the executor"
kafka_dump "incidents"       "final incident state changes"

log "final state"
TIMELINE="$(timeline_types "$INCIDENT_ID")"
echo "  incident   : $INCIDENT_ID"
echo "  status     : $(incident_status)"
echo "  timeline   : $TIMELINE"

if [ "$MODE" = "happy" ]; then
  case ",$TIMELINE," in
    *,VERIFIED,*) ok "verification passed, incident resolved" ;;
    *) die "expected VERIFIED in timeline, got: $TIMELINE" ;;
  esac
else
  case ",$TIMELINE," in
    *,ACTION_FAILED,*) ok "verification failed as expected" ;;
    *) die "expected ACTION_FAILED in timeline, got: $TIMELINE" ;;
  esac
  case ",$TIMELINE," in
    *,ESCALATED,*) ok "incident escalated after rollback" ;;
    *) die "expected ESCALATED in timeline, got: $TIMELINE" ;;
  esac
fi

log "E2E verification passed ($MODE path)"