import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev-server proxy so the SPA and the API share an origin locally.
export default defineConfig({
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
});
