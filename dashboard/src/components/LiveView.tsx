import type { Incident } from "../lib/types";
import { ageOf } from "../lib/format";
import { sevClass, statusClass } from "../lib/ui";
import { sampleStats } from "../lib/sample";

interface Props {
  incidents: Incident[];
  live: boolean;
  onSelect: (incident: Incident) => void;
}

export default function LiveView({ incidents, live, onSelect }: Props) {
  const active = incidents.filter((i) =>
    ["OPEN", "INVESTIGATING", "PROPOSAL", "AWAITING_APPROVAL", "EXECUTING", "VERIFYING", "FAILED", "ROLLING_BACK"].includes(i.status),
  ).length;
  const resolvedToday = incidents.filter((i) => {
    if (i.status !== "RESOLVED") return false;
    return new Date(i.openedAt).toDateString() === new Date().toDateString();
  }).length;

  const stats = live
    ? [
        { label: "Active incidents", value: String(active), delta: "from incidents topic", tone: "" },
        { label: "Resolved today", value: String(resolvedToday), delta: "from incidents topic", tone: "good" },
        { label: "Auto-recovery success", value: "sample", delta: "eval harness, not live", tone: "" },
        { label: "Agent proposal precision", value: "sample", delta: "eval harness, not live", tone: "" },
        { label: "Shadow mode", value: "Config", delta: "controls view", tone: "" },
      ]
    : [...sampleStats];

  return (
    <section className="view active">
      <div className="stats">
        {stats.map((s) => (
          <div className="stat" key={s.label}>
            <div className="label">{s.label}</div>
            <div className="value">{s.value}</div>
            <div className={`delta ${s.tone}`}>{s.delta}</div>
          </div>
        ))}
      </div>

      <div className="card">
        <div className="card-head">
          <h2>Incident feed</h2>
          <div className="hint">kafka · incidents topic · ws /topic/incidents</div>
        </div>
        <div className="inc-row inc-head-row">
          <div>Incident</div><div>Service</div><div>Severity</div><div>Status</div><div>Summary</div><div>Age</div><div>Conf.</div>
        </div>
        {incidents.length === 0 && (
          <div className="empty-note">No incidents yet. Trigger the error-spike scenario in the simulator.</div>
        )}
        {incidents.map((inc) => (
          <div className="inc-row" key={inc.externalId} onClick={() => onSelect(inc)}>
            <div className="id">{inc.externalId}</div>
            <div className="svc">{inc.service}</div>
            <div><span className={`sev ${sevClass(inc.severity)}`}>{inc.severity}</span></div>
            <div><span className={`status ${statusClass(inc.status)}`}><span className="dot"></span>{inc.status}</span></div>
            <div className="sum">{inc.summary}</div>
            <div className="age">{ageOf(inc.openedAt)}</div>
            <div className="conf">-</div>
          </div>
        ))}
      </div>
      {!live && <p className="sample-note warn" style={{ marginTop: 12 }}>sample data · no live connection to incident-svc</p>}
    </section>
  );}
