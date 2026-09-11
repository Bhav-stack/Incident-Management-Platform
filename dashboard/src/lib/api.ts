import type { Incident, Proposal, TimelineEvent } from "./types";

// Thin REST client for incident-svc. The live feed arrives over STOMP; REST
// is the replay/snapshot layer (initial list, detail, timeline, decisions).

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${path} -> ${res.status}`);
  return res.json() as Promise<T>;
}

export const api = {
  incidents: () => getJson<Incident[]>("/api/incidents"),
  incident: (id: string) => getJson<Incident>(`/api/incidents/${id}`),
  timeline: (id: string) => getJson<TimelineEvent[]>(`/api/incidents/${id}/events`),
  proposals: (incidentId: string) =>
    getJson<Proposal[]>(`/api/incidents/${incidentId}/proposals`),
  approve: (proposalId: string, approver: string) =>
    fetch(`/api/proposals/${proposalId}/approve`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ approver }),
    }),
  reject: (proposalId: string, approver: string) =>
    fetch(`/api/proposals/${proposalId}/reject`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ approver }),
    }),
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