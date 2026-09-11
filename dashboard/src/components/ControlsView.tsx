import { useEffect, useState } from "react";
import { api, type KillSwitches, type PolicyState, type ReplayStats } from "../lib/api";

// Live control surface: kill switches and policy toggles are Redis-backed in
// incident-svc and take effect immediately (checked in the propose and
// execute paths). The evaluation harness reads replay scores from the same
// service. Only the agent configuration card is informational.

export default function ControlsView() {
  const [kill, setKill] = useState<KillSwitches | null>(null);
  const [policy, setPolicy] = useState<PolicyState | null>(null);
  const [stats, setStats] = useState<ReplayStats | null>(null);
  const [replayRunning, setReplayRunning] = useState(false);
  const [offline, setOffline] = useState(false);

  const load = () => {
    api
      .killSwitches()
      .then(setKill)
      .then(() => api.policy())
      .then(setPolicy)
      .then(() => api.replayStats())
      .then(setStats)
      .catch(() => setOffline(true));
  };

  useEffect(load, []);

  const toggleKill = async (target: string, killed: boolean) => {
    await api.setKillSwitch(target, killed);
    setKill(await api.killSwitches());
  };

  const toggleFlag = async (flag: "shadow-mode" | "auto-approve-low", enabled: boolean) => {
    await api.setPolicyFlag(flag, enabled);
    setPolicy(await api.policy());
  };

  const runReplay = async () => {
    setReplayRunning(true);
    try {
      setStats(await api.runReplay());
    } finally {
      setReplayRunning(false);
    }
  };

  const fpRate = stats && stats.total > 0 ? (stats.fp / stats.total) * 100 : 0;

  return (
    <section className="view active">
      <div className="ctrl-grid">
        <div>
          <div className="card">
            <div className="card-head"><h2>Kill switches</h2><div className="hint">redis · checked before propose &amp; execute</div></div>
            {kill === null && <EmptyNote text={offline ? "incident-svc unreachable" : "loading"} />}
            {kill && (
              <>
                <SwitchRow name="Global auto-recovery" desc="Rejects all proposals · no actions execute"
                  checked={kill.global} onChange={(v) => void toggleKill("global", v)} />
                {kill.services.map((s) => (
                  <SwitchRow key={s.service} name={s.service} desc="Per-service override"
                    checked={s.killed} onChange={(v) => void toggleKill(s.service, v)} />
                ))}
              </>
            )}
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head"><h2>Autonomy policy</h2><div className="hint">per-risk-tier gates</div></div>
            {policy === null && <EmptyNote text={offline ? "incident-svc unreachable" : "loading"} />}
            {policy && (
              <>
                <SwitchRow name="Shadow mode" desc="Agent proposes · nothing executes · proposals scored offline"
                  checked={policy.shadowMode} onChange={(v) => void toggleFlag("shadow-mode", v)} />
                <SwitchRow name="LOW-risk auto-approve" desc="LOW risk + confidence and evidence minimums still apply"
                  checked={policy.autoApproveLow} onChange={(v) => void toggleFlag("auto-approve-low", v)} />
              </>
            )}
          </div>
        </div>

        <div>
          <div className="card">
            <div className="card-head"><h2>Risk policy matrix</h2><div className="hint">policy validator · config-driven</div></div>
            <table className="policy">
              <thead>
                <tr><th>Action</th><th>Default gate</th><th>Conf. min</th></tr>
              </thead>
              <tbody>
                {policy && Object.entries(policy.confidenceMinimums).map(([action, min]) => (
                  <tr key={action}>
                    <td>{action}</td>
                    <td className="gate">{action === "rollback_deploy" || action === "kill_instance" ? "human · 2-person prod" : action === "clear_cache" ? "auto (post-shadow)" : "human approval"}</td>
                    <td>{min.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {policy && <div style={{ padding: "4px 20px 12px" }}><span className="sample-note">min evidence {policy.minEvidenceSignals} signals per proposal</span></div>}
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head"><h2>Evaluation harness</h2><div className="hint">replay · ground truth · shadow mode</div></div>
            <div style={{ padding: "14px 20px 4px" }}>
              <button className="btn approve" style={{ width: "100%" }} disabled={replayRunning} onClick={() => void runReplay()}>
                {replayRunning ? "Running replay…" : "Run replay"}
              </button>
            </div>
            <div className="mini-stats">
              <div className="mini-stat"><div className="label">Precision</div><div className="value" style={{ color: "var(--color-green)" }}>{stats ? `${(stats.precision * 100).toFixed(1)}%` : "n/a"}</div></div>
              <div className="mini-stat"><div className="label">Recall</div><div className="value" style={{ color: "var(--color-green)" }}>{stats ? `${(stats.recall * 100).toFixed(1)}%` : "n/a"}</div></div>
              <div className="mini-stat"><div className="label">False-positive rate</div><div className="value" style={{ color: "var(--color-orange-text)" }}>{stats ? `${fpRate.toFixed(1)}%` : "n/a"}</div></div>
              <div className="mini-stat"><div className="label">Scenarios</div><div className="value">{stats ? stats.total : "n/a"}</div></div>
            </div>
            <div style={{ padding: "0 20px 14px" }}>
              {stats === null && <span className="sample-note">no replay runs yet · run the harness above</span>}
              {stats && <span className="sample-note">{stats.correct} of {stats.total} correct · tp {stats.tp} · fp {stats.fp} · fn {stats.fn}</span>}
            </div>
          </div>

          <div className="card" style={{ marginTop: 20 }}>
            <div className="card-head"><h2>Agent configuration</h2><div className="hint">incident-svc · env-driven</div></div>
            <div style={{ padding: "8px 20px" }}>
              <div className="kv"><span className="k">Proposer mode</span><span className="v">rule / agent (env)</span></div>
              <div className="kv"><span className="k">LLM for dev</span><span className="v">fake (CI) or API</span></div>
              <div className="kv"><span className="k">Max tool calls / incident</span><span className="v">8</span></div>
              <div className="kv"><span className="k">Loop implementation</span><span className="v">hand-rolled · tool_calls</span></div>
            </div>
          </div>
        </div>
      </div>
      {offline && <p className="sample-note warn" style={{ marginTop: 16 }}>incident-svc unreachable · controls shown are the approved design only</p>}
    </section>
  );
}

function SwitchRow({ name, desc, checked, onChange }: {
  name: string; desc: string; checked: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <div className="switch-row">
      <div><div className="name">{name}</div><div className="desc">{desc}</div></div>
      <label className="switch">
        <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
        <span className="slider"></span>
      </label>
    </div>
  );
}

function EmptyNote({ text }: { text: string }) {
  return <div className="empty-note">{text}</div>;
}