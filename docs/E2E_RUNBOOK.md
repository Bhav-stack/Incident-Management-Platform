# End-to-End Verification Runbook

Two ways to verify the full loop, both requiring Docker:

| Path | Command | What it runs |
|---|---|---|
| Automated | `./gradlew :incident-svc:test --tests '*E2E*'` | The whole loop in Testcontainers (Kafka, Redis, Postgres) with a scripted simulator. Fast, repeatable, CI-friendly. |
| Full stack | `./scripts/e2e-verify.sh` | The real simulator + all three services against the Compose stack, with live dumps of every Kafka topic at each milestone. |

Both verify the same contract:

```
error-spike -> raw.events -> ingest-svc (dedup, order, 2-window confirm)
   -> anomalies -> incident-svc (open, correlate, propose)
   -> proposals -> approve -> action.commands -> executor (lock, undo capture)
   -> simulator action -> verify -> resolved            (happy path)
   -> verify deadline -> rollback -> escalated          (stubborn path)
```

---

## 1. Automated verification (Testcontainers)

```bash
./scripts/e2e-verify.sh --help   # n/a
# Instead:
./gradlew :incident-svc:test --tests '*IncidentLifecycleE2ETest*'
```

`IncidentLifecycleE2ETest` starts Kafka, Redis, and PostgreSQL in containers,
points incident-svc at them, and replaces only the `SimulatorClient` with a
stub. It then drives the real code paths end to end:

- `approvalLeadsToExecutionVerificationAndResolution` - publishes an anomaly,
  waits for the incident to reach `AWAITING_APPROVAL`, approves via REST,
  and asserts the incident resolves with a `VERIFIED` timeline event and the
  proposal marked `EXECUTED`. Windows are shrunk via properties
  (`action.verify-window=2s`, `action.verify-poll-interval=1s`).
- `stubbornFailureIsRolledBackAndEscalated` - the stub simulator reports the
  error rate never recovering, so verification fails after the window and the
  incident resolves through the rollback path with `ACTION_FAILED` and
  `ESCALATED` timeline events.

Skipped automatically when Docker is unavailable (`@Testcontainers(disabledWithoutDocker = true)`).

## 2. Full stack verification (script)

Requirements: Docker with compose v2, JDK 17+ (Gradle auto-provisions the
JDK 21 toolchain), `python3` (used only to parse JSON from the REST API).

```bash
make reset          # clean slate: wipes compose volumes (fresh DBs)
./scripts/e2e-verify.sh            # happy path
./scripts/e2e-verify.sh --stubborn # rollback path
```

The script tears the stack down on exit (`docker compose down -v`). Add
`--keep` to leave everything running and inspect topics by hand.

### What you should see at each milestone

| Milestone | Topic | Expected content |
|---|---|---|
| Emitters running | `raw.events` | `RawEvent` envelopes: `{"schemaVersion":1,"eventId":"...","eventType":"METRIC","eventTime":"...","source":"checkout-service:checkout-api-1",...}` every 5s per instance; LOG and HEALTH too |
| ~7s after the spike (5s buffer + 2 samples) | `anomalies` | `AnomalyEvent`: `{"eventId":"...","sourceEventId":"...","service":"checkout-service","signal":"error_rate","value":8.x,"threshold":5.0,"severity":"SEV2","windowStart":"..."}` |
| Incident opens | `incidents` | `IncidentStateEvent` with `status":"AWAITING_APPROVAL"` (via OPEN -> INVESTIGATING -> PROPOSAL) |
| Approve | `action.commands` | `ActionCommandEvent`: `{"commandId":"...","proposalId":"...","incidentId":"...","service":"checkout-service","actionType":"restart_instance","params":{"target":"checkout-service"},...}` |
| Happy path | `incidents` | Final `IncidentStateEvent` with `"status":"RESOLVED"`; timeline contains `VERIFIED` |
| Stubborn path | `incidents` | `ACTION_FAILED`, `ROLLBACK_SKIPPED`, `ESCALATED` in the timeline; status `RESOLVED` (rolled back) |

### Manual walkthrough (instead of the script)

```bash
docker compose up -d
./gradlew :simulator:bootRun     &   # terminal 1
./gradlew :ingest-svc:bootRun    &   # terminal 2
./gradlew :incident-svc:bootRun  &   # terminal 3

curl -X POST localhost:8080/api/scenarios/error-spike \
  -H 'Content-Type: application/json' \
  -d '{"service":"checkout-service","errorRate":8.5,"durationSeconds":120}'

# ~7s later: incident awaiting approval
curl localhost:8082/api/incidents

# approve the proposal
curl localhost:8082/api/incidents   # copy the incident externalId
curl localhost:8082/api/incidents/<id>/proposals   # copy the proposal externalId
curl -X POST localhost:8082/api/proposals/<proposalId>/approve \
  -H 'Content-Type: application/json' -d '{"approver":"me"}'

# watch it resolve (happy) or roll back (if you also ran the stubborn scenario)
curl localhost:8082/api/incidents/<id>/events

# dump any topic:
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic incidents \
  --from-beginning --timeout-ms 5000
```

### Rollback demo (manual)

```bash
curl -X POST localhost:8080/api/scenarios/stubborn \
  -H 'Content-Type: application/json' \
  -d '{"service":"checkout-service","durationSeconds":120}'
```
With `action.verify-window` at its default 60s, the incident resolves through
the rollback path about a minute after approval: restart executes, metrics
stay elevated (stubborn), verification fails, the incident escalates
(restart has no inverse) and resolves as rolled back. For a fast demo, start
incident-svc with `ACTION_VERIFY_WINDOW=10s ACTION_VERIFY_POLL_INTERVAL=2s`.

---

## 3. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `docker-entrypoint-initdb.d` scripts did not run | Init scripts run only on the first volume creation. Run `make reset` (docker compose down -v) and `docker compose up -d` again. |
| Old incidents still open when re-running | A new anomaly merges into the open incident instead of opening a new one (by design). Use `make reset` for a clean slate. |
| Ports 8080/8081/8082 or 9092/5432/6379 busy | Change ports in `docker-compose.yml` / service `application.yml`, or stop the conflicting process. |
| `bootRun` fails to compile on JDK 17 | Expected on first run: Gradle downloads the JDK 21 toolchain via the foojay resolver. Needs network on first build. |
| No anomaly appears | Check `raw.events` is flowing, then that `ingest-svc` sees Redis + Postgres (`curl localhost:8081/api/stats` increments `total`). The anomaly needs 2 consecutive breach samples and passes through the 5s ordering buffer. |
| Approval does nothing | Check `/tmp/aegis-incident.log`: the executor requires the proposal to be APPROVED; a rejected/expired proposal is skipped by design. |
| Kafka listeners not consuming | Consumer group offsets start at `earliest` in dev config; if you changed `auto-offset-reset`, restart with a new group id. |