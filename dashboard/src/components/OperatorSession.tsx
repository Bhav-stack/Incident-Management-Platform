import { useEffect, useState, useSyncExternalStore } from "react";
import {
  getSession,
  remainingSeconds,
  signIn,
  signOut,
  subscribeSession,
} from "../lib/session";

/**
 * Signing an approval as a person.
 *
 * Two appearances, one state: the compact form is the top-bar status, the
 * panel form sits in the proposal card where the signature happens. The panel
 * is deliberately explicit about what is going on, because "sign in" here
 * means something narrower than it usually does: it is not an app login, it is
 * the operator supplying the credential that a decision will be recorded
 * against.
 */
export default function OperatorSession({ variant }: { variant: "compact" | "panel" }) {
  const session = useSyncExternalStore(subscribeSession, getSession, getSession);
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [remaining, setRemaining] = useState(() => remainingSeconds());

  // The countdown is the honest version of "you are signed in": it shows how
  // long the signature will keep being accepted.
  useEffect(() => {
    if (!session) return;
    setRemaining(remainingSeconds());
    const t = setInterval(() => setRemaining(remainingSeconds()), 1000);
    return () => clearInterval(t);
  }, [session]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!key.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await signIn(key.trim());
      setKey("");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sign-in failed.");
    } finally {
      setBusy(false);
    }
  };

  const leave = () => {
    signOut();
    setError(null);
    setKey("");
  };

  if (variant === "compact") {
    if (!session) return null;
    return (
      <div className="operator-chip" title={`Token issued by ${session.issuer}`}>
        <span>operator</span>
        <span className="who">{session.subject}</span>
        <a onClick={leave} role="button" tabIndex={0}
          onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); leave(); } }}>
          sign out
        </a>
      </div>
    );
  }

  if (session) {
    const minutes = Math.floor(remaining / 60);
    const seconds = remaining % 60;
    return (
      <div className="operator-panel">
        <div className="lbl">Signing as</div>
        <div className="operator-who">
          <span className="who">{session.subject}</span>
          <span className="ttl">
            {minutes}:{String(seconds).padStart(2, "0")} left
          </span>
        </div>
        <div className="note">
          The approval records this name{session.attributable ? "" : " and will not count towards a two-person rule"}.
          {" "}
          <a onClick={leave} role="button" tabIndex={0}
            onKeyDown={(e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); leave(); } }}>
            sign out
          </a>
        </div>
      </div>
    );
  }

  return (
    <div className="operator-panel">
      <div className="lbl">Sign in to approve as yourself</div>
      <form onSubmit={submit}>
        <input
          type="password"
          value={key}
          autoComplete="off"
          spellCheck={false}
          aria-label="Operator key"
          placeholder="operator key"
          onChange={(e) => setKey(e.target.value)}
          disabled={busy}
        />
        <button className="operator-btn" type="submit" disabled={busy || !key.trim()}>
          {busy ? "Signing in" : "Sign in"}
        </button>
      </form>
      {error && <div className="err">{error}</div>}
      <div className="note">
        A two-person approval needs two operators, which means signing out and
        back in as the second one. The signed-in identity is not editable: the
        control plane reads it from the token, never from the request.
      </div>
    </div>
  );
}
