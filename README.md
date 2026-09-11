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
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | System design: components, data model, agent loop, safety model, Kafka semantics, interview cheat sheet |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Build order: repo structure, tech stack per module, granular steps with acceptance criteria |
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

## Quickstart (Phase 0)

Requires JDK 17+ (Gradle auto-provisions the JDK 21 toolchain) and Docker.

```bash
make up                              # docker compose: kafka, postgres, redis
./gradlew :simulator:bootRun &       # emits metrics/logs/health -> raw.events
./gradlew :ingest-svc:bootRun &      # consumes, normalizes, counts (dedup next)
./gradlew :incident-svc:bootRun &    # state machine + REST on :8082
```

Break a service and watch the pipeline (Phase 1):

```bash
curl -X POST localhost:8080/api/scenarios/error-spike \
  -H 'Content-Type: application/json' \
  -d '{"service":"checkout-service","errorRate":8.5,"durationSeconds":60}'
curl localhost:8081/api/stats   # ingest event counters
# anomalies land on the `anomalies` topic after ~7s (buffer + 2-window confirm)
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

Counters: `aegis_events_{emitted,received,deduplicated}`, `aegis_anomalies_emitted`,
`aegis_incidents_{opened,resolved}`, `aegis_proposals_{created,approved,rejected,expired,auto_approved}`,
`aegis_actions_{executed,verified,verification_failed,rolled_back,escalated}`. The
provisioned dashboard (`observability/grafana/`) shows pipeline throughput, the
approval gate, and recovery outcomes.

## CI/CD + AWS (Phase 7)

- CI: `.github/workflows/ci.yml` (Gradle build incl. Testcontainers E2E on
  GitHub runners, plus the dashboard build).
- Images: `deploy/Dockerfile.*`; dashboard image serves the bundle and
  proxies `/api` + `/ws` to incident-svc via nginx.
- AWS: `deploy/aws/` — ECS Fargate task definitions and a runbook (MSK, RDS,
  ElastiCache, ALB). Deployment needs your AWS account.

## End-to-end verification (needs Docker)

```bash
# 1. Automated: the whole loop in Testcontainers (anomaly -> approve -> verify)
./gradlew :incident-svc:test --tests '*IncidentLifecycleE2ETest*'

# 2. Full stack with live Kafka topic dumps at every milestone
make reset
./scripts/e2e-verify.sh             # happy path: restart -> verified -> resolved
./scripts/e2e-verify.sh --stubborn  # rollback path: verify fails -> auto-rollback
```

Details, expected topic payloads, and troubleshooting: [`docs/E2E_RUNBOOK.md`](docs/E2E_RUNBOOK.md).

Later phases: `make demo` (scripted failure scenario end-to-end) and
`make replay` (agent evaluation harness).