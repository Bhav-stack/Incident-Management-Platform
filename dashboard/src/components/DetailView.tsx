import { useState, type ReactNode } from "react";
import type { Incident, Proposal, TimelineEvent, TraceStep } from "../lib/types";
import { api } from "../lib/api";
import { countdown, timeOf } from "../lib/format";
import { riskClass, sevClass, statusClass, timelineDotColor } from "../lib/ui";
import { sampleTrace } from "../lib/sample";
import { CheckIcon, XIcon } from "./Icons";

// Approval window shown to the operator. Mirrors proposal.ttl on the backend
// (5 minutes); the backend remains the source of truth for expiry.
const PROPOSAL_TTL_MS = 5 * 60_000;

interface Props {
  incident: Incident;
  timeline: TimelineEvent[];
  proposal: Proposal | null;
  trace: TraceStep[];
  live: boolean;
  now: number;
}

export default function DetailView({ incident, timeline, proposal, trace, live, now }: Props) {
  const [decision, setDecision] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const shownTrace = trace.length > 0 ? trace : live ? [] : sampleTrace;

  const decide = async (approve: boolean) => {
    if (!proposal || proposal.status !== "PENDING") return;
    setBusy(true);
    try {
      const res = approve
        ? await api.approve(proposal.externalId, "dashboard")
        : await api.reject(proposal.externalId, "dashboard");
      setDecision(
        res.ok
          ? { ok: true, text: approve ? `Approved · proposal ${proposal.externalId} queued for execution` : `Rejected · proposal ${proposal.externalId} closed` }
          : { ok: false, text: `Decision failed (${res.status}). The proposal may already be decided.` },
      );
    } catch {
      setDecision({ ok: false, text: "Decision failed. Is incident-svc reachable?" });
    } finally {
      setBusy(false);
    }
  };

  const ttlDeadline = proposal
    ? new Date(proposal.createdAt).getTime() + PROPOSAL_TTL_MS
    : 0;

  return (
    <section className="view active">
      <div className="card inc-header">
        <div><span className={`sev ${sevClass(incident.severity)}`}>{incident.severity}</span></div>
        <div className="title">
          <h1>{incident.externalId} · {incident.service}</h1>
          <div className="meta">
            opened {timeOf(incident.openedAt)} UTC · <b>status</b> {incident.status}
            {proposal && <> · proposal {proposal.externalId}</>}
          </div>
        </div>
        <div><span className={`status ${statusClass(incident.status)}`}><span className="dot"></span>{incident.status}</span></div>
      </div>

      <div className="detail-grid">
        <div>
          <div className="card">
            <div className="card-head">
              <h2>Agent investigation · tool-calling trace</h2>
              <div className="hint">agent_runs · incident {incident.externalId}</div>
            </div>
            {shownTrace.length === 0 && (
              <div className="empty-note">
                No agent trace for this incident. Traces are recorded when the agent proposer runs (agent mode).
              </div>
            )}
            {shownTrace.map((step, i) => (
              <div className="step" key={i}>
                <div>
                  <div className="tool"><span className="n">{String(i + 1).padStart(2, "0")}</span>{step.tool}</div>
                  <div className="in">{step.input}</div>
                </div>
                <div className="out">{renderOutput(step.output)}</div>
                <div className="dur">{step.durationMs ? `${step.durationMs}ms` : ""} <span className="done">✓</span></div>
              </div>
            ))}
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head"><h2>Incident timeline</h2><div className="hint">incident_events</div></div>
            <div className="tl">
              {timeline.length === 0 && (
                <div className="empty-note">No timeline events recorded.</div>
              )}
              {timeline.map((ev) => (
                <div className="tl-item" key={ev.id}>
                  <div className={`dot ${timelineDotColor(ev.eventType)}`}></div>
                  <div className="t">
                    {ev.eventType}
                    {ev.detail && Object.keys(ev.detail).length > 0 && (
                      <> · {formatDetail(ev.detail)}</>
                    )}
                    <span className="when">{timeOf(ev.createdAt)}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        <div className="proposal">
          <div className="card">
            <div className="card-head"><h2>Proposed action</h2><div className="hint">{proposal ? `proposal ${proposal.externalId}` : "no proposal"}</div></div>
            {proposal ? (
              <div className="body">
                <div className="action-label">Action type</div>
                <div className="action-name">
                  {actionLabel(proposal.actionType)} <code>{targetOf(proposal)}</code>
                </div>

                <div className="conf-bar"><div style={{ width: `${Math.round(proposal.confidence * 100)}%` }}></div></div>
                <div className="conf-caption">
                  <span><b>{proposal.confidence.toFixed(2)}</b> confidence</span>
                  <span>threshold 0.70 ✓</span>
                </div>

                <div style={{ marginTop: 12 }}>
                  <div className="kv"><span className="k">Risk tier</span><span className={`v ${riskClass(proposal.riskLevel)}`}>{proposal.riskLevel}</span></div>
                  <div className="kv"><span className="k">Status</span><span className="v">{proposal.status}</span></div>
                  <div className="kv"><span className="k">Reversibility</span><span className="v">verification-gated</span></div>
                  <div className="kv"><span className="k">Evidence signals</span><span className="v">{evidenceOf(proposal).length}</span></div>
                  <div className="kv"><span className="k">Kill switch</span><span className="v on">armed / off</span></div>
                </div>

                {evidenceOf(proposal).length > 0 && (
                  <div className="chips">
                    {evidenceOf(proposal).map((e) => (
                      <span className="chip warn" key={e}>{e}</span>
                    ))}
                  </div>
                )}

                <div className="reason">
                  <b>Agent reasoning.</b>{" "}
                  {typeof proposal.reasoning.text === "string" && proposal.reasoning.text.length > 0
                    ? proposal.reasoning.text
                    : "Reasoning recorded on the proposal is not available; see the evidence chips above."}
                </div>

                {proposal.status === "PENDING" && (
                  <>
                    <div className="ttl">
                      <div className="lbl">Approval window</div>
                      <div className="num">{countdown(ttlDeadline, now)}</div>
                    </div>
                    <div className="btn-row">
                      <button className="btn approve" disabled={busy} onClick={() => void decide(true)}><CheckIcon /> Approve</button>
                      <button className="btn reject" disabled={busy} onClick={() => void decide(false)}><XIcon /> Reject</button>
                    </div>
                  </>
                )}
                {decision && (
                  <div className={`decision-msg ${decision.ok ? "" : "err"}`}>{decision.text}</div>
                )}
                {proposal.status !== "PENDING" && (
                  <div className="decision-msg">
                    Decided {proposal.status.toLowerCase()} by {proposal.decidedBy ?? "system"}
                    {proposal.decidedAt ? ` at ${timeOf(proposal.decidedAt)}` : ""}
                  </div>
                )}
              </div>
            ) : (
              <div className="empty-note" style={{ padding: "28px 22px" }}>
                No recovery proposal yet. The incident is open; a proposer has not produced an action.
              </div>
            )}
          </div>

          <div className="card safety">
            <h3>Safety invariants</h3>
            <div className="kv"><span className="k">Agent write access</span><span className="v">propose only</span></div>
            <div className="kv"><span className="k">Execution authority</span><span className="v on">action executor only</span></div>
            <div className="kv"><span className="k">Idempotency</span><span className="v on">lock + dedup</span></div>
            <div className="kv"><span className="k">Verification window</span><span className="v on">after exec</span></div>
            <div className="kv"><span className="k">Rollback on failure</span><span className="v on">automatic</span></div>
            <div className="kv"><span className="k">Audit trail</span><span className="v on">every tool call</span></div>
          </div>
        </div>
      </div>
      {!live && <p className="sample-note warn" style={{ marginTop: 16 }}>sample data · no live connection to incident-svc</p>}
    </section>
  );
}

function renderOutput(output: string): ReactNode {
  // No HTML from the backend: split on known negative markers and wrap them
  // in the red "hit" style, exactly like the mockup's trace coloring.
  const parts = output.split(/(UNHEALTHY|FAILED|spike|exhausted|\d+\s*×\s*\w+)/gi);
  return parts.map((p, i) =>
    /^(UNHEALTHY|FAILED|spike|exhausted|\d+\s*×\s*\w+)$/i.test(p) ? (
      <span className="hit" key={i}>{p}</span>
    ) : (
      <span key={i}>{p}</span>
    ),
  );
}

function formatDetail(detail: Record<string, unknown>): string {
  return Object.entries(detail)
    .map(([k, v]) => `${k}=${String(v)}`)
    .join(" · ");
}

function actionLabel(actionType: string): string {
  switch (actionType) {
    case "restart_instance":
      return "Restart instance";
    case "scale_replicas":
      return "Scale replicas";
    case "rollback_deploy":
      return "Rollback deploy";
    case "clear_cache":
      return "Clear cache";
    default:
      return actionType;
  }
}

function targetOf(proposal: Proposal): string {
  const t = proposal.params?.target;
  return typeof t === "string" ? t : "";
}

function evidenceOf(proposal: Proposal): string[] {
  const raw = proposal.reasoning?.evidence;
  if (Array.isArray(raw)) return raw.filter((e): e is string => typeof e === "string");
  return [];
}