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
import OperatorSession from "./components/OperatorSession";
import { DetailSkeleton } from "./components/Skeleton";

// Public portfolio demo is read-only by default.
// Set VITE_DEMO_MODE=false to enable the live API path locally.
const DEMO_MODE = import.meta.env.VITE_DEMO_MODE !== "false";

type View = "live" | "detail" | "postmortem" | "controls";

const ACTIVE_STATUSES = [
  "OPEN",
  "INVESTIGATING",
  "PROPOSAL",
  "AWAITING_APPROVAL",
  "EXECUTING",
  "VERIFYING",
  "FAILED",
  "ROLLING_BACK",
];

export default function App() {
  const [view, setView] = useState<View>("live");
  const [incidents, setIncidents] = useState<Incident[]>([]);
  // backendUp is true only when a real REST snapshot was loaded.
  // wsConnected indicates whether the live feed is connected.
  const [backendUp, setBackendUp] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [selected, setSelected] = useState<Incident | null>(null);
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const [proposal, setProposal] = useState<Proposal | null>(null);
  const [trace, setTrace] = useState<TraceStep[]>([]);
  const [now, setNow] = useState(() => Date.now());
  const [approvalCount, setApprovalCount] = useState(0);
  const [detailLoading, setDetailLoading] = useState(false);

  // Clock tick (topbar + TTL countdown share it).
  useEffect(() => {
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, []);

  // Load sample incidents in demo mode; use the backend otherwise.
  useEffect(() => {
    if (DEMO_MODE) {
      setIncidents(sampleIncidents);
      setSelected(sampleIncidents[0]);
      setTimeline(sampleTimeline);
      setProposal(sampleProposal);
      setTrace([]);
      setLoaded(true);
      return;
    }

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

  // Never establish a live feed connection in public demo mode.
  useEffect(() => {
    if (DEMO_MODE) {
      setWsConnected(false);
      return;
    }

    const disconnect = connectLiveFeed({
      onConnected: (connected) => {
        setWsConnected(connected);
        if (connected) void refreshIncidents();
      },
      onIncident: (event) => {
        setIncidents((prev) => {
          const existing = prev.find(
            (i) => i.externalId === event.incidentId,
          );

          if (existing) {
            return prev.map((i) =>
              i.externalId === event.incidentId
                ? {
                    ...i,
                    status: event.status,
                    summary: event.summary,
                    severity: event.severity,
                  }
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

        // Refresh the detail panel if the selected incident changes.
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
            ? {
                ...prev,
                status: msg.status,
                decidedAt: msg.decidedAt,
                decidedBy: msg.decidedBy,
                approvals: msg.approvals?.map((operator) => ({ operator })),
                approvalsRequired: msg.approvalsRequired,
              }
            : prev,
        );
      },
    });

    return disconnect;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const refreshIncidents = async () => {
    if (DEMO_MODE) return;

    try {
      setIncidents(await api.incidents());
    } catch {
      // Keep the current data if the backend becomes unavailable.
    }
  };

  const loadDetail = async (incidentId: string) => {
    if (DEMO_MODE) {
      setSelected(sampleIncidents[0]);
      setTimeline(sampleTimeline);
      setProposal(sampleProposal);
      setTrace([]);
      return;
    }

    setDetailLoading(true);

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
      // Fall back to sample detail when the backend is unavailable.
      setSelected(sampleIncidents[0]);
      setTimeline(sampleTimeline);
      setProposal(sampleProposal);
      setTrace([]);
    } finally {
      setDetailLoading(false);
    }
  };

  const selectIncident = (inc: Incident) => {
    setSelected(inc);
    setView("detail");

    if (!DEMO_MODE && backendUp) {
      void loadDetail(inc.externalId);
    } else {
      setTimeline(sampleTimeline);
      setProposal(sampleProposal);
      setTrace([]);
    }
  };

  const activeCount = incidents.filter((i) =>
    ACTIVE_STATUSES.includes(i.status),
  ).length;

  const live = !DEMO_MODE && backendUp;

  const wsLabel = DEMO_MODE
    ? "Demo"
    : wsConnected
      ? "Live"
      : backendUp
        ? "Reconnecting"
        : "Offline";

  return (
    <>
      <header className="topbar">
        <div className="brand">
          <div className="mark">
            <ShieldIcon />
          </div>
          <div className="name">Aegis</div>
          <div className="sub" style={{ marginLeft: 2 }}>
            Autonomous Incident Recovery
          </div>
        </div>

        <nav className="tabs" role="tablist" aria-label="Dashboard views">
          <Tab
            label="Live Incidents"
            count={activeCount}
            view="live"
            current={view}
            onClick={setView}
          />
          <Tab
            label={selected ? `Incident ${selected.externalId}` : "Incident"}
            view="detail"
            current={view}
            onClick={setView}
          />
          <Tab
            label="Post-Mortem"
            view="postmortem"
            current={view}
            onClick={setView}
          />
          <Tab
            label="Controls"
            view="controls"
            current={view}
            onClick={setView}
          />
        </nav>

        <div className="topbar-right">
          <div className={`live-dot ${wsConnected ? "" : "offline"}`}>
            <span className="dot"></span>
            {wsLabel}
          </div>

          {!DEMO_MODE && <OperatorSession variant="compact" />}

          <div className="clock">{nowUtc()}</div>

          <div
            className="killswitch-btn"
            title={
              DEMO_MODE
                ? "Demo mode: live kill-switch controls are unavailable"
                : "Kill-switch status is managed in Controls"
            }
            aria-label={
              DEMO_MODE
                ? "Global kill switch unavailable in demo mode"
                : "Global kill switch status"
            }
          >
            <PowerIcon />
            {DEMO_MODE ? "Demo · Kill switch unavailable" : "Global kill switch"}
          </div>
        </div>
      </header>

      {view === "live" && (
        <LiveView
          incidents={incidents}
          live={live}
          loading={!loaded}
          onSelect={selectIncident}
        />
      )}

      {view === "detail" && selected && detailLoading && <DetailSkeleton />}

      {view === "detail" && selected && !detailLoading && (
        <DetailView
          incident={selected}
          timeline={timeline}
          proposal={proposal}
          trace={trace}
          live={live}
          now={now}
        />
      )}

      {view === "detail" && !selected && <DetailSkeleton />}

      {view === "postmortem" && <PostMortemView live={live} />}

      {view === "controls" && <ControlsView />}

      <div className="footer">
        {DEMO_MODE
          ? "Aegis · DEMO MODE · illustrative sample data · read-only"
          : !backendUp
            ? "Aegis · sample data · backend offline"
            : wsConnected
              ? "Aegis · live data · ws /topic/incidents + /topic/proposals"
              : "Aegis · live data · websocket reconnecting"}

        {!DEMO_MODE && approvalCount > 0 &&
          ` · ${approvalCount} proposal event${approvalCount === 1 ? "" : "s"} received`}

        {!loaded && " · loading"}
      </div>
    </>
  );
}

function Tab({
  label,
  count,
  view,
  current,
  onClick,
}: {
  label: string;
  count?: number;
  view: View;
  current: View;
  onClick: (v: View) => void;
}) {
  return (
    <div
      className={`tab ${current === view ? "active" : ""}`}
      role="tab"
      tabIndex={0}
      aria-selected={current === view}
      onClick={() => onClick(view)}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          onClick(view);
        }
      }}
    >
      {label}
      {count !== undefined && count > 0 && (
        <span className="count">{count}</span>
      )}
    </div>
  );
}

/**
 * Trace comes from the agent's reasoning (Phase 4). Evidence events are
 * shown as metric steps when a trace is not available.
 */
function extractTrace(
  proposal: Proposal | null,
  events: TimelineEvent[],
): TraceStep[] {
  const raw = proposal?.reasoning?.trace;

  if (Array.isArray(raw)) {
    return raw.filter(
      (t): t is TraceStep =>
        typeof t === "object" &&
        t !== null &&
        typeof (t as TraceStep).tool === "string",
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
