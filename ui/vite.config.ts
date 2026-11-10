import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev-server proxy so the SPA and the API share an origin locally.
//
// `--mode demo` builds the static demo for GitHub Pages: it is served from
// /token-usage-tracker/, written to dist-demo, and has no backend.
export default defineConfig(({ mode }) => ({
  base: mode === "demo" ? "/token-usage-tracker/" : "/",
  build: { outDir: mode === "demo" ? "dist-demo" : "dist" },
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/auth": "http://localhost:8080",
      "/analytics": "http://localhost:8080",
      "/usage": "http://localhost:8080",
      "/model-pricing": "http://localhost:8080",
      "/budgets": "http://localhost:8080",
    },
  },
}));
