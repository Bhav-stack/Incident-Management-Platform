import { useEffect, useState } from "react";
import {
  api,
  type KillSwitches,
  type PolicyState,
  type ReplayStats,
} from "../lib/api";
import { RowsSkeleton } from "./Skeleton";

// Public portfolio demo is read-only. Set VITE_DEMO_MODE=false
// in a local environment when you want to use the real backend.
const DEMO_MODE = import.meta.env.VITE_DEMO_MODE !== "false";

const DEMO_POLICY_ROWS = [
  { action: "restart_instance", gate: "Human approval", confidence: "0.70" },
  { action: "scale_replicas", gate: "Human approval", confidence: "0.75" },
  { action: "rollback_deploy", gate: "Human approval", confidence: "0.85" },
  { action: "clear_cache", gate: "LOW risk + thresholds", confidence: "0.60" },
];

export default function ControlsView() {
  const [kill, setKill] = useState<KillSwitches | null>(null);
  const [policy, setPolicy] = useState<PolicyState | null>(null);
  const [stats, setStats] = useState<ReplayStats | null>(null);
  const [replayRunning, setReplayRunning] = useState(false);
  const [offline, setOffline] = useState(false);

  useEffect(() => {
    if (DEMO_MODE) {
      setOffline(true);
      return;
    }

    api
      .killSwitches()
      .then(setKill)
      .then(() => api.policy())
      .then(setPolicy)
      .then(() => api.replayStats())
      .then(setStats)
      .catch(() => setOffline(true));
  }, []);

  const toggleKill = async (target: string, killed: boolean) => {
    if (DEMO_MODE) return;
    try {
      await api.setKillSwitch(target, killed);
      setKill(await api.killSwitches());
    } catch {
      setOffline(true);
    }
  };

  const toggleFlag = async (
    flag: "shadow-mode" | "auto-approve-low",
    enabled: boolean,
  ) => {
    if (DEMO_MODE) return;
    try {
      await api.setPolicyFlag(flag, enabled);
      setPolicy(await api.policy());
    } catch {
      setOffline(true);
    }
  };

  const runReplay = async () => {
    if (DEMO_MODE || replayRunning) return;
    setReplayRunning(true);
    try {
      setStats(await api.runReplay());
    } catch {
      setOffline(true);
    } finally {
      setReplayRunning(false);
    }
  };

  const fpRate =
    stats && stats.total > 0 ? (stats.fp / stats.total) * 100 : 0;

  return (
    <section className="view active">
      <div className="sample-note warn" style={{ marginBottom: 16 }}>
        {DEMO_MODE
          ? "DEMO MODE · Read-only interface · No live controls or replay execution"
          : offline
            ? "BACKEND OFFLINE · Live controls are unavailable"
            : "LIVE MODE · Controls require a connected, authenticated backend"}
      </div>

      <div className="ctrl-grid">
        <div>
          <div className="card">
            <div className="card-head">
              <h2>Kill switches</h2>
              <div className="hint">
                {DEMO_MODE ? "read-only demo" : "redis · checked before propose & execute"}
              </div>
            </div>

            {DEMO_MODE || (offline && kill === null) ? (
              <EmptyNote text="Illustrative only · no live kill-switch state" />
            ) : kill === null ? (
              <RowsSkeleton rows={3} label="Loading kill switches" />
            ) : (
              <>
                <SwitchRow
                  name="Global auto-recovery"
                  desc="Rejects all proposals · no actions execute"
                  checked={kill.global}
                  disabled={offline}
                  onChange={(v) => void toggleKill("global", v)}
                />
                {kill.services.map((s) => (
                  <SwitchRow
                    key={s.service}
                    name={s.service}
                    desc="Per-service override"
                    checked={s.killed}
                    disabled={offline}
                    onChange={(v) => void toggleKill(s.service, v)}
                  />
                ))}
              </>
            )}
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head">
              <h2>Autonomy policy</h2>
              <div className="hint">per-risk-tier gates</div>
            </div>

            {DEMO_MODE || (offline && policy === null) ? (
              <EmptyNote text="Illustrative policy · live configuration unavailable" />
            ) : policy === null ? (
              <RowsSkeleton rows={2} label="Loading autonomy policy" />
            ) : (
              <>
                <SwitchRow
                  name="Shadow mode"
                  desc="Agent proposes · nothing executes"
                  checked={policy.shadowMode}
                  disabled={offline}
                  onChange={(v) => void toggleFlag("shadow-mode", v)}
                />
                <SwitchRow
                  name="LOW-risk auto-approve"
                  desc="LOW risk + confidence and evidence minimums still apply"
                  checked={policy.autoApproveLow}
                  disabled={offline}
                  onChange={(v) => void toggleFlag("auto-approve-low", v)}
                />
              </>
            )}
          </div>
        </div>

        <div>
          <div className="card">
            <div className="card-head">
              <h2>Risk policy matrix</h2>
              <div className="hint">
                {DEMO_MODE ? "illustrative example · not backend configuration" : "policy validator · config-driven"}
              </div>
            </div>

            <table className="policy">
              <thead>
                <tr>
                  <th>Action</th>
                  <th>Default gate</th>
                  <th>Conf. min</th>
                </tr>
              </thead>
              <tbody>
                {DEMO_MODE
                  ? DEMO_POLICY_ROWS.map((row) => (
                      <tr key={row.action}>
                        <td>{row.action}</td>
                        <td className="gate">{row.gate}</td>
                        <td>{row.confidence}</td>
                      </tr>
                    ))
                  : policy &&
                    Object.entries(policy.confidenceMinimums).map(([action, min]) => (
                      <tr key={action}>
                        <td>{action}</td>
                        <td className="gate">
                          {policy.autoApproveLow && action === "clear_cache"
                            ? "auto when LOW + thresholds clear"
                            : "human approval"}
                        </td>
                        <td>{min.toFixed(2)}</td>
                      </tr>
                    ))}
              </tbody>
            </table>

            {DEMO_MODE ? (
              <div style={{ padding: "4px 20px 12px" }}>
                <span className="sample-note">
                  Example thresholds for demonstration only; actual gates are enforced by the backend.
                </span>
              </div>
            ) : policy ? (
              <div style={{ padding: "4px 20px 12px" }}>
                <span className="sample-note">
                  Min evidence {policy.minEvidenceSignals} signals per proposal
                  {policy.twoPersonLevels.length > 0 &&
                    ` · two-person approval for ${policy.twoPersonLevels.join(", ")} risk`}
                </span>
              </div>
            ) : null}
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head">
              <h2>Evaluation harness</h2>
              <div className="hint">replay · ground truth · shadow mode</div>
            </div>

            <div style={{ padding: "14px 20px 4px" }}>
              <button
                className="btn approve"
                style={{ width: "100%" }}
                disabled={DEMO_MODE || offline || replayRunning}
                onClick={() => void runReplay()}
              >
                {DEMO_MODE
                  ? "Replay disabled in demo"
                  : replayRunning
                    ? "Running replay…"
                    : "Run replay"}
              </button>
            </div>

            <div className="mini-stats">
              <div className="mini-stat">
                <div className="label">Precision</div>
                <div className="value" style={{ color: "var(--color-green)" }}>
                  {stats ? `${(stats.precision * 100).toFixed(1)}%` : "n/a"}
                </div>
              </div>
              <div className="mini-stat">
                <div className="label">Recall</div>
                <div className="value" style={{ color: "var(--color-green)" }}>
                  {stats ? `${(stats.recall * 100).toFixed(1)}%` : "n/a"}
                </div>
              </div>
              <div className="mini-stat">
                <div className="label">False-positive rate</div>
                <div className="value" style={{ color: "var(--color-orange-text)" }}>
                  {stats ? `${fpRate.toFixed(1)}%` : "n/a"}
                </div>
              </div>
              <div className="mini-stat">
                <div className="label">Scenarios</div>
                <div className="value">{stats ? stats.total : "n/a"}</div>
              </div>
            </div>

            <div style={{ padding: "0 20px 14px" }}>
              {DEMO_MODE ? (
                <span className="sample-note">
                  No replay executed · live evaluation metrics are unavailable in this demo
                </span>
              ) : stats ? (
                <span className="sample-note">
                  {stats.correct} of {stats.total} correct · tp {stats.tp} · fp {stats.fp} · fn {stats.fn}
                </span>
              ) : (
                <span className="sample-note">
                  No replay results available yet
                </span>
              )}
            </div>
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head">
              <h2>Agent configuration</h2>
              <div className="hint">incident-svc · env-driven</div>
            </div>
            <div style={{ padding: "8px 20px" }}>
              <div className="kv">
                <span className="k">Proposer mode</span>
                <span className="v">rule / agent (env)</span>
              </div>
              <div className="kv">
                <span className="k">LLM for dev</span>
                <span className="v">fake (CI) or API</span>
              </div>
              <div className="kv">
                <span className="k">Max tool calls / incident</span>
                <span className="v">8</span>
              </div>
              <div className="kv">
                <span className="k">Loop implementation</span>
                <span className="v">hand-rolled · tool_calls</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}

function SwitchRow({
  name,
  desc,
  checked,
  disabled = false,
  onChange,
}: {
  name: string;
  desc: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="switch-row">
      <div>
        <div className="name">{name}</div>
        <div className="desc">{desc}</div>
      </div>
      <label className="switch">
        <input
          type="checkbox"
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
        />
        <span className="slider"></span>
      </label>
    </div>
  );
}

function EmptyNote({ text }: { text: string }) {
  return <div className="empty-note">{text}</div>;
}
