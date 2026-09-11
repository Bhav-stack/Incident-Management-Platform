# Autonomous Incident Management & Recovery — Architecture

An AI-powered incident management platform. The system ingests simulated failures
(logs, metrics, health events) via Kafka, correlates them into incidents, runs a
**tool-calling LLM agent** that investigates root cause and proposes recovery
actions, gates high-risk actions behind **human approval**, executes actions
**idempotently with automatic rollback on failure**, and streams the whole thing
live to a React dashboard.

> **Core philosophy: the AI agent is a decision component inside a deterministic
> recovery pipeline — not the pipeline itself.**
> Every guardrail (approval gates, idempotency, verification, rollback, kill
> switches) is built and tested *before* the LLM is introduced. The agent plugs
> into a slot that a rule-based engine already occupies. This is the single most
> important design decision, and the one to lead with in an interview.

---

## 1. System Overview

```
┌────────────────────────────────────────────────────────────────────────────┐
│                              DOCKER COMPOSE / AWS                          │
│                                                                            │
│  ┌────────────┐   ┌──────────────┐   ┌──────────────┐                      │
│  │  Simulator │──▶│  ingest-svc  │──▶│ incident-svc │                      │
│  │ (chaos gen)│   │  normalize   │   │ state machine│                      │
│  └────────────┘   │  dedup       │   │ correlation  │                      │
│       │           │  anomaly     │   └──────┬───────┘                      │
│       │           │  detection   │          │  investigation request       │
│       ▼           └──────────────┘          ▼                              │
│  ┌──────────────────────────────────────────────────────────────┐          │
│  │                          KAFKA                               │          │
│  │  topics: raw.events · anomalies · incidents · proposals ·    │          │
│  │          action.commands · action.results · audit            │          │
│  └───────┬──────────────────────────────────────┬───────────────┘          │
│          │                                      │                          │
│          ▼                                      ▼                          │
│  ┌──────────────┐                      ┌──────────────┐                    │
│  │  agent-svc   │                      │ action-svc   │                    │
│  │ tool-calling │                      │ executor     │                    │
│  │ LLM loop     │                      │ verifier     │                    │
│  │ reasoning log│                      │ rollback     │                    │
│  └──────────────┘                      └──────┬───────┘                    │
│                                               │                            │
│  ┌──────────────┐   ┌──────────────┐  ┌───────▼───────┐                    │
│  │  PostgreSQL  │   │    Redis     │  │ WebSocket hub │                    │
│  │ incidents,   │   │ live state,  │  │ (STOMP)       │                    │
│  │ proposals,   │   │ locks, dedup,│  └───────┬───────┘                    │
│  │ agent runs,  │   │ cooldowns    │          │                            │
│  │ audit trail  │   └──────────────┘          ▼                            │
│  └──────────────┘                     ┌──────────────┐                    │
│                                       │ React        │                    │
│                                       │ dashboard    │                    │
│                                       │ approve/     │                    │
│                                       │ reject       │                    │
│                                       └──────────────┘                    │
└────────────────────────────────────────────────────────────────────────────┘
```

### Data flow (happy path)

1. **Simulator** emits metric spikes, error logs, and health events into Kafka,
   keyed by `service:instance` so per-key ordering holds.
2. **ingest-svc** normalizes events, dedupes by `event_id`, and evaluates
   anomaly rules (error rate > 5%, p99 > 500 ms, 5xx spike, instance down).
   An anomaly that persists for **2 consecutive windows** opens an **incident**.
3. **incident-svc** owns the incident state machine and persistence. It pushes
   state changes to Kafka + Redis and notifies the dashboard over WebSocket.
4. **agent-svc** receives an investigation brief and runs the tool-calling loop:
   it calls `get_metrics`, `get_logs`, `get_deployments`, etc., accumulates
   evidence, and finishes with a structured `propose_action` call.
5. The proposal is **policy-checked** (risk tier, confidence threshold,
   cooldowns, kill switches). High-risk actions enter **AWAITING_APPROVAL**;
   the dashboard shows an approve/reject card via WebSocket.
6. On approval, **action-svc** executes idempotently (Redis lock + dedup),
   records `undo_params` *before* executing, then **verifies** recovery by
   watching metrics for N minutes. If verification fails → **auto-rollback**.
7. Every step — every tool call, LLM response, approval, execution — is written
   to Postgres (`agent_runs`, `audit_log`) for post-incident review.

---

## 2. Services (Spring Boot)

| Service | Responsibility | Notes |
|---|---|---|
| `simulator` | Chaos generator: scripted & random failure scenarios | Also exposes an HTTP "break it" endpoint for demos |
| `ingest-svc` | Kafka consumer → normalize → dedup → anomaly rules → incident events | Stateless, horizontally scalable consumers |
| `incident-svc` | Incident state machine, correlation, persistence, WebSocket fan-out | The "brain"; owns the domain model |
| `agent-svc` | Tool-calling LLM loop, tool registry, proposal generation, reasoning logs | The "intelligence"; fully replaceable |
| `action-svc` | Action executor, verifier, rollback engine | The "hands"; enforces all safety invariants |
| `dashboard` (React) | Live incident feed, investigation timeline, approve/reject, post-mortem view | Vite + React + STOMP over WebSocket |

**Microservices vs. modular monolith:** start with 3 deployables — `simulator`,
`ingest-svc`, and one `core-svc` that contains incident state machine + action
executor + WebSocket. Split `agent-svc` and `action-svc` out in Phase 2 as the
agentic layer lands. The seams are the Kafka topics, so the split is cheap and
demonstrates that you understand service boundaries rather than cargo-culting
microservices.

---

## 3. Storage & Messaging

### PostgreSQL (source of truth — history)

- `incidents(id, service, severity, status, opened_at, resolved_at, summary, root_cause, resolution)`
- `incident_events(id, incident_id, type, payload jsonb, ts)` — full timeline
- `anomalies(id, source_event_id, service, signal_type, window_start, value, threshold)`
- `proposals(id, incident_id, action_type, params jsonb, risk_level, confidence, reasoning jsonb, status)`
- `approvals(id, proposal_id, approver, decision, decided_at, ttl_seconds)`
- `actions(id, proposal_id, action_type, params jsonb, undo_params jsonb, status, executed_at, verified_at, rollback_action_id)`
- `agent_runs(id, incident_id, step_index, tool_name, tool_input jsonb, tool_output jsonb, model, latency_ms, tokens)`
- `audit_log(id, ts, actor, event, detail jsonb)` — append-only

Key indexes: `incidents(status, opened_at)`, `agent_runs(incident_id, step_index)`,
unique constraint on `anomalies(source_event_id)` for dedup.

### Redis (real-time state — ephemeral)

- `dedup:{source}:{event_id}` — `SETNX` + TTL (dedup window)
- `incident:{id}:active` — active incident cache with TTL
- `lock:action:{action_id}` — distributed lock for idempotent execution
- `cooldown:action:{service}` — per-service action cooldown TTL
- `killswitch:global` / `killswitch:{service}` — circuit breaker flags
- `active_incidents` — sorted set for the dashboard feed
- `last_seen:{service}:{instance}` — instance health heartbeat

### Kafka topology

| Topic | Key | Producer | Consumers |
|---|---|---|---|
| `raw.events` | `service:instance` | simulator | ingest-svc |
| `anomalies` | `service` | ingest-svc | incident-svc |
| `incidents` | `incident_id` | incident-svc | dashboard, agent-svc |
| `proposals` | `incident_id` | agent-svc | incident-svc, action-svc |
| `action.commands` | `incident_id` | action-svc | action-svc (own executor) |
| `action.results` | `incident_id` | action-svc | incident-svc, dashboard |
| `audit` | `incident_id` | all | audit consumer → Postgres |

Every event carries a versioned envelope:
`{schema_version, event_id (uuid), event_time (producer clock), ingest_time, source, payload}`.
Producers use `enable.idempotence=true`; the consumer group implements
**at-least-once + idempotent side effects** (see §7).

---

## 4. The Agent Layer (the centerpiece)

### 4.1 Framework decision: hand-roll the loop

Use the raw OpenAI/Anthropic-compatible chat API with native `tool_calls`
support, and implement the agent loop yourself (~200 lines):

- `Tool` interface (Spring bean) → declarative tool registry with JSON schemas
- `AgentLoop` — while loop: call model → parse `tool_calls` → execute → append
  results → repeat, with max-iteration and token budgets
- `StructuredOutputValidator` — the final proposal must validate against a
  strict JSON schema; malformed or low-confidence proposals are rejected by
  *policy*, never executed

**Why not Spring AI / LangChain4j?** They abstract exactly the part you want to
show you understand. Hand-rolling the loop — tool schemas, the `tool_calls`
round-trip, loop termination, retries, budget enforcement, structured output —
is the differentiator on a 2026 resume. It's also trivially swappable: any
OpenAI-compatible endpoint (OpenAI, Anthropic, or local Ollama for dev) works
behind one interface. Keep a **fake LLM** (scripted tool-call sequences) for CI
tests — deterministic agent tests without network calls.

### 4.2 Tool registry

Investigation tools (read-only):

| Tool | Purpose |
|---|---|
| `get_service_health(service)` | Instance status from Redis heartbeats |
| `get_metrics(service, metric, window)` | Time series from the metrics store |
| `search_logs(service, query, window)` | Structured log search |
| `get_deployments(service)` | Recent deploys & versions (root cause: "bad deploy") |
| `get_incident_context(incident_id)` | Symptoms, current status, prior actions |

Decision tools (the only write path):

| Tool | Purpose |
|---|---|
| `propose_action(action_type, target, params, confidence, risk_assessment, reasoning)` | The agent's *only* output |
| `request_human_help(message)` | Explicit escalation with a question |

**The agent can never execute anything.** There is no `execute_action` tool.
Proposals cross the Kafka boundary to `action-svc`, which is the only component
with execution authority. This single-write-path rule is the backbone of safety.

### 4.3 How the agent decides which tool to call

1. **System prompt contains an investigation protocol**, e.g.: *"Start with
   service health, then correlate metrics and logs. Check for a recent deploy.
   Only propose an action when you have evidence from ≥ 2 independent signals.
   Never guess a root cause from a single metric."*
2. **Tool selection is schema-driven**: each tool's JSON schema (description,
   parameters, examples) is sent with every request; the model picks the tool
   whose description matches the evidence gap it needs to fill.
3. **The loop makes it agentic**: the model sees *actual tool results*, so the
   next tool call depends on what was found (logs point to a DB error → it calls
   `get_metrics` on the DB pool). This is correlation in the loop, not a single
   prompt-response.
4. **Termination conditions** (checked in order):
   - Model emits a valid `propose_action` → loop ends
   - Model calls `request_human_help` → escalate
   - Max iterations (e.g., 8) or token budget hit → force escalate
5. **Structured proposal** forces the model through the decision fields
   interviewers love: `confidence ∈ [0,1]`, `risk_assessment {blast_radius,
   reversibility, affected_users}`, `reasoning` (evidence summary).

### 4.4 Reasoning logging (post-incident review)

Every iteration is persisted to `agent_runs`: tool chosen, input, raw output,
model, latency, token count. The dashboard renders this as an **evidence →
decision timeline**: "saw error-rate spike → pulled logs → found connection pool
exhaustion → checked deploys → proposed restart with confidence 0.82". This is
your post-incident review screen and your proof of agentic behavior in demos.

---

## 5. Reducing False-Positive Recovery Actions

This is the question that separates "LLM wrapper" from "agentic engineer." The
answer is a **layered policy stack**, most of it *outside* the model:

1. **Confirmation windows (pre-incident).** An anomaly only opens an incident
   after persisting across 2 consecutive windows (e.g., 30 s each). Transient
   spikes never reach the agent.
2. **Multi-signal correlation (agent policy).** The proposal validator rejects
   any proposal whose evidence list has < 2 independent signal types (e.g.,
   metrics alone is insufficient; metrics + logs or metrics + deploy info
   required).
3. **Confidence threshold.** Proposals below `confidence < 0.7` are never
   auto-approved; they are routed to a human as "recommendation with
   uncertainty." Threshold is a config value, tuned per risk tier.
4. **Risk-tiered policy.**

   | Action | Risk | Default gate |
   |---|---|---|
   | clear cache | LOW | auto-approve (only after shadow-mode validation) |
   | restart service instance | MEDIUM | human approval |
   | scale replicas | MEDIUM | human approval |
   | rollback deployment | HIGH | human approval, 2-person for prod |
   | kill / stop instance | HIGH | human approval |

   Start with **everything requiring approval**; relax tiers only after the
   evaluation harness shows you a verified track record.
5. **Shadow mode.** Before any autonomy, run the agent for N incidents in
   shadow mode: it proposes, nothing executes, humans do the real recovery.
   Record proposal accuracy against what actually fixed it. This is a demo-able
   feature and a strong interview story.
6. **Replay/evaluation harness.** Re-run historical Kafka event streams through
   the agent and score its proposals against ground truth (the simulator knows
   what it broke). Report **precision / recall / false-positive rate** per
   scenario. This gives you a number to put on the resume ("92% proposal
   precision across 12 failure scenarios").
7. **Cooldown & hysteresis.** After any action on a service, no new action for
   X minutes unless the incident severity *increases*. Prevents flapping and
   agent action-loops.
8. **Action budget.** Max 2 auto actions per incident per 10 min; cap total
   actions per incident. The agent's proposal is rejected if the budget is
   exhausted.

---

## 6. Safety: Approval Gates, Idempotency, Rollback

### 6.1 Incident state machine (enforced, not aspirational)

```
OPEN → INVESTIGATING → PROPOSAL → AWAITING_APPROVAL → EXECUTING → VERIFYING → RESOLVED
                                  │                        │
                                  ▼                        ▼
                            REJECTED / EXPIRED         FAILED → ROLLING_BACK → RESOLVED(rolled back)
```

Transitions are validated in `incident-svc` — a duplicate `EXECUTING` event for
the same incident is ignored, not applied. State changes go to Kafka + Redis +
WebSocket.

### 6.2 Approval gate

- Proposal with risk ≥ MEDIUM → `AWAITING_APPROVAL` with a **TTL (e.g., 5 min)**.
- Dashboard shows an approve/reject card (WebSocket push). The decision is
  recorded with approver identity + timestamp in Postgres.
- **TTL expiry escalates to a human via the dashboard; it never auto-approves.**
- The agent cannot see or influence the approval decision (approval is handled
  entirely outside `agent-svc`).

### 6.3 Idempotent execution

- Every action has `action_id` (UUID). Before executing, `action-svc` takes
  `SET lock:action:{id} NX` and checks the dedup key; duplicate commands are
  no-ops that return the original result.
- Consumer groups run **at-least-once**, so retries are expected and harmless.

### 6.4 Verification + rollback of the recovery action itself

This is the "self-healing system" story:

1. **Capture `undo_params` before executing.** Each action type declares its
   inverse: `scale_up(2)` → `scale_down(2)`; `rollback(v2→v1)` →
   `deploy(v2)`; `restart` → none (verification is the undo); `clear_cache` →
   cache warm/rebuild.
2. **Execute.** Record `executed_at`, push `action.results`.
3. **Verify.** Watch the triggering metrics for N minutes (config per action).
   Define success: metric back under threshold *and* stable.
4. **On verification failure** → automatic rollback using `undo_params`, then
   incident reopens with escalation note: "auto-recovery failed, rolled back,
   needs human." The rollback action is itself a first-class `actions` row with
   its own idempotency and audit trail.

### 6.5 Kill switch & guardrails

- `killswitch:global` and `killswitch:{service}` flags in Redis, toggled from
  the dashboard. When set: agent proposals are rejected with reason
  `KILL_SWITCH`; no actions execute.
- Agent loop budgets: max 8 tool calls / investigation, max tokens, hard
  timeout (e.g., 90 s). Exceeded → force escalate to human.
- **Prompt-injection awareness:** tool outputs (especially log text, which is
  semi-untrusted) are treated as *data, not instructions* — the system prompt
  says so explicitly, and more importantly the policy validator rejects
  proposals on objective grounds (risk tier, confidence, evidence count)
  regardless of what the model "believed."

---

## 7. Kafka: Duplicates & Out-of-Order Events

Explicitly design for **at-least-once delivery + idempotent consumers** — it's
the honest answer for "what happens with duplicates?":

| Problem | Mechanism |
|---|---|
| Duplicate events | `event_id` dedup at ingest: Redis `SETNX dedup:{source}:{event_id}` + Postgres unique index on `anomalies(source_event_id)` |
| Duplicate commands | `lock:action:{action_id}` + dedup key; consumers return the stored result on replay |
| Out-of-order events | Events keyed by `service:instance` → partition ordering per key preserved; plus an **event-time buffer**: ingest holds events ~5 s and emits by `event_time` with a watermark, dropping stragglers older than the window |
| Two events for the same incident | Incident merge: correlation key = `service` + signal window; same-key anomalies update the existing open incident instead of opening a new one |
| Duplicate state transitions | State machine guards in `incident-svc`; transitions are validated against current status |
| Producer duplicates | `enable.idempotence=true` (Kafka-side dedup) |

Mention "exactly-once" only as the thing you *deliberately didn't chase*:
EOS/Kafka Streams exactly-once is available but at-least-once + idempotent side
effects is simpler, more testable, and correct for this workload. That's a
sophisticated answer interviewers like.

---

## 8. Frontend (React)

- **Vite + React + TypeScript**, STOMP-over-WebSocket client (Spring's STOMP
  support is the standard pairing; SockJS fallback for dev).
- Views:
  1. **Live feed** — active incidents, severity, status, age (Redis-backed).
  2. **Incident detail** — evidence timeline, agent reasoning steps (from
     `agent_runs`), current status.
  3. **Approval card** — proposed action, risk tier, confidence, reasoning,
     approve/reject buttons, TTL countdown.
  4. **Post-mortem view** — resolution, root cause, action + rollback history,
     verification outcome.
  5. **Kill switch** — global and per-service toggles (also a great demo prop).
- Keep state in the WebSocket feed + a light query layer (REST) for detail
  pages; don't over-engineer with state management libraries.

---

## 9. Observability

| Layer | Tooling | What you measure |
|---|---|---|
| Metrics | Micrometer → Prometheus → Grafana | Incident funnel (opened→resolved), MTTR by severity, action success/rollback rate, false-positive rate, agent loop latency, tool-call counts, LLM tokens & cost per incident, Kafka consumer lag |
| Logs | Structured JSON → Loki (or OpenSearch) | Service logs; the simulator's logs feed Kafka too, closing the loop: logs are both *observed* and *ingested* |
| Traces (stretch) | Micrometer Tracing + Tempo | End-to-end latency across services |
| Dashboards | Grafana | The "ops" view for the demo; separate from the product dashboard |

Simulators should emit Prometheus-format metrics too, so Grafana shows the
*failure itself* while the React dashboard shows the *response to it* — a
compelling side-by-side in a demo.

---

## 10. Deployment

### Local: Docker Compose

`kafka (KRaft, no ZooKeeper) · postgres · redis · prometheus · grafana ·
loki · 3–5 Spring services · dashboard`. One `docker compose up` brings up the
demo; a `scenarios/` folder contains scripted failure scenarios (latency spike,
connection-pool exhaustion, bad deploy, cache stampede, instance down, 5xx
burst, slow DB query, disk fill).

### AWS (hosted demo, stretch)

- **Option A (recommended for a resume demo):** ECS Fargate services behind an
  ALB, MSK or self-hosted Kafka (or start with a single EC2 running Compose +
  ngrok for the demo), RDS Postgres, ElastiCache Redis.
- **Option B:** EKS with Karpenter — more to explain, better if you want to
  talk Kubernetes.
- Include: IAM least-privilege notes, VPC/subnets, TLS for WebSockets (WSS via
  ALB), cost estimate (~$150–300/mo on ECS; ~$60/mo on a single EC2 + managed
  Kafka-free setup).
- Terraform or CDK for infra-as-code (pick one, either is fine to discuss).

---

> Implementation order, repo layout, and granular steps with acceptance criteria: see
> [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md). UI design mockup:
> [`design/dashboard.html`](design/dashboard.html).

## 11. Phased Build Plan

### Phase 1 — Deterministic MVP (no LLM) — ~2–3 weeks

> Goal: the *entire* loop works end-to-end with rule-based intelligence.
> **This phase is non-negotiable before any AI.** You'll debug Kafka ordering,
> state machines, and idempotency against deterministic code, not model
> nondeterminism.

- [ ] Docker Compose: Kafka (KRaft), PostgreSQL, Redis
- [ ] `simulator` emitting metrics/logs/health events
- [ ] `ingest-svc`: normalize, dedup, anomaly rules with confirmation windows
- [ ] `incident-svc` (+ WS): state machine, incident persistence, WebSocket feed
- [ ] Rule-based proposals (e.g., "error rate high → propose restart")
- [ ] Approval flow: dashboard approve/reject, TTL, expiry → escalate
- [ ] `action-svc`: idempotent executor, verification, rollback
- [ ] React dashboard: live feed + incident detail + approval card
- [ ] **Done when:** simulator breaks a service → incident opens → dashboard
      shows proposal → human approves → action executes → verified → resolved.
      Kill the Kafka consumer mid-action and confirm no duplicate execution.

### Phase 2 — Agentic layer — ~3–4 weeks

> Goal: replace the rule-based proposal engine with the tool-calling agent,
> *behind the same interfaces*, then prove it's safe.

- [ ] Tool registry + agent loop (hand-rolled tool calling, budgeted)
- [ ] Investigation tools wired to real data (metrics, logs, deploys, health)
- [ ] `propose_action` + structured-output validation + reasoning logging
- [ ] Fake LLM for CI; integration tests with Testcontainers
- [ ] Risk-tiered approval policy + confidence thresholds + cooldowns + budgets
- [ ] Shadow mode + replay/evaluation harness (precision/recall numbers)
- [ ] Kill switches (global + per-service) in dashboard
- [ ] **Done when:** agent correctly diagnoses the 8 scripted scenarios
      (correlates logs+metrics, names the right root cause), proposals gate
      correctly per risk tier, shadow-mode precision ≥ 85%, and a
      verification-failure triggers an automatic rollback.

### Phase 3 — Observability & hardening — ~2 weeks

- [ ] Prometheus + Grafana dashboards (incident funnel, MTTR, action
      success/rollback, agent metrics, Kafka lag)
- [ ] Structured JSON logging → Loki; audit topic → Postgres
- [ ] Out-of-order/duplicate stress tests (replay, reorder, duplicate events)
- [ ] Chaos scenario catalog (8–12 failure modes) + demo script
- [ ] Post-incident review view in dashboard (agent reasoning + decisions)
- [ ] Load test the pipeline (100+ events/s) and record numbers
- [ ] **Done when:** you can run a live demo where a random scenario triggers,
      the agent investigates visibly, a human approves, recovery verifies, and
      Grafana shows the whole incident lifecycle.

### Phase 4 — AWS demo (optional stretch)

- [ ] ECS Fargate (or EKS) + ALB/WSS + RDS + ElastiCache + Kafka
- [ ] Terraform/CDK, IAM least privilege, cost estimate
- [ ] CI/CD (GitHub Actions: build, Testcontainers tests, deploy)
- [ ] **Done when:** `demo.freebuff.io`-style URL works with the same
      Compose-to-AWS parity.

---

## 12. Interview Cheat Sheet

| "How does the agent decide what tool to call?" | Schema-driven tool selection + investigation protocol + evidence loop. The model picks tools whose descriptions match its evidence gaps; the framework executes, feeds results back, and iterates until it emits a structured `propose_action` or hits budgets. |
|---|---|
| "How do you prevent false-positive recovery actions?" | Confirmation windows, multi-signal evidence requirement, confidence thresholds, risk-tiered gates, shadow mode + replay harness measuring precision/recall, cooldowns, action budgets. |
| "How is this safe / not just an LLM wrapper?" | The agent has *no* execution tool — one write path through `action-svc`; risk-tiered approval gates with TTL escalation; idempotent actions; verify-then-rollback; kill switches; full audit trail; prompt-injection treated as data. |
| "What about duplicate / out-of-order Kafka events?" | At-least-once + idempotent consumers; event_id dedup (Redis + Postgres unique index); keyed partitions preserve per-key order; event-time watermarking buffer; state-machine guards reject duplicate transitions; producers use idempotence. |
| "Why microservices?" | Independent scaling of ingest vs. agent vs. action; failure isolation; the agent/action split enforces the safety boundary at the network level. Honest answer: started modular, split on real seams. |
| "What did you learn?" | The AI is the *easy* part to demo and the *hard* part to make safe — most engineering was in the pipeline around the model: state machines, idempotency, policy, verification, evaluation. |

---

## 13. Key Decisions & Alternatives (with defaults)

| Decision | Default | Alternative |
|---|---|---|
| Agent loop | Hand-rolled tool calling on OpenAI-compatible API | Spring AI / LangChain4j (faster, less differentiated) |
| LLM for dev | Ollama (free, local) | OpenAI/Anthropic APIs |
| Messaging | Kafka (KRaft) | Redpanda (drop-in, lighter) |
| Real-time | STOMP over WebSocket | Native WebSocket, SSE (simpler, one-way) |
| Log storage | Loki + Kafka | OpenSearch/ELK (heavier, more "enterprise") |
| Metrics | Micrometer + Prometheus + Grafana | — |
| Tests | Testcontainers (Kafka/Postgres/Redis) + fake LLM | — |
| Infra | Docker Compose → ECS Fargate | EKS |
| Language | Java 21 + Spring Boot 3.x, Gradle or Maven | — |

---

## 14. Repository Layout (target)

```
incident-platform/
├── simulator/            # chaos generator (Spring Boot or plain Java)
├── ingest-svc/
├── incident-svc/         # state machine, correlation, WebSocket hub
├── agent-svc/            # tool-calling loop, tools, policy
├── action-svc/           # executor, verifier, rollback
├── dashboard/            # React + Vite + TS
├── observability/        # prometheus.yml, grafana dashboards, loki config
├── scenarios/            # scripted failure scenarios + demo script
├── infra/                # docker-compose.yml, terraform/ (aws)
└── docs/
```

Start with `simulator`, `incident-svc` (with action executor + WS inside),
`dashboard`, and `infra/docker-compose.yml`. Everything else is carved out of
those as the seams appear.