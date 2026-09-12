# Pre-ship audit

Date: 2026-09-12. Scope: the whole repository at the point where all seven
build phases were declared done. Method: read every production class and
config, run the full build and test suite, and trace each safety-critical path
(proposal gate, executor, verification, rollback, evaluation, observability)
against what it claims to do. Findings are ordered by severity, and every fix
below is in this tree with a test.

What could **not** be executed on this machine: Docker is not installed, so the
Compose stack, the container images, and the two Testcontainers end-to-end
tests were not run here. They compile and the tests skip cleanly. Verification
steps for a Docker host are at the end.

---

## 1. What is implemented

| Layer | State |
|---|---|
| `contracts` | Versioned event records; dependency-free on purpose |
| `simulator` | Metric/log/health emitters, error-spike and stubborn scenarios, `/api/services/{service}/logs`, action execution |
| `ingest-svc` | Normalize, Redis dedup, out-of-order buffer with watermark, error-rate rule with a 2-window confirmation, anomaly publish |
| `incident-svc` | Incident state machine, correlation/merge, approval gate with TTL, policy + kill switches, idempotent executor, verifier, rollback engine, shadow mode, replay harness, STOMP broadcast |
| Agent | Hand-rolled tool-calling loop, four read-only tools, scripted fake LLM for CI, OpenAI-compatible client, behind the `Proposer` interface |
| `dashboard` | React + Vite + TS, live STOMP/SockJS feed, approval card, agent trace, post-mortem, live controls, sample-data fallback |
| Observability | Micrometer counters in all three services, per-topic Kafka lag gauge, Prometheus + provisioned Grafana dashboard |
| Delivery | GitHub Actions (backend incl. Testcontainers + dashboard), four container images, ECS Fargate task definitions with a runbook |

Test suite: **91 tests, 0 failures**, of which 5 skip without Docker (the two
end-to-end lifecycle tests, the WebSocket end-to-end test, and two ingest
integration tests).

---

## 2. Defects found and fixed

### D1 — The default configuration could not start (high)

`ProposerConfig` declared `LlmClient` and `ToolCallingAgent` as unconditional
beans. `llmClient` throws when `agent.mode=agent` is not set and no API key is
present, so the **default** `mode=rule` configuration failed at context
startup. `gradle bootRun` without an exported key was broken, and both
Docker-gated `@SpringBootTest` end-to-end tests would have failed on CI (they
never override `agent.mode`).

Fixed by building the model client and the loop inside the `proposer` factory
instead of exposing them as beans: nothing else injects them, rule mode no
longer touches the agent code, and a missing key is reported only when agent
mode is actually requested.

### D2 — The ingest lag monitor was dead code (high)

`ingest-svc/.../metrics/KafkaLagMonitor.java` declared
`package io.aegis.incident.metrics`. Spring Boot scans the application's own
package tree (`io.aegis.ingest`), so the class was never registered: no
component, no metric, and a misleading file that looked like it worked.

Fixed: correct package (`io.aegis.ingest.metrics`), plus an explicit
`metrics.kafka-lag-topics: raw.events` so the ingest consumer group is measured
instead of the incident topics it does not consume.

### D3 — The lag gauge would have reported its first sample forever (high)

Both monitors called `MeterRegistry.gauge(name, tags, number)`. Micrometer
binds the value passed on the **first** call and ignores later ones, so the
gauge would have been frozen at whatever the first sample was (usually 0, since
the first poll often lands before the topic exists) while the dashboard showed
a healthy-looking flat line.

Fixed with a stable per-topic `AtomicLong` and a gauge that reads it via a
value function, so every scrape sees the current lag. While in there: one
`AdminClient` is created per service and closed on shutdown instead of one per
sample, and a failing topic no longer aborts sampling for the others.

### D4 — Evaluation stats conflated true positives with true negatives (high)

`ShadowEvaluator.stats()` did `long tp = correct`, where `correct` counted
correct `no_action` verdicts (true negatives) as well. Precision could exceed
1.0 and recall was overstated, on the number the README and the dashboard
present as evidence of agent quality.

Fixed in the query: true positives, false positives, false negatives, true
negatives and total are counted separately. Two tests pin the column order and
assert that correct `no_action` verdicts do not inflate either metric.

### D5 — An approved recovery command could be lost permanently (high)

The approval path takes a Redis claim (`SETNX lock:action:{commandId}`) in the
consumer. The claim was never released. So: execution throws (simulator or
database blip) → transaction rolls back → the consumer rethrows → Kafka
redelivers → this time (and on every redelivery) the claim is held, so the
consumer logged nothing, acknowledged, and moved on. The proposal stayed
`APPROVED` and never executed. A transient outage silently destroyed an
approved recovery action.

Compounding it: Spring Kafka's default error handler retries a handful of times
with **no** backoff and then commits the record, which is a silent drop.

Fixed in three parts:
1. `ActionLockStore.release`, called when execution throws, so the retry is not
   blocked until the lock TTL expires.
2. The consumer now distinguishes three cases: claim acquired → execute; claim
   held **and** an `actions` row exists → a real duplicate, acknowledge; claim
   held with no row → leave unacknowledged for redelivery.
3. `KafkaErrorHandlingConfig` in both consumer services: fixed 2s backoff,
   unlimited attempts, so nothing is skipped. Poison payloads are still
   acknowledged in the consumers themselves, so the handler only sees transient
   dependency failures.

Six tests cover the branches, including the failure-and-release path.

### D6 — One unreachable target blocked every other verification (medium)

`ActionVerifier.verifyPending` polls each `VERIFYING` action in one transaction
and one loop. A single `simulator.status` failure aborted the whole pass, so
other in-flight incidents waited for the next poll while the failure repeated.

Fixed with a per-action guard that logs and continues, retrying next cycle. It
rethrows only when the transaction is already marked rollback-only, so a
database-level failure is not swallowed into a confusing commit error. Tested
with a two-action batch where the first target is unreachable.

### D7 — `@Transactional` on a private method (medium)

`RollbackEngine.rollback` is private and called from `failAndRollback` in the
same bean. Spring proxies cannot intercept self-invocations, so the annotation
did nothing; it was correct only because the caller is already transactional.
Removed, with a comment stating where the transaction actually comes from.

### D8 — Unused parameter (low)

`IncidentBroadcaster.incident(Incident, IncidentStateEvent)` never used the
`Incident`. Removed, along with the now-unneeded import; the publisher and the
unit test were updated.

### D9 — The dashboard image could not have started (medium)

`deploy/nginx.conf` was copied to `conf.d` as-is and contained
`server ${INCIDENT_SVC:-incident-svc:8082};`. nginx does not perform shell
expansion, and `${VAR:-default}` is not even valid envsubst syntax, so the
config was invalid and the container would have crash-looped. The Dockerfile
never ran envsubst either.

Fixed: the file is copied to `/etc/nginx/templates/default.conf.template`, the
nginx entrypoint performs the substitution at start, exactly one variable is
templated (`AEGIS_API_KEY`, which the proxy injects on `/api` calls), and the
upstream is a static `incident-svc:8082`.

### D10 — Repository hygiene (low)

Tracked build output and junk: 146 `.class` files under `*/bin/`, a committed
`dashboard/tsconfig.tsbuildinfo`, and two stray UTF-16 files at the root
(`incident-process.txt`, `proposal-search.txt`). All untracked; `bin/` and
`*.tsbuildinfo` added to `.gitignore`; the two junk files deleted.

### D11 — Stale documentation (low)

`README.md` linked `docs/ARCHITECTURE.md`, which does not exist (the file is
`ARCHITECTURE.md`), and advertised `make demo` / `make replay` targets that no
Makefile defines. The runbook documented `./scripts/e2e-verify.sh --help`,
which the script does not implement. All corrected; the doc index now lists
this audit and the runbook.

### D12 — Configuration file defects (low)

`.env.example` began with a `[TEMPLATE]` line, which is not a dotenv
construct. The local `.env` (gitignored, not edited here) contains duplicate
`AGENT_MODE` and `AGENT_FAKE_LLM` lines; harmless because the last value wins
and both pairs are identical, but worth removing by hand.

---

## 3. Security posture

### Added

- **Control-plane API key.** `X-API-Key` is required on every `/api` request
  of incident-svc and the simulator: approvals, kill switches, replay, fault
  injection, and action execution were all previously open to anyone who could
  reach the port. Comparison is constant-time; failures return 401 with a
  `WWW-Authenticate` header. An empty `AEGIS_API_KEY` disables the filter and
  logs a startup warning, which is the local-development and test path.
  `SimulatorClient` sends the same key on its outbound calls.
- **The key never reaches the browser.** The nginx image and the Vite dev proxy
  inject the header on the requests they forward, so the dashboard bundle holds
  no secret.
- **Grafana anonymous access was `Admin`**, which let anyone who could reach
  :3000 change data sources and dashboards. Now `Viewer`.
- **Containers ran as root.** The three Java images now create and switch to a
  non-root user.
- **AWS artifacts had secrets in plain environment variables.** The database
  password and the API keys are now `secrets` entries resolved from Secrets
  Manager at task start, and the runbook explains sharing one API key across
  incident-svc, simulator, and dashboard.

### Residual risk (deliberately not "fixed" here)

| # | Risk | Why it stands | Remediation |
|---|---|---|---|
| R1 | `/ws/**` is unauthenticated, so the live incident feed is readable by anyone who can reach the port | The browser cannot set headers on a SockJS handshake; forcing a query-parameter token would put the secret in the page. Proxies do inject headers, so this is a deployment choice, not a code gap | Keep the WS port private (same network as the dashboard), or add a STOMP `CONNECT` interceptor, or terminate auth at the ALB and stop publishing the port |
| R2 | Kafka is PLAINTEXT, Postgres and Redis use default credentials | Correct for a local Compose demo, wrong for anything reachable | Managed services with TLS + SASL/ACLs; credentials from Secrets Manager; Redis AUTH |
| R3 | One shared API key, and `approver` is self-declared, so "who approved this" is not attested | Out of scope for a demo identity model | OIDC/JWT with the approver taken from the token subject, and a two-person rule for HIGH-risk actions (already specified in `ARCHITECTURE.md`) |
| R4 | No rate limiting on the control plane | Low value with a single operator | ALB WAF rate rules |
| R5 | `/actuator/health` and `/actuator/prometheus` are open | Health checks and the scrape job must not need a credential, and neither exposes control | Keep the port on a private network; do not publish 8080-8082 publicly |
| R6 | Log text from the simulator is semi-trusted input to the agent | Prompt injection could steer a proposal; it cannot execute anything (no write tool) and objective policy gates still apply | Keep the human gate on, and treat autonomy as opt-in only after shadow-mode evidence |

---

## 4. Verification performed

```bash
./gradlew build          # 91 tests, 0 failures (5 skipped without Docker)
cd dashboard && npm run build
node -e "require('./observability/grafana/provisioning/dashboards/aegis.json')"   # dashboard JSON valid
```

Not run here (no Docker on this machine):

```bash
make reset
./gradlew :incident-svc:test --tests '*IncidentLifecycleE2ETest*'  # automated full loop
./scripts/e2e-verify.sh                                            # full stack, happy path
./scripts/e2e-verify.sh --stubborn                                 # rollback path
docker build -f deploy/Dockerfile.incident .                       # and the other three
```

## 5. Recommended next steps

1. Run the two commands above on a Docker host and paste the topic dumps into
   the README; that is the last unverified claim in the project.
2. Decide R1 (WebSocket auth) before hosting a public demo URL.
3. Add OIDC for approver identity if the demo is shown to anyone but you.
4. Load-test the ingest path (the buffer, dedup, and rule state are all
   per-service; the pipeline is the only part without a stress figure).
5. Keep the two-person rule and cooldown/budget items from
   `ARCHITECTURE.md` §5 as the next increment of autonomy policy.
