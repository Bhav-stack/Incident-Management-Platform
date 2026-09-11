import { timeOf } from "../lib/format";
import { actionIconClass } from "../lib/ui";
import { sampleActions } from "../lib/sample";
import { RefreshIcon, XIcon, CheckIcon } from "./Icons";

interface Props {
  live: boolean;
}

// Post-mortems are assembled from resolved incidents and their action
// history. That endpoint arrives with the resolved-incident workflow; until
// then this view renders the approved sample so the design is reviewable.
export default function PostMortemView({ live }: Props) {
  const actions = live ? [] : sampleActions;

  return (
    <section className="view active">
      <div className="card inc-header">
        <div><span className="sev sev3">SEV3</span></div>
        <div className="title">
          <h1>Post-mortem · INC-2037 · auth-service</h1>
          <div className="meta">
            resolved 1h 12m ago · <b>outcome</b> auto-recovery failed, rolled back, human resolved · MTTR 18m
          </div>
        </div>
        <div><span className="status rolledback"><span className="dot"></span>RESOLVED · ROLLED BACK</span></div>
      </div>

      <div className="pm-grid">
        <div className="card pm-section">
          <h3>Root cause &amp; resolution</h3>
          <p><b>Root cause.</b> Connection pool exhaustion caused by a leaked transaction in auth-service v2.2.1, exposed only under peak load.</p>
          <p><b>Agent diagnosis.</b> Correctly identified the pool exhaustion from logs (confidence 0.61, flagged as uncertain because metrics were noisy).</p>
          <p><b>Human resolution.</b> Rolled back to v2.2.0 and re-ran the replay harness with the scenario. Proposal precision improved after adding the <span style={{ fontFamily: "var(--font-mono)", fontSize: 12.5 }}>get_transaction_stats</span> tool.</p>
          <div className="chips">
            <span className="chip">leak · idle_in_transaction</span>
            <span className="chip warn">confidence 0.61 &lt; 0.70</span>
            <span className="chip">escalated per policy</span>
          </div>
        </div>

        <div className="card pm-section">
          <h3>Action history · verify → rollback</h3>
          {actions.length === 0 && (
            <div className="empty-note">
              Action history for resolved incidents appears here once the resolved-incident workflow is wired to the dashboard.
            </div>
          )}
          <div className="action-history">
            {actions.map((a) => (
              <div className={`ah ${actionIconClass(a.verdict)}`} key={a.id}>
                <div className="icon">
                  {a.verdict === "EXECUTED" || a.verdict === "RESOLVED" ? <CheckIcon /> :
                   a.verdict === "VERIFY FAIL" || a.verdict === "FAILED" ? <XIcon /> : <RefreshIcon />}
                </div>
                <div className="desc">
                  {a.detail} <span className="when">action {a.id} · {timeOf(a.createdAt)}</span>
                </div>
                <div className="verdict">{a.verdict}</div>
              </div>
            ))}
          </div>
        </div>

        <div className="card pm-section" style={{ gridColumn: "1 / -1" }}>
          <h3>Agent run summary · AEGIS-2037</h3>
          <div className="mini-stats" style={{ padding: 0, marginBottom: 14 }}>
            <div className="mini-stat"><div className="label">Tool calls</div><div className="value">7</div></div>
            <div className="mini-stat"><div className="label">Peak confidence</div><div className="value">0.61</div></div>
            <div className="mini-stat"><div className="label">Latency</div><div className="value">6.4s</div></div>
            <div className="mini-stat"><div className="label">Tokens · cost</div><div className="value">4.8k · $0.08</div></div>
          </div>
          <p style={{ marginBottom: 0 }}>
            Key takeaway logged to <span style={{ fontFamily: "var(--font-mono)", fontSize: 12.5 }}>lessons_learned</span>: scale actions on auth-service need a transaction-leak check before execution. Rule added to the policy validator: <span style={{ fontFamily: "var(--font-mono)", fontSize: 12.5, color: "var(--color-orange-text)" }}>if logs contain "idle_in_transaction" → force escalation.</span>
          </p>
        </div>
      </div>
      {actions.length === 0 && <p className="sample-note warn" style={{ marginTop: 16 }}>sample view · live post-mortems arrive with the resolved-incident workflow</p>}
    </section>
  );
}