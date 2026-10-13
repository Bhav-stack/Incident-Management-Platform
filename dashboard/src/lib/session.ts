// The signed operator token the browser holds while a person is acting.
//
// Signing an approval used to be thing the proxy did for you: the browser sent
// no credential of its own and the identity was the shared client key, which is
// deliberately not a person. To approve as a human — and so to complete a
// two-person approval without leaving the dashboard — the browser signs in
// with an operator key once and keeps the short-lived token the control plane
// signs back.
//
// The token lives in sessionStorage, not localStorage: it is a bearer
// credential, it expires in minutes, and it should not outlive the tab it was
// obtained in or be shared with other tabs. Nothing else is persisted.

import { apiUrl } from "./config";

export interface OperatorSession {
  token: string;
  subject: string;
  attributable: boolean;
  issuer: string;
  /** Epoch milliseconds, derived from the server's expiresInSeconds. */
  expiresAt: number;
}

const STORAGE_KEY = "aegis.operator.session";

const listeners = new Set<() => void>();
let current: OperatorSession | null = read();
let expiryTimer: number | undefined;

function read(): OperatorSession | null {
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as OperatorSession;
    if (typeof parsed.token !== "string" || typeof parsed.expiresAt !== "number") return null;
    if (parsed.expiresAt <= Date.now()) {
      window.sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return parsed;
  } catch {
    // A corrupt or unreadable store must not take the dashboard down.
    return null;
  }
}

function store(session: OperatorSession | null) {
  current = session;
  try {
    if (session) window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    else window.sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // Private mode can refuse storage; the in-memory copy still works.
  }
  window.clearTimeout(expiryTimer);
  if (session) {
    // Clear it when it stops working, so the UI cannot keep offering a
    // signature the control plane will refuse.
    expiryTimer = window.setTimeout(
      () => store(null),
      Math.max(0, session.expiresAt - Date.now()),
    );
  }
  listeners.forEach((listener) => listener());
}

/** Stable snapshot, as useSyncExternalStore requires. */
export function getSession(): OperatorSession | null {
  return current;
}

export function subscribeSession(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** The token to present, or null when there is none worth presenting. */
export function operatorToken(): string | null {
  return current && current.expiresAt > Date.now() ? current.token : null;
}

export function signOut() {
  store(null);
}

export function remainingSeconds(): number {
  if (!current) return 0;
  return Math.max(0, Math.round((current.expiresAt - Date.now()) / 1000));
}

/**
 * Exchanges an operator key for a signed token.
 *
 * The key travels in its own header rather than the shared API-key header, so
 * it cannot be confused with the client credential the dev proxy attaches, and
 * so it never ends up in a request body where proxies and logs tend to keep a
 * copy of it.
 */
export async function signIn(operatorKey: string): Promise<OperatorSession> {
  const res = await fetch(apiUrl("/api/auth/session"), {
    method: "POST",
    headers: { "X-Operator-Key": operatorKey },
  });
  if (!res.ok) throw new Error(await signInError(res));

  const body = (await res.json()) as {
    token: string;
    subject: string;
    attributable: boolean;
    issuer?: string;
    expiresInSeconds: number;
  };
  const session: OperatorSession = {
    token: body.token,
    subject: body.subject,
    attributable: body.attributable,
    issuer: body.issuer ?? "",
    expiresAt: Date.now() + Math.max(1, body.expiresInSeconds) * 1000,
  };
  store(session);
  return session;
}

async function signInError(res: Response): Promise<string> {
  switch (res.status) {
    case 401:
      return "That key was not recognised. Check it and try again.";
    case 403:
      return "That is the shared client credential, not an operator key.";
    case 501:
      return "This deployment does not issue tokens itself. Sign in with the configured identity provider.";
    default:
      return `Sign-in failed (${res.status}).`;
  }
}
