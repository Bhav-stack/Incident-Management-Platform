
import type { Incident, Proposal, TimelineEvent } from "./types";
import { apiUrl } from "./config";
import { operatorToken } from "./session";

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(apiUrl(path));
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

function operatorHeaders(): Record<string, string> {
  const token = operatorToken();
  if (!token) {
    throw new Error("Please sign in with an operator key first.");
  }
  return { Authorization: `Bearer ${token}` };
}

interface WireTimelineEvent {
  id: number;
  eventType: string;
  createdAt: string;
  payload?: Record<string, unknown>;
  detail?: Record<string, unknown>;
}

export const api = {
  incidents: () => getJson<Incident[]>("/api/incidents"),

  incident: (id: string) =>
    getJson<Incident>(`/api/incidents/${id}`),

  timeline: async (id: string): Promise<TimelineEvent[]> => {
    const events = await getJson<WireTimelineEvent[]>(
      `/api/incidents/${id}/events`,
    );

    return events.map((event) => ({
      id: event.id,
      eventType: event.eventType,
      createdAt: event.createdAt,
      detail: event.payload ?? event.detail ?? {},
    }));
  },

  proposals: (incidentId: string) =>
    getJson<Proposal[]>(`/api/incidents/${incidentId}/proposals`),

  approve: (proposalId: string) =>
    fetch(apiUrl(`/api/proposals/${proposalId}/approve`), {
      method: "POST",
      headers: operatorHeaders(),
    }),

  reject: (proposalId: string) =>
    fetch(apiUrl(`/api/proposals/${proposalId}/reject`), {
      method: "POST",
      headers: operatorHeaders(),
    }),

  wsTicket: () =>
    postJson<{ ticket: string; expiresInSeconds: number }>(
      "/api/ws-ticket",
      operatorHeaders(),
    ),

  killSwitches: () =>
    getJson<KillSwitches>("/api/controls/kill-switches"),

  setKillSwitch: (target: string, killed: boolean) =>
    put(`/api/controls/kill-switches/${target}`, { killed }),

  policy: () => getJson<PolicyState>("/api/controls/policy"),

  setPolicyFlag: (
    flag: "shadow-mode" | "auto-approve-low",
    enabled: boolean,
  ) => put(`/api/controls/policy/${flag}`, { enabled }),

  replayStats: () => getJson<ReplayStats>("/api/replay/stats"),

  runReplay: () =>
    postJson<ReplayStats>("/api/replay/run", operatorHeaders()),
};

async function put(path: string, body: unknown): Promise<void> {
  const res = await fetch(apiUrl(path), {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      ...operatorHeaders(),
    },
    body: JSON.stringify(body),
  });

  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
}

async function postJson<T>(
  path: string,
  headers: Record<string, string> = {},
): Promise<T> {
  const res = await fetch(apiUrl(path), {
    method: "POST",
    headers,
  });

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
