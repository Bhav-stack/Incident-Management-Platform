# End-to-End Implementation Plan

Companion to [`ARCHITECTURE.md`](../ARCHITECTURE.md) (system design, safety model,
Kafka semantics) and [`design/dashboard.html`](../design/dashboard.html) (UI
mockup). This document is the build order: repo structure, tech stack per
module, and granular steps with acceptance criteria.

---

## 1. Target Repository Structure

```
incident-platform/
├── settings.gradle.kts              # module list (Kotlin DSL)
├── build.gradle.kts                 # root build: versions, plugin management
├── gradle/libs.versions.toml        # version catalog (single source of truth)
├── docker-compose.yml               # kafka, postgres, redis, prometheus, grafana, loki
├── .env.example
├── Makefile                         # dev shortcuts: up, down, reset, demo, replay
├── README.md
├── docs/
│   ├── ARCHITECTURE.md
│   ├── IMPLEMENTATION_PLAN.md       # this file
│   └── design/dashboard.html        # UI mockup
├── contracts/                       # shared event DTOs (plain Java records, no Spring)
│   └── src/main/java/io/aegis/contracts/events/
│       ├── RawEvent.java            # schema_version, event_id, event_time, source, payload
│       ├── AnomalyEvent.java
│       ├── IncidentEvent.java
│       ├── ProposalEvent.java
│       ├── ActionCommandEvent.java
│       ├── ActionResultEvent.java
│       └── AuditEvent.java
├── simulator/
│   ├── src/main/java/io/aegis/simulator/
│   │   ├── SimulatorApplication.java
│   │   ├── config/SimulatorConfig.java
│   │   ├── emitters/                # MetricEmitter, LogEmitter, HealthEmitter
│   │   ├── scenarios/               # Scenario, ScenarioRunner, ScenarioCatalog
│   │   ├── api/ScenarioController.java   # HTTP "break it" endpoint for demos
│   │   └── kafka/EventPublisher.java     # KafkaTemplate, idempotent producer
│   └── src/test/java/io/aegis/simulator/...
├── ingest-svc/
│   ├── src/main/java/io/aegis/ingest/
│   │   ├── IngestApplication.java
│   │   ├── consumer/RawEventConsumer.java       # @KafkaListener, at-least-once
│   │   ├── pipeline/
│   │   │   ├── EventNormalizer.java
│   │   │   ├── EventDeduplicator.java           # Redis SETNX + Postgres unique index
│   │   │   ├── OutOfOrderBuffer.java            # event-time watermarking (5s)
│   │   │   └── AnomalyDetector.java             # rules + confirmation windows
│   │   ├── rules/AnomalyRule.java               # SPI: ErrorRateRule, P99Rule, 5xxRule, InstanceDownRule
│   │   ├── kafka/AnomalyPublisher.java
│   │   └── store/MetricStore.java               # rolling window storage (Redis / timeseries)
│   └── src/test/java/io/aegis/ingest/...        # Testcontainers integration tests
├── incident-svc/                                # Phase 1 "core" — state machine, actions, WS
│   ├── src/main/java/io/aegis/incident/
│   │   ├── IncidentApplication.java
│   │   ├── domain/
│   │   │   ├── Incident.java                    # aggregate
│   │   │   ├── IncidentStatus.java              # enum
│   │   │   ├── IncidentTransition.java          # validated transition table
│   │   │   ├── Proposal.java / ProposalStatus.java
│   │   │   ├── Action.java / ActionStatus.java
│   │   │   └── Severity.java
│   │   ├── app/
│   │   │   ├── IncidentService.java             # application service (transactional)
│   │   │   ├── ProposalService.java
│   │   │   ├── ActionExecutor.java              # Phase 1: lives here; Phase 4: moved to action-svc
│   │   │   ├── ActionVerifier.java              # polls metrics after execution
│   │   │   └── RollbackEngine.java              # applies undo_params
│   │   ├── rules/RuleBasedProposer.java         # Phase 1 placeholder intelligence
│   │   ├── events/KafkaPublisher.java
│   │   ├── ws/
│   │   │   ├── WebSocketConfig.java             # STOMP + SockJS
│   │   │   └── DashboardNotifier.java           # /topic/incidents, /topic/incident/{id}
│   │   ├── api/                                 # IncidentController, ApprovalController, KillSwitchController
│   │   ├── repo/                                # Spring Data JPA repositories
│   │   └── redis/                               # LiveIncidentCache, ActionLockStore, DedupStore
│   └── src/test/java/io/aegis/incident/...      # domain unit tests + Testcontainers E2E
├── agent-svc/                                   # Phase 4 — extracted from incident-svc
│   ├── src/main/java/io/aegis/agent/
│   │   ├── AgentApplication.java
│   │   ├── loop/
│   │   │   ├── AgentLoop.java                   # hand-rolled tool_calls loop
│   │   │   ├── LoopBudget.java                  # max calls, tokens, timeout
│   │   │   └── LlmClient.java                   # OpenAI-compatible client abstraction
│   │   ├── tools/
│   │   │   ├── Tool.java                        # SPI: name, description, jsonSchema(), execute()
│   │   │   ├── ToolRegistry.java                # Spring-injected map<name, Tool>
│   │   │   ├── GetServiceHealthTool.java
│   │   │   ├── GetMetricsTool.java
│   │   │   ├── SearchLogsTool.java
│   │   │   ├── GetDeploymentsTool.java
│   │   │   ├── GetIncidentContextTool.java
│   │   │   ├── ProposeActionTool.java           # the ONLY write path
│   │   │   └── RequestHumanHelpTool.java
│   │   ├── policy/
│   │   │   ├── ProposalValidator.java           # risk tier, confidence, evidence count
│   │   │   ├── RiskPolicy.java                  # config-driven matrix
│   │   │   ├── CooldownGuard.java               # Redis TTL per service
│   │   │   ├── ActionBudgetGuard.java
│   │   │   └── KillSwitchGuard.java
│   │   ├── eval/
│   │   │   ├── ReplayHarness.java               # replay recorded events → score proposals
│   │   │   └── EvalReport.java                  # precision / recall / FPR
│   │   ├── shadow/ShadowModeGate.java
│   │   ├── reasoning/AgentRunRecorder.java      # agent_runs persistence + timeline API
│   │   └── fake/FakeLlmClient.java              # scripted tool-call sequences for CI
│   └── src/test/java/io/aegis/agent/...         # fake-LLM loop tests, policy unit tests
├── action-svc/                                  # Phase 4 — extracted from incident-svc
│   ├── src/main/java/io/aegis/action/
│   │   ├── ActionApplication.java
│   │   ├── executor/
│   │   │   ├── ActionExecutor.java              # idempotent: Redis lock + dedup
│   │   │   ├── ActionHandlers.java              # RestartHandler, ScaleHandler, RollbackHandler, ClearCacheHandler
│   │   │   └── UndoPlan.java                    # undo_params captured before execution
│   │   ├── verify/ActionVerifier.java           # watch metrics N min, decide success
│   │   ├── rollback/RollbackEngine.java
│   │   └── api/SimulatedTargetClient.java       # talks to simulator to "heal"/"break" services
│   └── src/test/java/io/aegis/action/...
├── dashboard/                                   # React + Vite + TS
│   ├── src/
│   │   ├── main.tsx
│   │   ├── App.tsx
│   │   ├── ws/stompClient.ts                    # @stomp/stompjs wrapper
│   │   ├── api/incidents.ts                     # REST detail queries
│   │   ├── types/incident.ts
│   │   ├── components/
│   │   │   ├── LiveFeed.tsx
│   │   │   ├── IncidentDetail.tsx
│   │   │   ├── AgentTrace.tsx                   # reasoning timeline
│   │   │   ├── ProposalCard.tsx                 # approve/reject
│   │   │   ├── PostMortem.tsx
│   │   │   ├── KillSwitchPanel.tsx
│   │   │   └── StatusBadge.tsx / SeverityBadge.tsx
│   │   └── styles/
│   └── package.json                             # vite, react, typescript, @stomp/stompjs
├── observability/
│   ├── prometheus/prometheus.yml                # scrape configs for all services
│   ├── grafana/provisioning/dashboards/*.json   # incident funnel, MTTR, agent metrics
│   └── loki/loki-config.yml
├── scenarios/
│   ├── connection-pool-exhaustion.json
│   ├── bad-deploy-rollback.json
│   ├── cache-stampede.json
│   ├── instance-down.json
│   ├── ... (8–12 total) + demo-script.md
├── infra/
│   ├── docker-compose.yml                       # local
│   ├── aws/terraform/                           # ECS Fargate or EKS (stretch)
│   └── .github/workflows/ci.yml                 # build + Testcontainers tests + deploy
└── scripts/
    ├── seed-scenario.sh
    └── replay-eval.sh
```

---

## 2. Tech Stack & Where It Is Used

| Concern | Technology | Where / how |
|---|---|---|
| Language / runtime | Java 21 (records, sealed interfaces, virtual threads where useful) | All backend modules |
| Framework | Spring Boot 3.4.x | All services |
| Build | Gradle 8.x, Kotlin DSL + version catalog | Root, `libs.versions.toml` |
| Messaging | Spring Kafka (`KafkaTemplate`, `@KafkaListener`), `enable.idempotence=true` producers | All producers/consumers |
| DB | Spring Data JPA + PostgreSQL 16 | incident-svc (source of truth) |
| Migrations | Flyway (`V1__init.sql` …) | incident-svc |
| Cache / realtime state | Spring Data Redis (`StringRedisTemplate`, `SETNX`, TTL, `RedissonClient` for locks) | dedup, locks, cooldowns, kill switches, live cache |
| Realtime push | Spring WebSocket + STOMP + SockJS | incident-svc → dashboard |
| REST / docs | Spring Web MVC, `springdoc-openapi` | API controllers |
| Agent loop | Hand-rolled `tool_calls` loop against OpenAI-compatible API (OpenAI/Anthropic; Ollama for local dev) | agent-svc |
| Structured output | Jackson + strict JSON schema validation (hand-written schema per tool) | agent-svc |
| Metrics | Micrometer + Prometheus registry (`/actuator/prometheus`) | every service |
| Dashboards | Grafana (provisioned JSON dashboards) | observability/ |
| Logs | Logback JSON encoder → Loki (local); structured logs also emitted as Kafka events by simulator | all |
| Tests | JUnit 5, AssertJ, Testcontainers (Kafka, PostgreSQL, Redis), fake LLM client, WireMock for simulator API | all backend modules |
| Load test | Gatling or k6 (`scripts/`) | pipeline throughput |
| Frontend | Vite + React 18 + TypeScript, `@stomp/stompjs`, plain CSS (or Tailwind v4) | dashboard/ |
| Infra (local) | Docker Compose: `apache/kafka` (KRaft), `postgres:16`, `redis:7`, Prometheus, Grafana, Loki | infra/ |
| Infra (AWS) | ECS Fargate + ALB (WSS), RDS, ElastiCache, MSK; Terraform | infra/aws (Phase 6) |
| CI | GitHub Actions: `gradle build` with Testcontainers, then build/push dashboard | infra/.github |

**Deliberate omissions (know why you omitted them):** no Avro/Schema Registry in
MVP (JSON + versioned envelope; add Apicurio later if needed), no Kafka Streams
(the pipeline is simple enough for plain consumers + Redis state), no
LangChain4j/Spring AI (hand-rolled loop is the resume differentiator), no
Kubernetes until Phase 6.

---

## 3. Implementation Steps by Phase

> Convention per step: **Goal → Files → Do → Acceptance criteria.**
> "Done" for a step = its acceptance criteria pass, not "code compiles."

### Phase 0 — Foundations (2–3 days)

**0.1 Repo bootstrap**
- Files: `settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `contracts/`, `.gitignore`, `README.md`.
- Do: multi-module Gradle with `contracts`, `simulator`, `ingest-svc`, `incident-svc`. Java toolchain 21. Dependency versions centralized.
- Accept: `./gradlew build` passes with modules; `./gradlew :ingest-svc:bootRun` starts an empty Spring context.

**0.2 Docker Compose**
- Files: `infra/docker-compose.yml`, `.env.example`, `Makefile`.
- Do: Kafka (KRaft, 1 broker, `apache/kafka:3.7`), PostgreSQL 16, Redis 7 with healthchecks and named volumes; later add Prometheus/Grafana/Loki.
- Accept: `make up` → `docker compose ps` all healthy; `kafka-topics.sh` can create topics; `psql` connects; `redis-cli ping` → PONG.

**0.3 Contract module + DB skeleton**
- Files: `contracts/.../RawEvent.java` + envelope fields; `incident-svc` Flyway `V1__init.sql` (incidents, incident_events).
- Do: define `RawEvent` record; wire Flyway + JPA datasource; add `/actuator/health`.
- Accept: an integration test starts Postgres via Testcontainers, runs migrations, saves an `Incident`.

### Phase 1 — Event pipeline (1 week)

**1.1 Event envelope & shared DTOs**
- Files: all records in `contracts/events/` (`RawEvent`, `AnomalyEvent`, `IncidentEvent`, `ProposalEvent`, `ActionCommandEvent`, `ActionResultEvent`, `AuditEvent`).
- Do: JSON serialization with Jackson; `event_id` as UUID; `event_time` from producer clock; topic constant class.
- Accept: unit test round-trips each record through JSON.

**1.2 Simulator**
- Files: `MetricEmitter`, `LogEmitter`, `HealthEmitter`, `EventPublisher`, `SimulatorConfig`.
- Do: scheduled emitters producing realistic data: metrics every 5s per service (cpu, error_rate, p99, memory), structured logs on demand, health heartbeats every 10s. Producer `enable.idempotence=true`, key `service:instance`.
- Accept: `docker compose up` + run → `kafka-console-consumer` shows well-formed events; duplicate producer retries don't duplicate `event_id`s (send same id twice manually).

**1.3 ingest-svc consumer + dedup**
- Files: `RawEventConsumer`, `EventNormalizer`, `EventDeduplicator`, `DedupStore` (Redis + Postgres unique index `anomalies(source_event_id)`).
- Do: consume `raw.events`, validate schema version, dedup by `event_id` (Redis `SETNX` + TTL as first line, Postgres unique index as second).
- Accept: **dedup test** — publish identical event twice → exactly one row. **out-of-order test** — publish event with `event_time` 10s old → held in `OutOfOrderBuffer` (5s watermark) then emitted, or dropped if beyond window; never processed out of order.

**1.4 Anomaly rules + confirmation windows**
- Files: `AnomalyRule` SPI + `ErrorRateRule`, `P99Rule`, `FiveXXRule`, `InstanceDownRule`; `AnomalyDetector`, `MetricStore` (Redis rolling windows).
- Do: rule fires only when signal breaches threshold for **2 consecutive windows**; emits `AnomalyEvent` to `anomalies` topic with `window_start`, `value`, `threshold`.
- Accept: unit test each rule; integration test: 1 bad window → nothing; 2 bad windows → anomaly emitted exactly once.

### Phase 2 — Incident core + actions (1.5 weeks)

**2.1 Incident domain**
- Files: `Incident`, `IncidentStatus`, `IncidentTransition`, `Severity`, `IncidentService`, `IncidentRepository`.
- Do: aggregate with **validated transition table** (no illegal moves); `openIncident(anomaly)` correlates by `service` — if an open incident exists for the service, **merge** instead of double-open.
- Accept: unit tests for every allowed/forbidden transition; merge test (two anomalies same service → one incident).

**2.2 Incident events + REST API**
- Files: `KafkaPublisher`, `IncidentController` (`GET /incidents`, `GET /incidents/{id}`, `GET /incidents/{id}/events`).
- Do: publish `IncidentEvent` on every state change; REST for dashboard detail queries.
- Accept: state change → event on `incidents` topic; API returns timeline.

**2.3 Rule-based proposer (temporary intelligence)**
- Files: `RuleBasedProposer` (maps anomaly → `Proposal`: restart/scale/rollback/clear-cache), `Proposal`, `ProposalStatus`.
- Do: deterministic proposals so the full loop runs before the LLM exists. **This is the slot the agent will later fill.**
- Accept: error-rate anomaly → restart proposal; 5xx burst + recent deploy → rollback proposal; etc.

**2.4 Approval gate**
- Files: `ApprovalController` (`POST /approvals/{proposalId}/approve|reject`), TTL scheduler (Redis TTL + key expiration listener or Spring `@Scheduled` sweep), audit rows.
- Do: proposal with risk ≥ MEDIUM → `AWAITING_APPROVAL`, TTL 5 min; approve/reject persisted with actor + timestamp; **TTL expiry escalates, never auto-approves**.
- Accept: approve → proposal `APPROVED`; reject → `REJECTED` + incident note; expiry → `EXPIRED` + escalation flag. All three audited.

**2.5 Action executor + verification + rollback (inside incident-svc for now)**
- Files: `ActionExecutor` (Redis lock `lock:action:{id}` + dedup), `ActionVerifier` (poll metrics for 5 min), `RollbackEngine` (undo_params).
- Do: execute action against simulator (call simulator "heal" endpoint), record `undo_params` **before** executing, verify recovery, auto-rollback on verification failure, publish `ActionResultEvent`.
- Accept: **idempotency test** — replay the same `ActionCommandEvent` → one execution, second returns stored result. **rollback test** — make verification fail (simulator keeps failing) → automatic rollback action created + executed + incident reopened with escalation note.

### Phase 3 — Realtime + dashboard (1 week)

**3.1 WebSocket layer**
- Files: `WebSocketConfig` (STOMP + SockJS, `/ws` endpoint), `DashboardNotifier` (`/topic/incidents`, `/topic/incident/{id}`), `LiveIncidentCache` (Redis).
- Do: push incident state changes, proposal cards, action results to subscribers; REST stays for initial load + detail.
- Accept: two browser tabs both receive live updates with no refresh.

**3.2 React dashboard**
- Files: Vite scaffold, `stompClient`, `LiveFeed`, `IncidentDetail`, `AgentTrace`, `ProposalCard`, `StatusBadge`, `SeverityBadge`.
- Do: implement the mockup (`design/dashboard.html`) as real components: feed from WS, detail from REST, approval card with approve/reject → REST.
- Accept: E2E — simulator breaks service → incident appears live → proposal card → approve → action executes → status advances to VERIFYING → RESOLVED, all without refresh.

**3.3 Kill switches + post-mortem view**
- Files: `KillSwitchController` + Redis flags, `KillSwitchPanel.tsx`, `PostMortem.tsx`.
- Do: global + per-service kill switch respected by proposer and executor; post-mortem view reads actions + agent runs.
- Accept: kill switch ON → new proposals rejected with reason `KILL_SWITCH`; dashboard shows it live.

### Phase 4 — Agent layer (3–4 weeks) ★ the differentiator

**4.1 Extract action-svc**
- Files: move `ActionExecutor`/`ActionVerifier`/`RollbackEngine`/handlers into `action-svc`; consumer for `action.commands`; `SimulatedTargetClient`.
- Do: same behavior, now a separate deployable — the safety boundary becomes a network boundary.
- Accept: full E2E still passes with action-svc standalone.

**4.2 Tool SPI + registry**
- Files: `Tool` (name, description, JSON schema, execute), `ToolRegistry`, `ToolSchemaGenerator` (Jackson → JSON schema).
- Do: registry auto-collects Spring beans; schemas serialized for the LLM request.
- Accept: unit test — registry has exactly the declared tools; schemas validate sample inputs.

**4.3 Investigation tools**
- Files: `GetServiceHealthTool`, `GetMetricsTool`, `SearchLogsTool`, `GetDeploymentsTool`, `GetIncidentContextTool`.
- Do: read from incident-svc REST + Redis + metric/log stores. Truncate tool outputs (e.g., top 50 log lines) to control tokens.
- Accept: each tool returns correct data for a seeded scenario; output size caps enforced.

**4.4 Agent loop**
- Files: `LlmClient` (OpenAI-compatible, configurable base URL → works with OpenAI, Anthropic, Ollama), `AgentLoop`, `LoopBudget` (max 8 tool calls, 90s timeout, token cap).
- Do: loop = call model → parse `tool_calls` → execute via registry → append results → repeat; termination on `propose_action`, `request_human_help`, or budget exhaustion → forced escalate. Retry with backoff on transient LLM errors.
- Accept: with the **fake LLM** (scripted responses), loop behavior is fully deterministic in CI: correct tool order, correct termination.

**4.5 Propose-action + structured validation**
- Files: `ProposeActionTool`, `ProposalValidator` (JSON schema: action_type ∈ enum, target, params, `confidence ∈ [0,1]`, `risk_assessment {blast_radius, reversibility, affected_users}`, `reasoning`).
- Do: validator rejects malformed/low-confidence proposals *by policy*; validated proposal → `proposals` topic → incident-svc approval gate.
- Accept: unit tests — valid proposal passes; missing evidence field rejected; confidence 0.5 rejected at threshold.

**4.6 Policy stack**
- Files: `RiskPolicy` (config-driven matrix from `ARCHITECTURE.md` §5), `CooldownGuard` (Redis TTL), `ActionBudgetGuard` (max 2 auto actions / 10 min), `KillSwitchGuard`.
- Do: all guards evaluated before a proposal is accepted for routing; rejection reasons recorded and surfaced in dashboard.
- Accept: table-driven tests for every (action, risk, confidence, cooldown, budget) combination.

**4.7 Reasoning logging + timeline API**
- Files: `AgentRunRecorder` (agent_runs rows), `GET /incidents/{id}/agent-runs`, `AgentTrace` component.
- Do: persist every tool call (input, output, latency, tokens); dashboard renders evidence → decision timeline (matches mockup).
- Accept: seeded incident → dashboard shows the full trace live.

**4.8 Shadow mode + replay harness**
- Files: `ShadowModeGate` (proposals recorded, never routed to execution), `ReplayHarness` (replay recorded Kafka events with ground truth from simulator scenario catalog), `EvalReport` (precision / recall / FPR), `scripts/replay-eval.sh`.
- Do: run agent in shadow across the scenario catalog; produce numbers; tune thresholds until precision ≥ 85%.
- Accept: `make replay` prints a report; the number goes on your resume.

**4.9 Autonomy graduation**
- Files: `LOW-risk auto-approve` config (clear_cache) gated on shadow-mode track record; everything else stays human-approved.
- Do: only after 4.8 passes. Document the graduation criteria in README.
- Accept: live demo — LOW-risk action auto-executes; MEDIUM/HIGH always require approval; a verification failure triggers auto-rollback with the agent in the loop.

### Phase 5 — Observability (1 week)

- Files: Micrometer counters/timers in every service (`incidents_opened_total`, `incidents_resolved_total`, `actions_executed_total`, `actions_rolled_back_total`, `proposal_confidence_histogram`, `agent_loop_latency`, `llm_cost_per_incident`); `prometheus.yml`; Grafana provisioned dashboards (incident funnel, MTTR by severity, action success/rollback rate, Kafka consumer lag via kafka-exporter); Logback JSON → Loki.
- Accept: Grafana shows a live incident lifecycle end-to-end (metrics spike → incident → resolution) while the React dashboard shows the response to it — the side-by-side demo moment.

### Phase 6 — AWS + CI/CD (stretch, 1–2 weeks)

- Files: Terraform (ECS Fargate services, ALB with WSS, RDS Postgres, ElastiCache Redis, MSK or self-hosted Kafka, VPC/IAM least-privilege), GitHub Actions workflow (build + Testcontainers tests on PR, deploy on main).
- Accept: `demo.…` URL serves the same Compose-to-AWS parity app; cost estimate documented in README (~$150–300/mo ECS, ~$60/mo single-EC2 demo).

---

## 4. Cross-Cutting Concerns (bake in from day one)

- **Event contract first.** Every topic's payload is a versioned record in `contracts/`. Change = new schema_version, never mutation.
- **Idempotency everywhere.** Consumers assume at-least-once. Side effects are guarded by Redis locks + unique indexes + state machine validation.
- **Single write path.** No component except action-svc executes actions; no component except agent-svc (or the Phase-1 rule proposer behind the same interface) proposes them.
- **Time.** All event-time comparisons use `event_time` (producer clock), never consumer clock.
- **Config-driven policy.** Risk matrix, thresholds, TTLs, cooldowns, budgets in `application.yml` / env — not hard-coded — so the "tune until precision ≥ 85%" story is a config change.
- **Testing pyramid.** Unit (domain transitions, rules, policy) → integration (Testcontainers Kafka/Postgres/Redis) → E2E (compose + dashboard) → replay evaluation (agent quality). The fake LLM keeps agent tests deterministic in CI.

---

## 5. Definition of Done (whole project)

1. `make up` → one command starts the full stack; `make demo` runs a scripted scenario end-to-end.
2. Agent correctly diagnoses the 8–12 catalog scenarios (correlates metrics + logs, names root cause, picks the right action).
3. Risk-tiered approval works: LOW auto (post-shadow), MEDIUM/HIGH human-gated, TTL expiry escalates.
4. Verification failure triggers automatic rollback; idempotency holds under replayed/duplicated events.
5. Dashboard shows the live agent trace and the full incident lifecycle; Grafana shows the ops view.
6. Replay harness reports precision/recall/FPR; numbers are real and repeatable.
7. Post-mortem view renders agent reasoning + action history + lessons-learned for any incident.

---

## 6. Suggested Commit Sequence (also your interview narrative)

1. `feat: bootstrap gradle multi-module + compose (kafka, postgres, redis)`
2. `feat: contracts module — versioned event envelope`
3. `feat: simulator emits metrics/logs/health to kafka`
4. `feat: ingest-svc dedup + out-of-order buffer + anomaly rules`
5. `feat: incident domain with validated state machine + merge`
6. `feat: rule-based proposer + approval gate with TTL`
7. `feat: idempotent action executor with verification + rollback`
8. `feat: STOMP websocket + dashboard (feed, detail, approval)`
9. `refactor: extract action-svc — safety boundary at the network`
10. `feat: tool registry + investigation tools`
11. `feat: hand-rolled agent loop with budgets + fake llm for ci`
12. `feat: proposal validator + risk policy + cooldowns + kill switches`
13. `feat: reasoning logging + trace view`
14. `feat: shadow mode + replay evaluation harness`
15. `feat: prometheus/grafana/loki observability`
16. `chore: aws terraform + ci`