import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter, HashRouter } from "react-router-dom";
import App from "./App";
import "./index.css";

// GitHub Pages has no fallback to index.html for client-side routes, so the
// demo build keeps its routes in the URL hash.
const Router = import.meta.env.VITE_DEMO === "true" ? HashRouter : BrowserRouter;

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <Router>
      <App />
    </Router>
  </StrictMode>,
);
