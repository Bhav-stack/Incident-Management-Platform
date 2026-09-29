#!/usr/bin/env bash
# ============================================================================
# loadtest.sh - measure the ingest path instead of guessing at it.
#
# The generator paces events in real time (event time = send time), because the
# pipeline is built for a live stream: it drops anything older than
# window + grace as a straggler. A burst of 100k events produced in 9 seconds
# cannot be measured by that pipeline - by the time the consumer reaches the
# tail, the tail is minutes old and correctly dropped - so the offered rate is
# stepped instead, and each step is reported on its own.
#
# For each step this prints:
#   * received / deduplicated / ordered / dropped (straggler + overflow)
#   * throughput: ordered events per second
#   * mean latency per pipeline stage, exact for that step (from timer
#     count/sum deltas): Redis dedup, buffer offer, rule evaluation
#   * event_time -> rule staleness, which includes the ordering window
#
# A final cumulative percentile table follows, with the caveat that Micrometer
# percentiles are a rolling window rather than a per-step figure.
#
# Usage:
#   ./scripts/loadtest.sh                              # 200,500,1000 steps, 30s each
#   ./scripts/loadtest.sh --rates 500,2000 --phase-seconds 60
#   ./scripts/loadtest.sh --spike 0                     # no anomaly episode
#   ./scripts/loadtest.sh --no-start --out docs/loadtest-ingest.txt
#
# If nothing answers on $INGEST_URL the script starts the compose stack and the
# ingest service itself, then stops the service on exit. Pass --no-start when
# ingest-svc is already running.
# ============================================================================
set -euo pipefail

# Git Bash on Windows rewrites POSIX paths passed to native binaries, which
# breaks the container-side Kafka CLI. Ignored on Linux/macOS.
export MSYS_NO_PATHCONV=1

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

RATES="200,500,1000"
PHASE_SECONDS=30
DUPLICATES=10        # percent of events that repeat an earlier eventId
SPIKE=2              # seconds of contiguous breach at the end of each step
# The pipeline is allowed to hold events back: the out-of-order buffer waits
# `window` for late siblings and drains on a `flush-interval` tick. At the
# instant a phase ends, that much of the tail is still legitimately in flight,
# so it is subtracted from the events a perfectly-keeping-up pipeline could
# have completed. Without this the keep-up figure tops out near 75% at every
# rate, including rates the pipeline handles with room to spare.
WINDOW_SECONDS=5
FLUSH_SECONDS=1
SERVICE="load-service"
INGEST_URL="${INGEST_URL:-http://localhost:8081}"
OUT=""
START="auto"

while [ $# -gt 0 ]; do
  case "$1" in
    --rates)         RATES="${2:?}"; shift ;;
    --phase-seconds) PHASE_SECONDS="${2:?}"; shift ;;
    --window-seconds) WINDOW_SECONDS="${2:?}"; shift ;;
    --flush-seconds) FLUSH_SECONDS="${2:?}"; shift ;;
    --duplicates)    DUPLICATES="${2:?}"; shift ;;
    --spike)         SPIKE="${2:?}"; shift ;;
    --service)       SERVICE="${2:?}"; shift ;;
    --out)           OUT="${2:?}"; shift ;;
    --no-start)      START="never" ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

command -v node >/dev/null || { echo "node is required to generate the load" >&2; exit 1; }

log() { printf '\n\033[1;36m== %s ==\033[0m\n' "$*"; }
die() { printf '\033[1;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# Scratch files live under the project root and are referenced relatively: on
# Windows a POSIX /tmp path resolves to C:\tmp for the node binary used to
# generate the payload, which does not exist.
SCRATCH=""
STARTED_PID=""
cleanup() {
  [ -n "$SCRATCH" ] && rm -rf "$SCRATCH"
  if [ -n "$STARTED_PID" ]; then
    log "stopping the ingest service started by this script"
    kill "$STARTED_PID" 2>/dev/null || true
  fi
}
# Registered before anything that can fail, so a failed start-up never leaks
# the ingest service this script launched.
trap cleanup EXIT

compose_healthy() { # service
  local i
  for i in $(seq 1 30); do
    docker compose ps "$1" 2>/dev/null | grep -q "healthy" && return 0
    sleep 2
  done
  die "container '$1' never became healthy"
}

if ! curl -sf "$INGEST_URL/actuator/health" >/dev/null 2>&1; then
  [ "$START" = "never" ] && die "ingest-svc is not reachable at $INGEST_URL (start it, or set INGEST_URL)"
  log "starting infrastructure (kafka, postgres, redis)"
  docker compose up -d kafka postgres redis
  compose_healthy kafka
  compose_healthy postgres
  compose_healthy redis
  log "starting ingest-svc (log in /tmp/aegis-loadtest-ingest.log)"
  ./gradlew :ingest-svc:bootRun >/tmp/aegis-loadtest-ingest.log 2>&1 &
  STARTED_PID=$!
  for _ in $(seq 1 90); do
    curl -sf "$INGEST_URL/actuator/health" >/dev/null 2>&1 && break
    sleep 2
  done
  curl -sf "$INGEST_URL/actuator/health" >/dev/null \
    || die "ingest-svc did not come up; see /tmp/aegis-loadtest-ingest.log"
fi

SCRATCH="$(mktemp -d ./.aegis-loadtest.XXXXXX)"
METRICS="$SCRATCH/metrics.txt"

snapshot() {
  curl -sf "$INGEST_URL/actuator/prometheus" > "$METRICS"
}

# Micrometer's Prometheus output names a timer <base>_seconds with
# <base>_seconds_{count,sum,max} and, because percentiles are published by the
# client, <base>_seconds{quantile="0.5"}.
# Sums every series for a counter, so tagged counters (for example
# aegis_anomalies_emitted_total{signal="error_rate"}) and untagged ones are
# both reported as one figure. A counter with no series yet reads 0, which is
# what "never incremented" means.
prom_counter() {
  awk -v m="$1" 'index($1, m "{") == 1 || $1 == m { s += $NF } END { printf "%.0f", s + 0 }' "$METRICS"
}
prom_field() { # exact series name
  awk -v m="$1" '$1 == m { v = $NF } END { print (v == "" ? 0 : v) }' "$METRICS"
}
prom_quantile() { # timer base name (without _seconds), quantile
  awk -v m="$1" -v q="quantile=\"$2\"" \
      'index($1, m "_seconds{") == 1 && index($0, q) > 0 { v = $NF } END { print (v == "" ? "n/a" : v) }' "$METRICS"
}

STAGES="aegis_ingest_dedup aegis_ingest_buffer_offer aegis_ingest_rule aegis_ingest_event_to_rule"

# "base count sum" for every stage, so a step can turn two snapshots into exact
# per-stage means without relying on percentiles.
dump_stages() {
  for base in $STAGES; do
    printf '%s %s %s\n' "$base" \
      "$(prom_field "${base}_seconds_count")" "$(prom_field "${base}_seconds_sum")"
  done
}

stage_means_ms() { # $1 = path of the "before" dump
  awk 'NR==FNR { c[$1]=$2; s[$1]=$3; next }
       { dc = $2 - c[$1]; ds = $3 - s[$1]
         if (dc > 0) printf "%-22s %10.3f\n", $1, ds / dc * 1000
         else        printf "%-22s %10s\n",   $1, "no samples" }' \
      "$1" <(dump_stages)
}

# The producer paces on the wall clock and closes stdin before returning, so
# when this process exits every generated line has been handed to the broker.
generate_paced() { # rate, seconds, spikeSeconds
  node -e '
    const { spawn } = require("child_process");
    const [rate, seconds, dupPct, service, spikeSeconds] = process.argv.slice(1);
    const ratePerSec = Number(rate), phaseMs = Number(seconds) * 1000;
    const dup = Number(dupPct) / 100, spikeMs = Number(spikeSeconds) * 1000;
    const total = Math.round(ratePerSec * Number(seconds));
    const instances = [1, 2, 3, 4].map((i) => `${service}-api-${i}`);
    const reused = [];
    const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

    // The console producer reads one line per record, so without batching it
    // emits one request per event and the broker pays per-record cost for
    // every line: measured at 10k events/s it peaked at 6.5 cores and became
    // the ceiling, while the ingest service it was feeding stayed under one.
    // Real producers accumulate and compress (linger + batch.size + lz4), so
    // the load generator does too; otherwise the ladder measures the rig.
    const child = spawn("docker", [
      "compose", "exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh",
      "--bootstrap-server", "localhost:9092", "--topic", "raw.events",
      "--producer-property", "acks=1",
      "--producer-property", "linger.ms=50",
      "--producer-property", "batch.size=262144",
      "--producer-property", "compression.type=lz4",
      "--producer-property", "max.in.flight.requests.per.connection=5",
    ], { stdio: ["pipe", "inherit", "inherit"] });
    let brokerFailed = null;
    child.on("error", (e) => { brokerFailed = e; });
    child.on("exit", (code) => { if (code !== 0) brokerFailed = new Error(`producer exited ${code}`); });

    const event = (seq, breach) => {
      const instance = instances[seq % instances.length];
      const value = breach ? 8 + Math.random() * 4 : Math.random() * 3;
      const eventId = reused.length > 0 && Math.random() < dup
        ? reused[Math.floor(Math.random() * reused.length)]
        : crypto.randomUUID();
      if (reused.length < 1000) reused.push(eventId);
      return JSON.stringify({
        schemaVersion: 1,
        eventId,
        eventType: "METRIC",
        eventTime: new Date().toISOString(),
        source: `${service}:${instance}`,
        service,
        instance,
        payload: { metric: "error_rate", unit: "percent", value: Number(value.toFixed(3)) },
      });
    };

    (async () => {
      const started = Date.now();
      let sent = 0;
      while (sent < total) {
        if (brokerFailed) throw brokerFailed;
        const elapsed = Date.now() - started;
        const target = Math.min(total, Math.round((elapsed / 1000) * ratePerSec));
        if (target > sent) {
          // Contiguous breach run at the end of the phase: a real incident
          // raises every instance at once, which is what the confirmation
          // streak needs to fire.
          const breach = spikeMs > 0 && elapsed >= phaseMs - spikeMs;
          const batch = [];
          for (let i = sent; i < target; i++) batch.push(event(i, breach));
          child.stdin.write(batch.join("\n") + "\n");
          sent = target;
        } else {
          await sleep(20);
        }
      }
      child.stdin.end();
      await new Promise((resolve, reject) => {
        child.on("exit", (code) => (code === 0 ? resolve() : reject(new Error(`producer exited ${code}`))));
      });
      process.stdout.write(`  produced ${sent} events in ${((Date.now() - started) / 1000).toFixed(1)}s\n`);
    })().catch((e) => { console.error(e.message); process.exit(1); });
  ' "$1" "$2" "$DUPLICATES" "$SERVICE" "$3"
}

snapshot
RECEIVED0=$(prom_counter aegis_events_received_total)
DEDUP0=$(prom_counter aegis_events_deduplicated_total)
ORDERED0=$(prom_counter aegis_events_ordered_total)
STRAGGLER0=$(prom_counter aegis_events_dropped_straggler_total)
OVERFLOW0=$(prom_counter aegis_events_dropped_overflow_total)
ANOMALIES0=$(prom_counter aegis_anomalies_emitted_total)

log "load ladder: rates $RATES events/sec, ${PHASE_SECONDS}s per step, ${DUPLICATES}% duplicates, ${SPIKE}s breach spike"

RESULTS="$SCRATCH/results.txt"
: > "$RESULTS"

IFS=',' read -r -a RATE_LIST <<< "$RATES"
for RATE in "${RATE_LIST[@]}"; do
  RATE="$(echo "$RATE" | tr -d ' ')"
  log "step: offering $RATE events/sec for ${PHASE_SECONDS}s"

  dump_stages > "$SCRATCH/stages-before-$RATE.txt"
  RECV_BEFORE=$(prom_counter aegis_events_received_total)
  DEDUP_BEFORE=$(prom_counter aegis_events_deduplicated_total)
  ORDER_BEFORE=$(prom_counter aegis_events_ordered_total)
  STRAG_BEFORE=$(prom_counter aegis_events_dropped_straggler_total)
  OVF_BEFORE=$(prom_counter aegis_events_dropped_overflow_total)
  ANOM_BEFORE=$(prom_counter aegis_anomalies_emitted_total)
  START_MS=$(date +%s%3N)

  # The generator reports how much it actually managed to produce. That figure
  # is what separates "the pipeline could not keep up" from "the offered rate
  # was never offered", which are very different findings.
  GENERATED="$(generate_paced "$RATE" "$PHASE_SECONDS" "$SPIKE")" \
    || die "the load generator failed"
  echo "$GENERATED"
  PRODUCED=$(printf '%s\n' "$GENERATED" | sed -n 's/.*produced \([0-9]*\) events.*/\1/p')
  PRODUCED=${PRODUCED:-0}
  # The producer paces on the wall clock, so when it returns the offered phase
  # is over. How much the pipeline had completed by that moment is the keep-up
  # figure; the rest is catch-up.
  PRODUCED_MS=$(( $(date +%s%3N) - START_MS ))
  snapshot
  ORDERED_BY_END=$(( $(prom_counter aegis_events_ordered_total) - ORDER_BEFORE ))

  # Everything the pipeline accepted must come out the other side before the
  # figures mean anything: accepted = received - duplicates, and every accepted
  # event is either ordered or dropped, which is exactly what the counters say.
  EXPECTED=$(( RATE * PHASE_SECONDS ))
  DEADLINE=$(( $(date +%s%3N) + 600000 ))
  while [ "$(date +%s%3N)" -lt "$DEADLINE" ]; do
    snapshot
    RECV_D=$(( $(prom_counter aegis_events_received_total) - RECV_BEFORE ))
    ACCEPT_D=$(( RECV_D - $(prom_counter aegis_events_deduplicated_total) + DEDUP_BEFORE ))
    DONE_D=$(( $(prom_counter aegis_events_ordered_total) - ORDER_BEFORE \
              + $(prom_counter aegis_events_dropped_straggler_total) - STRAG_BEFORE \
              + $(prom_counter aegis_events_dropped_overflow_total) - OVF_BEFORE ))
    if [ "$RECV_D" -ge "$EXPECTED" ] && [ "$DONE_D" -ge "$ACCEPT_D" ]; then
      break
    fi
    sleep 1
  done
  ELAPSED_MS=$(( $(date +%s%3N) - START_MS ))

  RECV_D=$(( $(prom_counter aegis_events_received_total) - RECV_BEFORE ))
  DEDUP_D=$(( $(prom_counter aegis_events_deduplicated_total) - DEDUP_BEFORE ))
  ORDER_D=$(( $(prom_counter aegis_events_ordered_total) - ORDER_BEFORE ))
  STRAG_D=$(( $(prom_counter aegis_events_dropped_straggler_total) - STRAG_BEFORE ))
  OVF_D=$(( $(prom_counter aegis_events_dropped_overflow_total) - OVF_BEFORE ))
  ANOM_D=$(( $(prom_counter aegis_anomalies_emitted_total) - ANOM_BEFORE ))

  if [ "$RECV_D" -lt "$EXPECTED" ]; then
    if [ "$PRODUCED" -gt 0 ] && [ "$PRODUCED" -lt "$EXPECTED" ]; then
      die "step $RATE: the generator only produced $PRODUCED of $EXPECTED events, so the
  offered rate was never reached. The limit is on the producing side (generator or
  broker), and the pipeline only received $RECV_D. Fix the generator before reading
  anything into the consumer figures."
    fi
    die "step $RATE: only $RECV_D of $EXPECTED events were consumed (the pipeline could not keep up)"
  fi

  KEEP_UP_RATE=$(awk -v n="$ORDERED_BY_END" -v s="$PHASE_SECONDS" 'BEGIN{ printf "%.0f", n / s }')
  # What the pipeline could have completed by the end of the phase: everything
  # offered, minus the seconds still inside the ordering window and the flush
  # tick. This is the figure that says whether it kept up.
  DUE=$(( RATE * (PHASE_SECONDS - WINDOW_SECONDS - FLUSH_SECONDS) ))
  [ "$DUE" -lt 0 ] && DUE=0
  KEEP_UP_PCT=$(awk -v n="$ORDERED_BY_END" -v t="$DUE" 'BEGIN{ printf "%.0f", (t == 0 ? 0 : n / t * 100) }')
  STREAM_PCT=$(awk -v n="$ORDERED_BY_END" -v t="$EXPECTED" 'BEGIN{ printf "%.0f", (t == 0 ? 0 : n / t * 100) }')
  CATCH_UP_MS=$(( ELAPSED_MS - PRODUCED_MS ))
  DROP_D=$(( STRAG_D + OVF_D ))

  # Once the producer stops, the only thing left to do is drain the backlog, so
  # this is the pipeline's own rate with the broker and the generator out of the
  # way. When 'ordered_by_end_of_phase' is well below the offered rate but this
  # figure is at or above it, the step is limited by the rig (broker + producer
  # on one machine), not by the pipeline.
  if [ "$CATCH_UP_MS" -gt 0 ] && [ "$ORDERED_BY_END" -lt "$EXPECTED" ]; then
    DRAIN_RATE=$(awk -v b="$((EXPECTED - ORDERED_BY_END))" -v ms="$CATCH_UP_MS" \
      'BEGIN { printf "%.0f", b / (ms / 1000) }')
    DRAIN="${DRAIN_RATE}/s"
  else
    DRAIN="n/a (kept up)"
  fi

  {
    echo "step offered=${RATE}/s generated_by_producer=$PRODUCED received=$RECV_D deduplicated=$DEDUP_D ordered=$ORDER_D"
    echo "     generator_wall_ms=$PRODUCED_MS (the offered phase is $((PHASE_SECONDS * 1000))ms; a larger"
    echo "         figure means the generator was throttled by its own pipe, not the pipeline)"
    echo "     ordered_by_end_of_phase=$ORDERED_BY_END = $DUE due by then, so ${KEEP_UP_PCT}% kept up"
    echo "         (${KEEP_UP_RATE}/s sustained, ${STREAM_PCT}% of the whole stream; the rest is the"
    echo "         ${WINDOW_SECONDS}s ordering window + ${FLUSH_SECONDS}s flush tick still in flight)"
    echo "     catch_up_ms=$CATCH_UP_MS drain_rate=$DRAIN dropped_straggler=$STRAG_D dropped_overflow=$OVF_D anomalies=$ANOM_D"
    stage_means_ms "$SCRATCH/stages-before-$RATE.txt" | sed 's/^/     mean /'
  } | tee -a "$RESULTS"

  if [ "$DROP_D" -gt 0 ]; then
    echo "     note: $DROP_D events were dropped, so this rate is past the pipeline's steady-state ceiling"
  fi
done

{
echo
echo "================ ingest load ladder ================"
printf '%s\n' "Each step: offered events/sec, how much of that stream the pipeline completed"
printf '%s\n' "while it was still being produced ('ordered_by_end_of_phase' - the sustained"
printf '%s\n' "rate it actually held), and the mean per-stage cost for the step. 'ordered'"
printf '%s\n' "means the event passed dedup, the out-of-order buffer and the rules."
printf '%s\n' "'kept up' compares that against the events that were due by then: the offered"
printf '%s\n' "stream minus the ${WINDOW_SECONDS}s ordering window and the ${FLUSH_SECONDS}s flush tick,"
printf '%s\n' "which a pipeline that is keeping up still has in flight when the phase ends."
echo
cat "$RESULTS"
echo
printf '%-24s %10s %10s %10s %10s\n' "stage (whole run)" "p50 (ms)" "p95 (ms)" "p99 (ms)" "max (ms)"
for stage in $STAGES; do
  printf '%-24s %10s %10s %10s %10s\n' "${stage#aegis_ingest_}" \
    "$(awk -v v="$(prom_quantile "$stage" 0.5)" 'BEGIN{ printf (v=="n/a" ? "n/a" : "%.3f"), v*1000 }')" \
    "$(awk -v v="$(prom_quantile "$stage" 0.95)" 'BEGIN{ printf (v=="n/a" ? "n/a" : "%.3f"), v*1000 }')" \
    "$(awk -v v="$(prom_quantile "$stage" 0.99)" 'BEGIN{ printf (v=="n/a" ? "n/a" : "%.3f"), v*1000 }')" \
    "$(awk -v v="$(prom_field "${stage}_seconds_max")" 'BEGIN{ printf "%.3f", v*1000 }')"
done
echo
echo "totals since the service started"
printf '  received               : %s\n' "$(( $(prom_counter aegis_events_received_total) - RECEIVED0 ))"
printf '  deduplicated           : %s\n' "$(( $(prom_counter aegis_events_deduplicated_total) - DEDUP0 ))"
printf '  ordered                : %s\n' "$(( $(prom_counter aegis_events_ordered_total) - ORDERED0 ))"
printf '  dropped (straggler)    : %s\n' "$(( $(prom_counter aegis_events_dropped_straggler_total) - STRAGGLER0 ))"
printf '  dropped (overflow)     : %s\n' "$(( $(prom_counter aegis_events_dropped_overflow_total) - OVERFLOW0 ))"
printf '  anomalies emitted      : %s\n' "$(( $(prom_counter aegis_anomalies_emitted_total) - ANOMALIES0 ))"
echo
echo "reading the numbers"
echo "  keep-up                : ordered_by_end_of_phase against the events due by the end"
echo "                           of the phase (offered minus window minus flush tick). Under"
echo "                           100% the pipeline is accumulating backlog, and past window +"
echo "                           grace that backlog is dropped, not delayed."
echo "  drain_rate             : the backlog divided by the catch-up time, i.e. the"
echo "                           pipeline's own rate once the producer stopped competing"
echo "                           for the machine. Read it as the pipeline's capacity;"
echo "                           keep-up below it means the rig, not the pipeline, is the"
echo "                           limit for that step."
echo "  dedup / buffer_offer   : paid once per received event, on the consumer thread"
echo "  rule                   : paid once per ordered sample, on the flush thread"
echo "  event_to_rule          : event time to rule evaluation. It includes the"
echo "                           deliberate 5s ordering window plus however long the"
echo "                           event waited to be produced and consumed, so it is a"
echo "                           staleness figure, not a processing cost."
echo "  percentiles            : Micrometer publishes them over a rolling window, so"
echo "                           the table above describes the end of the run rather"
echo "                           than any single step; the per-step means are exact."
} | tee "${OUT:-/dev/null}"

if [ -n "$OUT" ]; then
  echo "  written to $OUT"
fi
