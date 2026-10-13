/// <reference types="vite/client" />

// The dashboard is served from two different places, and they need different
// answers to "where is the API?":
//
//   * local development and the containerised deployment serve it from the same
//     origin as the API, behind the Vite proxy or Caddy, so relative paths work
//     and CORS is never involved;
//   * a hosted deployment (Vercel) serves the bundle from its own origin, so
//     the API is somewhere else entirely.
//
// These are the only two variables that differ between those cases. Both are
// baked in at build time by Vite, which is why they are read here rather than
// fetched at runtime: the bundle cannot be told where its API is after it was
// built, so a deployment must be built with the right values.
interface ImportMetaEnv {
  /** Absolute API origin, e.g. https://aegis-demo.duckdns.org. Empty means same-origin. */
  readonly VITE_API_BASE_URL?: string;
  /** Absolute SockJS endpoint, e.g. https://aegis-demo.duckdns.org/ws. Defaults to the API origin. */
  readonly VITE_WS_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
