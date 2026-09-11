// Mirrors the backend contracts (contracts module + incident-svc entities).
// Field names match the JSON exactly so the dashboard is a dumb consumer.

export type IncidentStatus =
  | "OPEN"
  | "INVESTIGATING"
  | "PROPOSAL"
  | "AWAITING_APPROVAL"
  | "EXECUTING"
  | "VERIFYING"
  | "RESOLVED"
  | "REJECTED"
  | "EXPIRED"
  | "FAILED"
  | "ROLLING_BACK";

export type Severity = "SEV1" | "SEV2" | "SEV3";

export type ProposalStatus =
  | "PENDING"
  | "APPROVED"
  | "REJECTED"
  | "EXPIRED"
  | "EXECUTED";

export interface Incident {
  id: number;
  externalId: string;
  service: string;
  severity: Severity;
  status: IncidentStatus;
  summary: string;
  openedAt: string;
  resolvedAt: string | null;
}

export interface IncidentStateEvent {
  eventId: string;
  incidentId: string;
  service: string;
  severity: Severity;
  status: IncidentStatus;
  summary: string;
  openedAt: string;
  occurredAt: string;
}

export interface Proposal {
  id: number;
  externalId: string;
  actionType: string;
  params: Record<string, unknown>;
  riskLevel: string;
  confidence: number;
  reasoning: Record<string, unknown>;
  status: ProposalStatus;
  createdAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
}

export interface ProposalMessage {
  externalId: string;
  incidentExternalId: string;
  service: string;
  actionType: string;
  riskLevel: string;
  confidence: number;
  status: ProposalStatus;
  createdAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
}

export interface TimelineEvent {
  id: number;
  eventType: string;
  detail: Record<string, unknown>;
  createdAt: string;
}

export interface TraceStep {
  tool: string;
  input: string;
  output: string;
  durationMs?: number;
}

// A recovery action the agent or a human executed (post-mortem history).
export interface ActionRecord {
  id: string;
  actionType: string;
  service: string;
  status: string;
  verdict: string;
  detail: string;
  createdAt: string;
}