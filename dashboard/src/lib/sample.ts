import type {
  ActionRecord,
  Incident,
  Proposal,
  TimelineEvent,
  TraceStep,
} from "./types";

// Sample data, identical to the approved design mockup. Used ONLY when the
// backend is unreachable so the dashboard is never an empty shell; every
// view that can render sample data carries a visible "sample data" label.

const ago = (minutes: number) =>
  new Date(Date.now() - minutes * 60_000).toISOString();

export const sampleIncidents: Incident[] = [
  {
    id: 1,
    externalId: "INC-2041",
    service: "checkout-service",
    severity: "SEV2",
    status: "AWAITING_APPROVAL",
    summary: "Error rate 8.2%, p99 1.4s. Connection pool exhaustion suspected",
    openedAt: ago(4),
    resolvedAt: null,
  },
  {
    id: 2,
    externalId: "INC-2040",
    service: "payment-service",
    severity: "SEV2",
    status: "VERIFYING",
    summary: "CPU saturation: scaled 2 to 4 replicas, verifying recovery",
    openedAt: ago(10),
    resolvedAt: null,
  },
  {
    id: 3,
    externalId: "INC-2039",
    service: "search-service",
    severity: "SEV1",
    status: "EXECUTING",
    summary: "Rollback v2.4.1 to v2.4.0 after deploy regression (5xx burst)",
    openedAt: ago(2),
    resolvedAt: null,
  },
  {
    id: 4,
    externalId: "INC-2038",
    service: "inventory-service",
    severity: "SEV3",
    status: "RESOLVED",
    summary: "Cache TTL stampede. Cleared cache, warmed in 40s",
    openedAt: ago(26),
    resolvedAt: ago(20),
  },
  {
    id: 5,
    externalId: "INC-2037",
    service: "auth-service",
    severity: "SEV3",
    status: "RESOLVED",
    summary: "Auto-recovery failed verification, auto-rollback, escalated to human",
    openedAt: ago(72),
    resolvedAt: ago(54),
  },
];

export const sampleTrace: TraceStep[] = [
  {
    tool: "get_service_health",
    input: "service=checkout-service",
    output: "2/3 instances healthy. checkout-api-3 UNHEALTHY (last heartbeat 9s ago)",
    durationMs: 210,
  },
  {
    tool: "get_metrics",
    input: "checkout-service · error_rate · 15m",
    output: "Spike at 14:02 · 0.4% to 8.2% · sustained across 3 windows",
    durationMs: 340,
  },
  {
    tool: "get_metrics",
    input: "checkout-service · p99_latency · 15m",
    output: "p99 1.4s (baseline 210ms) · correlation with error_rate r = 0.93",
    durationMs: 310,
  },
  {
    tool: "search_logs",
    input: "checkout-service · \"connection pool\" · 15m",
    output: "412x ConnectionTimeout, all from checkout-api-3 · pool exhausted at 14:01:48",
    durationMs: 520,
  },
  {
    tool: "get_deployments",
    input: "checkout-service · last 24h",
    output: "No deploy in 24h, bad-deploy hypothesis rejected",
    durationMs: 180,
  },
];

export const sampleProposal: Proposal = {
  id: 8812,
  externalId: "P-8812",
  actionType: "restart_instance",
  params: { target: "checkout-api-3" },
  riskLevel: "MEDIUM",
  confidence: 0.82,
  reasoning: {
    evidence: ["error_rate 8.2%", "p99 1.4s", "412 ConnectionTimeout", "no recent deploy"],
    text: "checkout-api-3 is unhealthy and the only source of connection-pool timeouts. Metrics correlate strongly (r = 0.93). No deploy in 24h rules out a bad release. Restart isolates the instance; if metrics do not recover within the verification window the action is rolled back automatically.",
  },
  status: "PENDING",
  createdAt: ago(1),
  decidedAt: null,
  decidedBy: null,
};

export const sampleTimeline: TimelineEvent[] = [
  {
    id: 1,
    eventType: "Anomaly confirmed",
    detail: { note: "error_rate > 5% for 2 consecutive windows" },
    createdAt: ago(4.1),
  },
  {
    id: 2,
    eventType: "Incident opened",
    detail: { note: "agent investigation started" },
    createdAt: ago(4.05),
  },
  {
    id: 3,
    eventType: "Evidence collected",
    detail: { note: "health, metrics x2, logs, deployments" },
    createdAt: ago(3.9),
  },
  {
    id: 4,
    eventType: "Proposal generated",
    detail: { note: "risk MEDIUM · routed to approval gate" },
    createdAt: ago(3.88),
  },
];

export const sampleActions: ActionRecord[] = [
  {
    id: "A-4471",
    actionType: "scale_replicas",
    service: "auth-service",
    status: "EXECUTED",
    verdict: "EXECUTED",
    detail: "Scale auth-service 2 to 4 replicas · undo_params captured",
    createdAt: ago(70),
  },
  {
    id: "A-4471b",
    actionType: "verify",
    service: "auth-service",
    status: "FAILED",
    verdict: "VERIFY FAIL",
    detail: "Verification failed · error rate still > 5% after the 5:00 window",
    createdAt: ago(65),
  },
  {
    id: "A-4472",
    actionType: "scale_replicas",
    service: "auth-service",
    status: "ROLLING_BACK",
    verdict: "ROLLED BACK",
    detail: "Auto-rollback · scaled 4 to 2 replicas via undo_params",
    createdAt: ago(64),
  },
  {
    id: "A-4473",
    actionType: "escalate",
    service: "auth-service",
    status: "ESCALATED",
    verdict: "ESCALATED",
    detail: "Escalated to human · incident reopened with escalation note",
    createdAt: ago(64),
  },
  {
    id: "A-4474",
    actionType: "rollback_deploy",
    service: "auth-service",
    status: "RESOLVED",
    verdict: "RESOLVED",
    detail: "Human: rollback deploy v2.2.1 to v2.2.0 · verified recovered",
    createdAt: ago(50),
  },
];

/** Sample stats row, shown only when there is no live connection. */
export const sampleStats = [
  { label: "Active incidents", value: "3", delta: "▲ 1 in the last hour", tone: "bad" },
  { label: "Resolved today", value: "14", delta: "MTTR 11m 24s", tone: "good" },
  { label: "Auto-recovery success", value: "87.2%", delta: "▲ 3.1% this week", tone: "good" },
  { label: "Agent proposal precision", value: "92%", delta: "last eval · 128 incidents", tone: "" },
  { label: "Shadow mode", value: "Active", delta: "LOW-risk auto-approve enabled", tone: "" },
] as const;