import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server proxies REST and SockJS traffic to incident-svc so the app has
// no CORS concerns and the browser sees a single origin. In prod, the same
// paths are served behind the API gateway.
export default defineConfig({
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
      },
      "/ws": {
        target: "http://localhost:8082",
        ws: true,
        changeOrigin: true,
      },
    },
  },
});