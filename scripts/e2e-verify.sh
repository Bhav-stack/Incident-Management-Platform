#!/usr/bin/env bash
# ============================================================================
# e2e-verify.sh - run the full autonomous-recovery loop against the real stack
# and capture the live events on every Kafka topic at each milestone.
#
# Usage (on a machine with Docker and JDK 17+):
#   ./scripts/e2e-verify.sh                    # happy path: restart -> verified
#   ./scripts/e2e-verify.sh --stubborn         # rollback: verify fails -> auto-rollback
#   ./scripts/e2e-verify.sh --capture docs/e2e-dumps   # save the topic dumps
#   ./scripts/e2e-verify.sh --keep             # leave the stack running afterwards
#   ./scripts/e2e-verify.sh --wipe             # delete the data volumes on exit
#
# Flow verified:
#   error-spike -> raw.events -> anomaly -> incident -> proposal -> approve
#   -> action.commands -> execute -> verify -> resolved (or rolled back)
#
# Teardown is deliberately conservative: containers are stopped and their
# volumes are kept, so incident history survives a verification run. Pass
# --wipe when you actually want a clean database.
# ============================================================================
set -euo pipefail

# Git Bash on Windows rewrites POSIX-looking arguments into Windows paths
# before handing them to a native binary, so `docker exec ... /opt/kafka/bin/x`
# became `C:/Program Files/Git/opt/kafka/bin/x` and every Kafka dump came back
# empty. This variable disables that rewrite; it is ignored on Linux/macOS.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

MODE="happy"
KEEP=""
WIPE=""
CAPTURE_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --stubborn) MODE="stubborn" ;;
    --keep)     KEEP="1" ;;
    --wipe)     WIPE="1" ;;
    --capture)  CAPTURE_DIR="${2:?--capture needs a directory}"; shift ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done
if [ -n "$CAPTURE_DIR" ]; then
  mkdir -p "$CAPTURE_DIR"
fi

SERVICE="checkout-service"
API="http://localhost:8082/api"
SIM="http://localhost:8080/api"

# Control-plane authentication: the services require X-API-Key on /api when
# AEGIS_API_KEY is set for them. Export the same value before running this
# script and every call below carries it; leave it unset for a keyless local
# stack (the services then log a warning and serve /api openly).
AUTH=()
if [ -n "${AEGIS_API_KEY:-}" ]; then
  AUTH=(-H "X-API-Key: ${AEGIS_API_KEY}")
  echo "control-plane API key: using AEGIS_API_KEY from the environment"
fi

# The approval is a person's decision, so this script signs it with a person's
# credential rather than the shared client key. With an operator key AND a
# token issuer configured it goes one step further and exchanges the key for a
# signed token, so the run proves the JWT path end to end; otherwise the
# operator key itself is presented. Both are attributable; the shared key is
# not, and is used only as a last resort so that a bare local stack still
# runs.
OPERATOR_KEY="${AEGIS_OPERATOR_1_KEY:-}"

# JSON parsing uses node: the dashboard already requires it, and it is
# present on the development machines and GitHub runners, unlike python3.
command -v node >/dev/null || { echo "node is required for JSON parsing" >&2; exit 1; }

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

# Reads a topic with the broker's own CLI and prints every record. The
# consumer writes records to stdout and its log4j chatter to stderr, so the
# first stdout line is a real record: do not strip it.
kafka_records() { # topic
  docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 --topic "$1" \
    --from-beginning --timeout-ms 4000 2>/dev/null || true
}

kafka_dump() { # topic label [grep-pattern] [max-lines]
  local topic="$1" label="$2" pattern="${3:-}" max="${4:-0}" out
  log "kafka topic [$topic] - $label"
  out="$(kafka_records "$topic")"
  if [ -n "$pattern" ]; then
    out="$(printf '%s\n' "$out" | grep -F "$pattern" || true)"
  fi
  if [ "$max" -gt 0 ] && [ -n "$out" ]; then
    out="$(printf '%s\n' "$out" | tail -n "$max")"
  fi
  if [ -z "$out" ]; then
    echo "  (no records on $topic for this filter)"
  else
    printf '%s\n' "$out"
    if [ -n "$CAPTURE_DIR" ]; then
      printf '%s\n' "$out" > "$CAPTURE_DIR/$MODE-$topic.ndjson"
    fi
  fi
}

# json <expression>  — reads a JSON document on stdin, evaluates the
# expression with the parsed value bound to `data`, prints the result.
json() {
  node -e "
    let raw = '';
    process.stdin.on('data', chunk => raw += chunk);
    process.stdin.on('end', () => {
      const data = JSON.parse(raw);
      console.log($1);
    });
  "
}

incident_status() {
  curl -sf "${AUTH[@]}" "$API/incidents" | json "data.length ? data[0].status : 'NONE'"
}
first_incident_id() {
  curl -sf "${AUTH[@]}" "$API/incidents" | json "data[0].externalId"
}
first_proposal_id() { # incidentId
  curl -sf "${AUTH[@]}" "$API/incidents/$1/proposals" | json "data[0].externalId"
}
timeline_types() { # incidentId
  curl -sf "${AUTH[@]}" "$API/incidents/$1/events" | json "data.map(event => event.eventType).join(',')"
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
  if [ -n "$WIPE" ]; then
    log "tearing down and deleting volumes (--wipe)"
    docker compose down -v
  elif [ -z "$KEEP" ]; then
    log "stopping the containers; volumes are kept (--wipe deletes them)"
    docker compose stop
  else
    log "leaving the stack running (--keep)"
  fi
}
trap cleanup EXIT

wait_http "http://localhost:8080/actuator/health" "simulator"
wait_http "http://localhost:8081/actuator/health" "ingest-svc"
wait_http "http://localhost:8082/actuator/health" "incident-svc"

log "scenario: error spike on $SERVICE (errorRate 8.5, 120s)"
curl -sf "${AUTH[@]}" -X POST "$SIM/scenarios/error-spike" \
  -H 'Content-Type: application/json' \
  -d "{\"service\":\"$SERVICE\",\"errorRate\":8.5,\"durationSeconds\":120}"
if [ "$MODE" = "stubborn" ]; then
  log "scenario: stubborn failure on $SERVICE (recovery actions will not heal it)"
  curl -sf "${AUTH[@]}" -X POST "$SIM/scenarios/stubborn" \
    -H 'Content-Type: application/json' \
    -d "{\"service\":\"$SERVICE\",\"durationSeconds\":120}"
fi
ok "scenario active"

log "waiting for anomaly -> incident -> proposal (buffer 5s + 2-window confirm)"
await_status "AWAITING_APPROVAL" 90
INCIDENT_ID="$(first_incident_id)"
ok "incident $INCIDENT_ID awaiting approval"
# raw.events is the high-volume input topic, so it is filtered to the service
# under test and capped; the derived topics are small enough to dump whole.
kafka_dump "raw.events"    "input events for $SERVICE (last 4)" "$SERVICE" 4
kafka_dump "anomalies"     "confirmed anomaly for $SERVICE"
kafka_dump "incidents"     "incident state changes so far"

log "approving the proposal"
PROPOSAL_ID="$(first_proposal_id "$INCIDENT_ID")"

# Build the decision credential once. Approve takes no body: the approver is
# whatever the credential names, never a field the caller can set.
DECISION=()
SIGNED_AS="the shared client key"
if [ -n "$OPERATOR_KEY" ] && [ -n "${AEGIS_JWT_SECRET:-}" ]; then
  TOKEN="$(curl -sf -X POST -H "X-Operator-Key: $OPERATOR_KEY" "$API/auth/session" \
    | json "data.token" 2>/dev/null || true)"
  if [ -n "${TOKEN:-}" ] && [ "$TOKEN" != "undefined" ]; then
    DECISION=(-H "Authorization: Bearer $TOKEN")
    SIGNED_AS="an operator token from the built-in issuer"
  fi
fi
if [ ${#DECISION[@]} -eq 0 ] && [ -n "$OPERATOR_KEY" ]; then
  DECISION=(-H "X-Operator-Key: $OPERATOR_KEY")
  SIGNED_AS="an operator key"
fi
if [ ${#DECISION[@]} -eq 0 ]; then
  DECISION=("${AUTH[@]}")
fi
echo "  signing as: $SIGNED_AS"

curl -sf "${DECISION[@]}" -X POST "$API/proposals/$PROPOSAL_ID/approve" >/dev/null
ok "proposal $PROPOSAL_ID approved -> action.commands"

# The point of the new credential path: the approval records how it was
# proven, and for a token, which document signed it.
log "approval attestation recorded on the proposal"
curl -sf "${AUTH[@]}" "$API/proposals/$PROPOSAL_ID" \
  | json "data.approvals.map(a => a.operator + ' via ' + a.method + (a.tokenId ? ' token ' + a.tokenId : '')).join(', ') || '(no approvals recorded)'"

log "watching execute -> verify"
if [ "$MODE" = "happy" ]; then
  await_status "RESOLVED" 90
else
  await_status "RESOLVED" 90   # rolled back incidents also end RESOLVED
fi
kafka_dump "action.commands" "approved command executed by the executor"
kafka_dump "incidents"       "final incident state changes"
if [ -n "$CAPTURE_DIR" ]; then
  echo "  dumps written to $CAPTURE_DIR ($MODE path)"
fi

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