# Aegis — Autonomous Incident Management & Recovery

AI-powered incident management platform. Simulated failures (metrics spikes,
error logs, instance down) stream through Kafka; an LLM tool-calling agent
correlates evidence, proposes recovery actions, and — gated by human approval —
executes idempotent recovery with automatic verification and rollback. Live
state streams to a React dashboard over WebSocket.

> **Design principle:** the AI agent is a decision component inside a
> deterministic recovery pipeline, not the pipeline itself. Everything safe
> (approval gates, idempotency, verification, rollback, kill switches) is built
> and tested before the LLM is introduced.

## Docs

| Doc | What it is |
|---|---|
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | System design: components, data model, agent loop, safety model, Kafka semantics, interview cheat sheet |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Build order: repo structure, tech stack per module, granular steps with acceptance criteria |
| [`docs/E2E_RUNBOOK.md`](docs/E2E_RUNBOOK.md) | Full-stack walkthrough with the expected payload on every Kafka topic and troubleshooting |
| [`docs/AUDIT.md`](docs/AUDIT.md) | Pre-ship audit: defects found and fixed, security posture, residual risks |
| [`docs/LOADTEST.md`](docs/LOADTEST.md) | Ingest throughput: the load ladder, where the ceiling is, and how it was measured |
| [`design/dashboard.html`](design/dashboard.html) | UI design mockup (live feed, agent trace, approval card, post-mortem, controls) |
| [`dashboard/`](dashboard/) | React + Vite + TypeScript dashboard: STOMP/SockJS live feed, approval gate, sample-data fallback |

## Stack

Java 21 · Spring Boot 3.4 · Spring Kafka · Spring Data JPA + PostgreSQL 16 ·
Redis 7 · WebSocket (STOMP) · hand-rolled LLM tool-calling loop (OpenAI-compatible,
Ollama for dev) · React + Vite + TypeScript · Micrometer/Prometheus/Grafana ·
Docker Compose (→ ECS Fargate stretch)

## Phase status

- [x] **Phase 0 — Foundations:** multi-module Gradle, Compose (Kafka/Postgres/Redis), contracts
- [x] **Phase 1 — Event pipeline:** simulator + scenarios, ingest-svc, dedup, out-of-order buffer, anomaly rules
- [x] **Phase 2 — Incident core:** anomaly→incident wiring, rule-based proposer, approval gate with TTL, idempotent executor, verification, auto-rollback
- [x] **Phase 3 — Realtime + dashboard:** STOMP over WebSocket with SockJS fallback, React + Vite + TypeScript dashboard (design tokens, live feed, approval controls)
- [x] **Phase 4 — Agent layer:** hand-rolled tool-calling loop (investigation tools), fake LLM for CI, agent proposer behind the Proposer interface
- [x] **Phase 5 — Controls + evaluation:** Redis kill switches (propose + execute gates), autonomy policy (confidence/evidence minimums, LOW auto-approve), shadow mode, replay harness with precision/recall
- [x] **Phase 6 — Observability:** Micrometer counters per service, Prometheus + Grafana in compose with a provisioned dashboard
- [x] **Phase 7 — CI/CD + AWS artifacts:** GitHub Actions (backend + dashboard), container images, ECS Fargate task definitions + runbook

## Quickstart

Requires JDK 17+ (Gradle auto-provisions the JDK 21 toolchain) and Docker.

```bash
make up                              # docker compose: kafka, postgres, redis, prometheus, grafana
./gradlew :simulator:bootRun &       # emits metrics/logs/health -> raw.events
./gradlew :ingest-svc:bootRun &      # consumes, normalizes, dedups, detects anomalies
./gradlew :incident-svc:bootRun &    # state machine + REST on :8082
```

Break a service and watch the pipeline:

```bash
curl -X POST localhost:8080/api/scenarios/error-spike \
  -H 'Content-Type: application/json' \
  -d '{"service":"checkout-service","errorRate":8.5,"durationSeconds":60}'
curl localhost:8081/api/stats   # ingest event counters
# anomalies land on the `anomalies` topic after ~7s (buffer + 2-window confirm)

# with AEGIS_API_KEY set, add -H "X-API-Key: $AEGIS_API_KEY" to every /api call
curl -H "X-API-Key: $AEGIS_API_KEY" localhost:8082/api/incidents
```

## Dashboard (Phase 3)

```bash
cd dashboard
npm install
npm run dev        # http://localhost:5173 (proxies /api and /ws to :8082)
```

The dashboard subscribes to `/topic/incidents` and `/topic/proposals` over
STOMP (SockJS fallback) and reads snapshots over REST. With no backend it
renders the approved mockup, clearly labeled as sample data.

## Agent mode (Phase 4)

By default the deterministic rule-based proposer fills the proposal slot. To
run the tool-calling loop instead, the agent replaces it behind the same
`Proposer` interface:

```bash
# Scripted fake LLM (CI / no key): investigates with tools, proposes restart
AGENT_MODE=agent AGENT_FAKE_LLM=true ./gradlew :incident-svc:bootRun

# Real model
OPENAI_API_KEY=... AGENT_MODE=agent ./gradlew :incident-svc:bootRun
```

The loop is hand-rolled (no framework): system prompt + investigation tools
(`get_service_health`, `get_metrics`, `search_logs`, `get_deployments`),
bounded rounds, tool results fed back as messages, verdict parsed to a
proposal draft, full trace stored on the proposal for the dashboard and audit.
The agent can look and propose, never execute: the approval gate is the only
path to execution.

## Controls + evaluation (Phase 5)

Kill switches and policy toggles are Redis-backed and checked in the propose
and execute paths; the Controls view is wired to them:

```bash
curl localhost:8082/api/controls/kill-switches
curl -X PUT localhost:8082/api/controls/kill-switches/global -H 'Content-Type: application/json' -d '{"killed":true}'
curl -X PUT localhost:8082/api/controls/policy/shadow-mode -H 'Content-Type: application/json' -d '{"enabled":true}'

# Replay harness: score the proposer against ground truth
curl -X POST localhost:8082/api/replay/run
curl localhost:8082/api/replay/stats
```

Shadow mode measures the proposer on live incidents without persisting
proposals or executing anything; replay scores it against ground-truth
scenarios (config: `replay.scenarios`) and reports precision/recall, with
`no_action` as the negative class so false positives are visible.

## Observability (Phase 6)

```bash
docker compose up -d prometheus grafana   # or: make up (starts all)
# Prometheus: http://localhost:9090   Grafana: http://localhost:3000 (admin/admin)
```

Counters: `aegis_events_{emitted,received,deduplicated,ordered}`,
`aegis_events_dropped_{straggler,overflow}`, `aegis_anomalies_emitted`,
`aegis_incidents_{opened,resolved}`, `aegis_proposals_{created,approved,rejected,expired,auto_approved}`,
`aegis_actions_{executed,verified,verification_failed,rolled_back,escalated}`. The
provisioned dashboard (`observability/grafana/`) shows pipeline throughput, the
approval gate, and recovery outcomes. Per-stage timers
(`aegis_ingest_{dedup,buffer_offer,rule,event_to_rule}_seconds`) back the
performance figures below.

## Measured ingest performance

The pipeline's behavior on a single Docker Desktop host, measured with a paced
load ladder rather than a burst (`docs/loadtest-ingest.txt`, methodology and
analysis in [`docs/LOADTEST.md`](docs/LOADTEST.md)):

```bash
./scripts/loadtest.sh --rates 200,1000,2000,5000,7000,10000 --phase-seconds 30 \
    --out docs/loadtest-ingest.txt
```

| offered | accepted | ordered | kept up | dropped (straggler + overflow) |
| --- | --- | --- | --- | --- |
| 200/s | 5,453 | 5,453 | 93% | 0 |
| 1,000/s | 27,059 | 27,059 | 94% | 0 |
| 2,000/s | 54,069 | 54,069 | 93% | 0 |
| 5,000/s | 135,237 | 135,237 | 92% | 0 |
| 7,000/s | 189,060 | 189,060 | 96% | 0 |
| 10,000/s | 270,077 | 270,077 | 74% | 0 |

`kept up` is what completed against what was due by the end of the phase, which
subtracts the pipeline's deliberate 5s ordering window and 1s flush tick.

**Nothing is dropped at any rate, including 10,000/s.** `ordered` equals
`accepted` at every step: the pipeline never discards a straggler or overflows a
queue. Up to 7,000 events/s it keeps up, with `event_time -> rule` staleness flat
at 5.5s and per-stage means flat (dedup 0.083ms/event, buffer offer 0.002ms, rule
0.006ms/sample). At 10,000/s it completes 74% while the stream is being produced,
drains the rest at 9,729/s, and its signals are 10.4s stale instead of 5.5s.

The limit at 10,000/s is the test rig, not the service: ingest-svc used under one
core of sixteen while it fell behind, Redis stayed under 2% (`INFO commandstats`:
8.77us per `SET`), and the Kafka broker saturated at 652% CPU because the load
generator initially fed it one produce request per record. Batching and
compressing the generator took that step from 40% to 54% kept up and halved
broker CPU. The measurements and the reasoning are in
[`docs/LOADTEST.md`](docs/LOADTEST.md).

The ceiling used to be ~490 events/s: one Redis round trip per event in dedup
plus one per rule evaluation, on single-partition, single-threaded consumption.
The consumer now claims a whole poll batch in one pipelined round trip and the
rules read and write their state once per flush batch, so the round trips are
per batch instead of per event. The ordered/dropped counters keep overload
visible instead of silent.

## CI/CD + AWS (Phase 7)

- CI: `.github/workflows/ci.yml` (Gradle build incl. Testcontainers E2E on
  GitHub runners, plus the dashboard build).
- Images: `deploy/Dockerfile.*`; dashboard image serves the bundle and
  proxies `/api` + `/ws` to incident-svc via nginx.
- AWS: `deploy/aws/` — ECS Fargate task definitions and a runbook (MSK, RDS,
  ElastiCache, ALB). Deployment needs your AWS account.

## Security

The control plane is not open by default in a deployment.

- **API key on `/api`** of incident-svc and the simulator: set `AEGIS_API_KEY`
  and every control call (approve, reject, kill switches, replay, fault
  injection, action execution) requires the `X-API-Key` header. Unset means
  authentication is disabled and both services log a warning at startup, which
  is the local-development and test path.
- **The shared key stays server-side.** The browser never holds it: the nginx
  image (`deploy/nginx.conf`) and the Vite dev proxy inject the header on the
  requests they forward. To approve *as a person* the operator signs in from the
  dashboard with their own operator key, which is exchanged for a token in
  `sessionStorage` and sent only on decisions — read-only calls stay on the
  proxy's shared credential, so an expired token cannot stop the board from
  loading. Two operators can therefore complete a two-person approval entirely
  in the UI: sign in, approve, sign out, sign in as the second operator,
  approve.
- **Verified operator identity, not a self-declared name.** Approvals are
  attributed to the credential that made them, never to a request field: the
  shared key resolves to a non-attributable client identity, per-person keys
  arrive in `X-Operator-Key` and map to named identities, and a signed token's
  subject is the approver. HIGH-risk actions need two distinct named operators
  (`policy.two-person-levels`), the same operator cannot supply both signatures,
  and the shared key cannot contribute either one.
- **Approvals are attested, not just noted.** `POST /api/auth/session` exchanges
  a verified operator credential for a short-lived signed token (HS256 from a
  built-in issuer, or RS256 from a real provider via
  `aegis.security.oidc.issuer-uri` and OIDC discovery). A decision sends it as
  `Authorization: Bearer`, and the approval record keeps the subject, the issuer
  and the token id: the audit trail names the exact document that signed, not
  just the person who could have. Tokens expire in minutes and a bearer token
  that does not verify is refused rather than falling back to a weaker key.
- **The incident feed is behind a ticket.** The STOMP endpoint requires a
  single-use ticket in the CONNECT frame (`POST /api/ws-ticket`, 60s TTL), issued
  through the authenticated proxy, so the browser never sees the API key and
  `/ws` is no longer readable by anything that can reach the port.
- **Read-only surface left open:** `/actuator/health` and
  `/actuator/prometheus`, which need no credential for health checks and
  scraping. See [`docs/AUDIT.md`](docs/AUDIT.md) for the residual risk list.
- **Containers run as a non-root user** (`deploy/Dockerfile.*`), Grafana
  anonymous access is read-only, and secrets are expected from the environment
  (`.env` is gitignored; `.env.example` documents every variable).

## End-to-end verification (needs Docker)

```bash
# 1. Automated: the whole loop in Testcontainers (anomaly -> approve -> verify)
./gradlew :incident-svc:test --tests '*IncidentLifecycleE2ETest*'

# 2. Full stack with live Kafka topic dumps at every milestone
make reset
./scripts/e2e-verify.sh                          # happy: restart -> verified
./scripts/e2e-verify.sh --stubborn               # rollback: verify fails -> escalate
./scripts/e2e-verify.sh --capture docs/e2e-dumps # also save the topic dumps
```

The script stops the containers when it finishes and leaves the data volumes
in place; pass `--wipe` if you want a clean database, or `--keep` to leave the
stack running.

Both paths were run against the real stack (Kafka, PostgreSQL and Redis in
Docker, the three services on the host). Scripted results:

| Path | Incident status | Timeline |
| --- | --- | --- |
| `--` (happy) | `RESOLVED` | `OPENED, PROPOSAL, PROPOSAL_APPROVED, ACTION_EXECUTED, VERIFIED` |
| `--stubborn` | `RESOLVED` (through rollback) | `OPENED, PROPOSAL, PROPOSAL_APPROVED, ACTION_EXECUTED, ACTION_FAILED, ROLLBACK_SKIPPED, ESCALATED` |

`ROLLBACK_SKIPPED` is correct for the restart action: its undo plan is a
no-op, so the rollback engine records that there was nothing to undo and the
incident escalates to a human. Actions that do change state (scale, rollback a
deploy) execute their undo plan instead.

### Captured Kafka topic dumps

Recorded with `--capture docs/e2e-dumps` on a freshly recreated Kafka (the
`.ndjson` files live under that directory; each `--capture` run rewrites them).
Topics are dumped with the broker's own CLI, so these are the bytes on the wire.

**`raw.events` — input, `checkout-service` error-rate samples**

```json
{"schemaVersion":1,"eventId":"408e1e97-c0e5-48da-af7b-2eaa1105713b","eventType":"METRIC","eventTime":"2026-09-28T14:17:40.163480900Z","source":"checkout-service:checkout-service-api-1","service":"checkout-service","instance":"checkout-service-api-1","payload":{"value":8.381,"unit":"percent","metric":"error_rate"}}
```

**`anomalies` — one confirmed anomaly, after the 5s ordering buffer and the 2-window streak rule**

```json
{"eventId":"9e4d0fcc-5d44-4f85-8642-f9aead25a92b","sourceEventId":"73515b8d-5a04-444f-a694-3953c4418c8d","service":"checkout-service","signal":"error_rate","value":8.221,"threshold":5.0,"severity":"SEV2","windowStart":"2026-09-28T14:17:25.105381900Z"}
```

**`action.commands` — the only message that can cause a change, published after approval commits**

```json
{"commandId":"7e81966f-b484-489d-b3e7-7e814b9a19d5","proposalId":"6041b0e0-02a5-42f9-9f35-6e0d1225104d","incidentId":"b192c04b-ff27-4a84-a9dc-7b5178282014","service":"checkout-service","actionType":"restart_instance","params":{"target":"checkout-service"},"issuedAt":"2026-09-28T14:18:04.907297300Z"}
```

**`incidents` — happy path state feed (`AWAITING_APPROVAL` x2, then resolve)**

```json
{"eventId":"e8a9945e-962c-4196-b856-2639301caed1","incidentId":"b192c04b-ff27-4a84-a9dc-7b5178282014","service":"checkout-service","severity":"SEV2","status":"AWAITING_APPROVAL","summary":"error_rate 8.221 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:17:30.290548700Z","occurredAt":"2026-09-28T14:17:30.434987700Z"}
{"eventId":"15c31932-38b1-4419-b2e2-fde943704a76","incidentId":"b192c04b-ff27-4a84-a9dc-7b5178282014","service":"checkout-service","severity":"SEV2","status":"AWAITING_APPROVAL","summary":"error_rate 8.221 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:17:30.290548700Z","occurredAt":"2026-09-28T14:17:30.663256600Z"}
{"eventId":"b5aa8706-78c2-4e6f-82b5-913fcd880b4b","incidentId":"b192c04b-ff27-4a84-a9dc-7b5178282014","service":"checkout-service","severity":"SEV2","status":"VERIFYING","summary":"error_rate 8.221 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:17:30.290549Z","occurredAt":"2026-09-28T14:18:05.156162200Z"}
{"eventId":"eb71fffc-81ea-4fac-b7d1-fa6ae4c60418","incidentId":"b192c04b-ff27-4a84-a9dc-7b5178282014","service":"checkout-service","severity":"SEV2","status":"RESOLVED","summary":"error_rate 8.221 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:17:30.290549Z","occurredAt":"2026-09-28T14:18:11.442294700Z"}
```

**`incidents` — rollback path, verification fails and the incident escalates**

```json
{"eventId":"643880c7-9924-4955-8a11-0a0ea03effa3","incidentId":"0d999eda-74d9-4337-8f70-6e4d7807b9c8","service":"checkout-service","severity":"SEV2","status":"AWAITING_APPROVAL","summary":"error_rate 8.561 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:20:10.211958200Z","occurredAt":"2026-09-28T14:20:10.346740100Z"}
{"eventId":"499443e7-86d5-4a37-8d8c-5d353e18112b","incidentId":"0d999eda-74d9-4337-8f70-6e4d7807b9c8","service":"checkout-service","severity":"SEV2","status":"AWAITING_APPROVAL","summary":"error_rate 8.561 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:20:10.211958200Z","occurredAt":"2026-09-28T14:20:10.555556700Z"}
{"eventId":"fe1dedfc-5cfc-4848-8a4f-f75edaef2231","incidentId":"0d999eda-74d9-4337-8f70-6e4d7807b9c8","service":"checkout-service","severity":"SEV2","status":"VERIFYING","summary":"error_rate 8.561 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:20:10.211958Z","occurredAt":"2026-09-28T14:20:44.815947500Z"}
{"eventId":"2a0d761f-0c71-4ed3-a22d-c9867d955fce","incidentId":"0d999eda-74d9-4337-8f70-6e4d7807b9c8","service":"checkout-service","severity":"SEV2","status":"FAILED","summary":"error_rate 8.561 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:20:10.211958Z","occurredAt":"2026-09-28T14:20:55.151386500Z"}
{"eventId":"51267268-8ddf-4825-bf18-5e260297d896","incidentId":"0d999eda-74d9-4337-8f70-6e4d7807b9c8","service":"checkout-service","severity":"SEV2","status":"RESOLVED","summary":"error_rate 8.561 (threshold 5.0) on checkout-service","openedAt":"2026-09-28T14:20:10.211958Z","occurredAt":"2026-09-28T14:20:55.162829900Z"}
```

The `incidents` topic carries the state feed. The finer-grained per-incident
timeline (`ACTION_FAILED`, `ROLLBACK_SKIPPED`, `ESCALATED`) is an
`incident_events` row served by `GET /api/incidents/{id}/events`, which is what
the table above quotes; `--capture` writes the topic bytes only.

Details, expected topic payloads, and troubleshooting: [`docs/E2E_RUNBOOK.md`](docs/E2E_RUNBOOK.md).
Set `AEGIS_API_KEY` in the shell to run the script against a stack with
authentication enabled; it then sends the header on every call.