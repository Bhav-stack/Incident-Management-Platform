import { useEffect, useState } from "react";
import type { Incident, Proposal, TimelineEvent, TraceStep } from "./lib/types";
import { api } from "./lib/api";
import { connectLiveFeed } from "./lib/ws";
import { nowUtc } from "./lib/format";
import { sampleIncidents, sampleProposal, sampleTimeline } from "./lib/sample";
import { ShieldIcon, PowerIcon } from "./components/Icons";
import LiveView from "./components/LiveView";
import DetailView from "./components/DetailView";
import PostMortemView from "./components/PostMortemView";
import ControlsView from "./components/ControlsView";

type View = "live" | "detail" | "postmortem" | "controls";

const ACTIVE_STATUSES = [
  "OPEN", "INVESTIGATING", "PROPOSAL", "AWAITING_APPROVAL",
  "EXECUTING", "VERIFYING", "FAILED", "ROLLING_BACK",
];

export default function App() {
  const [view, setView] = useState<View>("live");
  const [incidents, setIncidents] = useState<Incident[]>([]);
  // backendUp: the REST snapshot loaded, so incidents are real data.
  // wsConnected: the live feed is streaming state changes.
  const [backendUp, setBackendUp] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [selected, setSelected] = useState<Incident | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [trace, setTrace] = useState<TraceStep[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const [approvalCount, setApprovalCount] = useState(0);

  // Clock tick (topbar + TTL countdown share it).
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  // Snapshot load: incidents from REST; detail lazily on selection. If the
  // backend is unreachable the approved sample takes over, clearly labeled.
  useEffect(() => {
    api
      .incidents()
      .then((list) => {
        setIncidents(list);
        setBackendUp(true);
      })
      .catch(() => {
        setIncidents(sampleIncidents);
        setSelected(sampleIncidents[0]);
        setTimeline(sampleTimeline);
        setProposal(sampleProposal);
        setTrace([]);
      })
      .finally(() => setLoaded(true));
  }, []);

  // Live feed: state changes arrive over STOMP and update the list in place.
  useEffect(() => {
    const disconnect = connectLiveFeed({
      onConnected: (connected) => {
        setWsConnected(connected);
        if (connected) void refreshIncidents();
      },
      onIncident: (event) => {
        setIncidents((prev) => {
          const existing = prev.find((i) => i.externalId === event.incidentId);
          if (existing) {
            return prev.map((i) =>
              i.externalId === event.incidentId
                ? { ...i, status: event.status, summary: event.summary, severity: event.severity }
                : i,
            );
          }
          const fresh: Incident = {
            id: 0,
            externalId: event.incidentId,
            service: event.service,
            severity: event.severity,
            status: event.status,
            summary: event.summary,
            openedAt: event.openedAt,
            resolvedAt: null,
          };
          return [fresh, ...prev];
        });
        // If this event concerns the open detail view, refresh it.
        setSelected((prev) => {
          if (prev && prev.externalId === event.incidentId) {
            void loadDetail(event.incidentId);
          }
          return prev;
        });
      },
      onProposal: (msg) => {
        setApprovalCount((c) => c + 1);
        setProposal((prev) =>
          prev && prev.externalId === msg.externalId
            ? { ...prev, status: msg.status, decidedAt: msg.decidedAt, decidedBy: msg.decidedBy }
            : prev,
        );
      },
    });
    return disconnect;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refreshIncidents = async () => {
    try {
      setIncidents(await api.incidents());
    } catch {
      // Stay on current data; the feed will keep retrying.
    }
  };

  const loadDetail = async (incidentId: string) => {
    try {
      const [inc, evts, props] = await Promise.all([
        api.incident(incidentId),
        api.timeline(incidentId),
        api.proposals(incidentId),
      ]);
      setSelected(inc);
      setTimeline(evts);
      setProposal(props[0] ?? null);
      setTrace(extractTrace(props[0] ?? null, evts));
    } catch {
      // Unreachable backend: fall back to the sample detail.
      setSelected(sampleIncidents[0]);
      setTimeline(sampleTimeline);
      setProposal(sampleProposal);
      setTrace([]);
    }
  };

  const selectIncident = (inc: Incident) => {
    setSelected(inc);
    setView("detail");
    if (backendUp) void loadDetail(inc.externalId);
    else {
      setTimeline(sampleTimeline);
      setProposal(sampleProposal);
      setTrace([]);
    }
  };

  const activeCount = incidents.filter((i) => ACTIVE_STATUSES.includes(i.status)).length;
  const live = backendUp;
  const wsLabel = wsConnected ? "Live" : backendUp ? "Reconnecting" : "Offline";

  return (
    <>
      <header className="topbar">
        <div className="brand">
          <div className="mark"><ShieldIcon /></div>
          <div className="name">Aegis</div>
          <div className="sub" style={{ marginLeft: 2 }}>Autonomous Incident Recovery</div>
        </div>
        <nav className="tabs">
          <Tab label="Live Incidents" count={activeCount} view="live" current={view} onClick={setView} />
          <Tab label={selected ? `Incident ${selected.externalId}` : "Incident"} view="detail" current={view} onClick={setView} />
          <Tab label="Post-Mortem" view="postmortem" current={view} onClick={setView} />
          <Tab label="Controls" view="controls" current={view} onClick={setView} />
        </nav>
        <div className="topbar-right">
          <div className={`live-dot ${wsConnected ? "" : "offline"}`}><span className="dot"></span>{wsLabel}</div>
          <div className="clock">{nowUtc()}</div>
          <div className="killswitch-btn"><PowerIcon /> Global kill switch</div>
        </div>
      </header>

      {view === "live" && (
        <LiveView incidents={incidents} live={live} onSelect={selectIncident} />
      )}
      {view === "detail" && selected && (
        <DetailView
          incident={selected}
          timeline={timeline}
          proposal={proposal}
          trace={trace}
          live={live}
          now={now}
        />
      )}
      {view === "postmortem" && <PostMortemView live={live} />}
      {view === "controls" && <ControlsView />}

      <div className="footer">
        Aegis · {!backendUp
          ? "sample data · backend offline"
          : wsConnected
            ? "live data · ws /topic/incidents + /topic/proposals"
            : "live data · websocket reconnecting"}
        {approvalCount > 0 && ` · ${approvalCount} proposal event${approvalCount === 1 ? "" : "s"} received`}
        {!loaded && " · loading"}
      </div>
    </>
  );
}

function Tab({
  label, count, view, current, onClick,
}: {
  label: string; count?: number; view: View; current: View; onClick: (v: View) => void;
}) {
  return (
    <div className={`tab ${current === view ? "active" : ""}`} onClick={() => onClick(view)}>
      {label}
      {count !== undefined && count > 0 && <span className="count">{count}</span>}
    </div>
  );
}

/** Trace comes from the agent's reasoning (Phase 4); until then, evidence
 * events are shown as metric steps so the live view is never fabricated. */
function extractTrace(proposal: Proposal | null, events: TimelineEvent[]): TraceStep[] {
  const raw = proposal?.reasoning?.trace;
  if (Array.isArray(raw)) {
    return raw.filter(
      (t): t is TraceStep =>
        typeof t === "object" && t !== null && typeof (t as TraceStep).tool === "string",
    );
  }
  return events
    .filter((e) => e.eventType === "EVIDENCE")
    .map((e) => ({
      tool: "get_metrics",
      input: `${String(e.detail.signal ?? "?")} · ${String(e.detail.value ?? "?")} vs threshold ${String(e.detail.threshold ?? "?")}`,
      output: `Anomaly confirmed on ${String(e.detail.sourceEventId ?? "?")}`,
    }));
}