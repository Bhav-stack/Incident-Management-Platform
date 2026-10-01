# Ship-readiness report

Date: 2026-09-30. Scope: the whole repository, at the point where the identity
work, the UI pass, the load measurement and the Docker-gated end-to-end flows are
all in the tree. This supersedes the earlier revision of this document (identity
was still a shared key then) and the *Pre-ship audit* (`docs/AUDIT.md`), which was
written when Docker was unavailable and 5 tests had to skip.

## Verdict

The platform is **demo-ready and internally consistent**: it builds, its full
test suite passes against real Kafka/Postgres containers, the happy and stubborn
recovery flows are captured end to end, the dashboard shows live incident data
over STOMP, and a two-person approval can be completed entirely from the UI with
two signed operator tokens.

It is **not** ready to expose on the public internet, but the largest remaining
risk is no longer identity — it is transport. Kafka is PLAINTEXT, Postgres and
Redis use default credentials, and the Compose files are a local topology. See
§4.

## 1. Evidence

Everything below was executed, not asserted.

| Check | Command | Result |
|---|---|---|
| Full test suite | `./gradlew cleanTest test` | **148 tests, 0 failures, 0 errors, 0 skipped** (28 classes), real Kafka/Postgres/Redis containers via Testcontainers |
| Dashboard build | `cd dashboard && npm run build` | green (`tsc -b` clean, 112 modules, 336 kB / 103 kB gzip) |
| Live stack | `docker compose up -d` + three `bootRun`s | Kafka/Postgres/Redis healthy; simulator :8080, ingest :8081, incident :8082 all `/actuator/health` 200 |
| Live pipeline | `POST /api/scenarios/error-spike` | fresh incident opened (`AWAITING_APPROVAL`) and rendered live in the dashboard over `ws /topic/incidents` |
| Operator token | `POST /api/auth/session` with `X-Operator-Key` | returns a 300s HS256 token whose subject is the operator; the shared client key is refused with 403 |
| Two-person approval, from the UI | dashboard: sign in, approve, sign out, sign in as the second operator, approve | first signature recorded (`1 of 2`), second released the command, proposal `EXECUTED`, incident `RESOLVED` |
| Ingest throughput | `./scripts/loadtest.sh --rates 200,1000,2000,5000,7000,10000` | see §3; raw output in `docs/loadtest-ingest.txt` |
| Kafka topic dumps | `./scripts/e2e-verify.sh --capture docs/e2e-dumps` | 8 files, 21 lines, tracked (`happy-*`, `stubborn-*`) |

The 5 tests that skipped in the original audit now run, so the only claim that
audit listed as unverified — the Docker-gated end-to-end loop and the topic
dumps — is verified.

## 2. What is done

- **Approval identity comes from the credential.** Three credentials in order of
  authority: a signed bearer token (subject is the approver), a per-person key in
  `X-Operator-Key`, and the shared client key the dashboard proxy injects. The
  shared key resolves to a non-attributable identity on purpose.
- **Tokens, not just keys.** `POST /api/auth/session` exchanges a verified
  operator credential for a short-lived signed token. Validation uses Spring
  Security's JOSE support: an external provider via
  `aegis.security.oidc.issuer-uri` (OIDC discovery, RS256), or the built-in
  issuer (HS256) for local and demo runs. Signature, `iss`, `aud` and `exp` are
  all enforced, an invalid token is never downgraded to a weaker credential, and
  with neither issuer configured no token is accepted at all.
- **Approvals are attested.** Each signature stores how the identity was proven
  and, for a token, the issuer and the token id, so an audit can point at the
  exact document that signed rather than a reusable secret.
- **Two-person rule** for HIGH-risk actions: two distinct named operators, no
  double-signing, a single operator can still reject, and the requirement is
  captured on the proposal when it is created so relaxing the policy later
  cannot rewrite what an action needed.
- **The dashboard can sign as a person**, so the two-person flow is a UI flow
  rather than a curl exercise: sign in (token in `sessionStorage`, dropped on
  expiry), approve, sign out, sign in as the second operator, approve.
- **Safety-critical paths** with tests: approval gate + TTL, idempotent executor
  with a durable `actions` row, verifier, rollback engine, evaluation, and the
  two latent defects found in the audit (lost approved command, self-invoked
  `@Transactional`).
- **Ingest throughput** (the previous ceiling, ~490 events/s) fixed by moving
  dedup and rule state from one Redis round trip per event to one per poll batch,
  with the buffer cap, partition count and listener concurrency made explicit.
  See §3.
- **Ingest observability**: ordered / straggler-drop / overflow-drop counters,
  plus drops caused by a failed sink call and failed anomaly writes
  (`aegis_events_dropped_sink_total`, `aegis_anomalies_failed_total`), so
  overload and downstream failure are visible rather than silent; per-topic
  Kafka lag gauge; per-stage timers
  (`aegis_ingest_{dedup,buffer_offer,rule,event_to_rule}_seconds`).
- **Feed hardening**: single-use STOMP ticket from `POST /api/ws-ticket` in the
  CONNECT frame, so `/ws` is not readable by anything that can reach the port.
- **UI**: Apple-official light system, no purple / pills / emoji / em dashes;
  distinct loading vs empty vs offline states; keyboard-activatable `tablist`,
  `:focus-visible`, `prefers-reduced-motion`; sample data is always labeled.
- **Repo hygiene**: tracked build output, `tsconfig.tsbuildinfo` and root junk
  removed; `.gitignore` extended; the work committed in reviewable chunks.

## 3. Measured limits

- **Ingest holds 10,000 events/s in 30-second steps with zero events dropped**,
  and that is where the ladder ends rather than where the pipeline broke: every
  step kept up 94-96% of the events due by the end of its phase, a deficit that
  does not grow with the offered rate; `received = deduplicated + ordered` closed
  exactly over the run (756,498 = 75,497 + 681,001); and `event_time -> rule`
  stayed within 5.51-5.62s at every rate from 200/s to 10,000/s, i.e. the 5s
  ordering window plus under 1.2s of pipeline at the 99th percentile. Method and
  raw output: [`docs/LOADTEST.md`](LOADTEST.md), `docs/loadtest-ingest.txt`.
- **Earlier, lower ceiling numbers were the rig and a broken run, not the
  service.** While those passes fell behind, ingest-svc used under one core of
  sixteen (0.076 of `process_cpu_usage`), Redis stayed under 2% (8.77 µs per
  `SET` by `INFO commandstats`), and the broker saturated at 652% CPU because the
  load generator fed it one produce request per record; batching and compressing
  the generator like a real producer fixed that half. The other half was the run
  environment: the ingest database had lost its schema (23
  `relation "anomalies" does not exist` failures were reported) and the flush
  path could lose an ordered batch, or an anomaly, when a downstream call threw.
  Both are now contained and counted, with tests, and the ladder above is the
  first clean run.
- **Overload degrades by dropping, not delaying** — a deliberate bounded-memory
  policy, and a failing downstream call costs the work it touched rather than
  vanishing. The counters
  (`aegis_events_dropped_{straggler,overflow,sink}_total`,
  `aegis_anomalies_failed_total`) make both visible, and the ladder exercises it:
  in the earlier configuration a mis-sized queue cap dropped 22,979 events at
  1,000/s, which is exactly the failure this metric exists to surface.

## 4. What is left, and the next big step

Ranked by blast radius.

1. **Transport hardening (now the largest remaining risk).** Kafka is PLAINTEXT,
   Postgres and Redis use default credentials, and all three talk over a local
   bridge network. Reaching any of them from off-host is enough to read or
   rewrite incident history. Managed services with TLS + SASL/ACLs, credentials
   from a secret manager, and a private network for the Prometheus scrape.
2. **Find the ceiling with a soak, not a ladder.** The ladder now holds 10,000/s
   in 30-second steps; it cannot say what happens after ten minutes there, and the
   drain rates quoted at the top two steps are lower bounds because events
   produced in the last five seconds cannot complete until their window elapses.
   Ten minutes at 10,000/s and a 15,000/s step, with the broker off the producer
   and consumer host, is the next honest measurement.
3. **Multi-instance WebSocket.** The STOMP broker is the in-process simple
   broker with in-memory session tracking, so a second incident-svc instance
   would not receive the other's frames. A relay and a shared session store are
   the fix; tickets already live in Redis for that reason.
4. **Token issuance is a stand-in locally.** The built-in HS256 issuer exists so
   the demo runs without an identity provider. Before a real deployment, point
   `aegis.security.oidc.issuer-uri` at the provider, add the auth-code + PKCE
   flow to the dashboard, and rotate the operator keys out entirely.
5. **Ship the work.** The tree is committed in reviewable chunks; do not push
   until (1) is decided if the target is anything public.

## 5. Not re-run here (and why)

- Container image builds (`deploy/Dockerfile.*`) were not rebuilt; the task
  definitions remain unchanged from the audited revision.
- The nginx proxy template was reviewed rather than exercised for the token
  path: it forwards client headers (including `Authorization` and
  `X-Operator-Key`) and only sets `Host` and `X-API-Key`, so the token reaches
  incident-svc. The dev proxy path is what the demo above ran through.
