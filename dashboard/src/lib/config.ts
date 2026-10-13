// Where the API lives.
//
// Two deployment shapes, one rule: if VITE_API_BASE_URL is set, the dashboard is
// being served from somewhere other than the API and every request is absolute.
// If it is empty, the dashboard and the API share an origin (the Vite dev proxy,
// or Caddy in the containerised stack) and relative paths are correct.
//
// Keeping the relative default matters. A same-origin request is not a CORS
// request at all, so nothing about the dev or self-hosted path depends on the
// CORS configuration being right; only the hosted deployment does.

const rawBase = (import.meta.env.VITE_API_BASE_URL ?? "").trim();

/** Origin of the API, with no trailing slash. Empty when same-origin. */
export const API_BASE = rawBase.replace(/\/+$/, "");

/** An absolute URL for an API path, or the path itself when same-origin. */
export function apiUrl(path: string): string {
  return `${API_BASE}${path}`;
}

/**
 * The SockJS endpoint.
 *
 * SockJS takes an http(s) URL and derives the WebSocket scheme itself, so the
 * configured value is an https URL, not a wss one. Passing a websocket URL here
 * is a mistake worth naming: SockJS would try to use it as an XHR base.
 */
export function wsUrl(): string {
  const configured = (import.meta.env.VITE_WS_URL ?? "").trim();
  if (configured) return configured.replace(/\/+$/, "");
  if (!API_BASE) return "/ws";
  return `${API_BASE}/ws`;
}

/** True when the dashboard was built to talk to a remote API. */
export const isRemoteApi = API_BASE.length > 0;
