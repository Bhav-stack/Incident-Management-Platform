import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// Dev server proxies REST and SockJS traffic to incident-svc so the app has no
// CORS concerns and the browser sees a single origin. In prod, the same paths
// are served behind nginx (deploy/nginx.conf).
//
// When the services require an API key, the proxy injects it from the
// environment (AEGIS_API_KEY=... npm run dev) so the key stays out of the
// bundle. Leave it unset when the services run with authentication disabled.
export default defineConfig(({ mode }) => {
  const apiKey = loadEnv(mode, ".", "").AEGIS_API_KEY ?? "";

  return {
    plugins: [react()],
    // sockjs-client references Node's `global`; alias it for the browser.
    define: {
      global: "globalThis",
    },
    server: {
      port: 5173,
      proxy: {
        "/api": {
          target: "http://localhost:8082",
          changeOrigin: true,
          headers: apiKey ? { "X-API-Key": apiKey } : {},
        },
        "/ws": {
          target: "http://localhost:8082",
          ws: true,
          changeOrigin: true,
        },
      },
    },
  };
});
