import type { Incident, Proposal, TimelineEvent } from "./types";
import { operatorToken } from "./session";

// Thin REST client for incident-svc. The live feed arrives over STOMP; REST
// is the replay/snapshot layer (initial list, detail, timeline, decisions).

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

// A decision is the one call that carries the operator's own credential, so
// the signature is attributed to the person who made it rather than to the
// shared key the dev proxy attaches. Read-only calls stay on the proxy key:
// a token that has expired should not stop the board from loading.
function decisionHeaders(): Record<string, string> {
  const token = operatorToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// The timeline entity serialises its detail as `payload` (IncidentEvent's own
// field name); the dashboard calls it `detail`. Normalising here keeps one
// name inside the UI and stops the columns from silently rendering empty.
interface WireTimelineEvent {
  id: number;
  eventType: string;
  createdAt: string;
  payload?: Record<string, unknown>;
  detail?: Record<string, unknown>;
}

export const api = {
  incidents: () => getJson<Incident[]>("/api/incidents"),
  incident: (id: string) => getJson<Incident>(`/api/incidents/${id}`),
  timeline: async (id: string): Promise<TimelineEvent[]> => {
    const events = await getJson<WireTimelineEvent[]>(`/api/incidents/${id}/events`);
    return events.map((e) => ({
      id: e.id,
      eventType: e.eventType,
      createdAt: e.createdAt,
      detail: e.payload ?? e.detail ?? {},
    }));
  },
  proposals: (incidentId: string) =>
    getJson<Proposal[]>(`/api/incidents/${incidentId}/proposals`),
  // The approver is never a request field: it comes from the credential, so a
  // caller cannot claim to be someone they are not. When an operator is signed
  // in that credential is their token; otherwise it is the proxy's shared key,
  // which is a client identity and cannot satisfy a two-person rule.
  approve: (proposalId: string) =>
    fetch(`/api/proposals/${proposalId}/approve`, {
      method: "POST",
      headers: decisionHeaders(),
    }),
  reject: (proposalId: string) =>
    fetch(`/api/proposals/${proposalId}/reject`, {
      method: "POST",
      headers: decisionHeaders(),
    }),

  /**
   * Short-lived single-use ticket for the STOMP feed. The proxy injects the
   * control-plane key, so the browser never holds it.
   */
  wsTicket: () =>
    postJson<{ ticket: string; expiresInSeconds: number }>("/api/ws-ticket"),
  killSwitches: () => getJson<KillSwitches>("/api/controls/kill-switches"),
  setKillSwitch: (target: string, killed: boolean) =>
    put(`/api/controls/kill-switches/${target}`, { killed }),
  policy: () => getJson<PolicyState>("/api/controls/policy"),
  setPolicyFlag: (flag: "shadow-mode" | "auto-approve-low", enabled: boolean) =>
    put(`/api/controls/policy/${flag}`, { enabled }),
  replayStats: () => getJson<ReplayStats>("/api/replay/stats"),
  runReplay: () => postJson<ReplayStats>("/api/replay/run"),
};

async function put(path: string, body: unknown): Promise<void> {
  const res = await fetch(path, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
}

async function postJson<T>(path: string): Promise<T> {
  const res = await fetch(path, { method: "POST" });
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

export interface KillSwitches {
  global: boolean;
  services: { service: string; killed: boolean }[];
}

export interface PolicyState {
  shadowMode: boolean;
  autoApproveLow: boolean;
  confidenceMinimums: Record<string, number>;
  minEvidenceSignals: number;
  twoPersonLevels: string[];
}

export interface ReplayStats {
  total: number;
  correct: number;
  tp: number;
  fp: number;
  fn: number;
  precision: number;
  recall: number;
}