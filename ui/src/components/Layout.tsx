import { lazy, Suspense, useState } from "react";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { logout as endSession } from "../api";
import { downloadCsv } from "../csv";
import { PageChromeProvider, useChrome } from "../pageChrome";
import { useIsAdmin, useMe } from "../session";
import RangeControl from "./RangeControl";
import SessionGate from "./SessionGate";

// Only the public demo build has the banner. The flag is a build-time constant,
// so the normal build leaves the import (and the demo module) out.
const DEMO = import.meta.env.VITE_DEMO === "true";
const DemoBanner = DEMO ? lazy(() => import("../demo/DemoBanner")) : null;

const NAV = [
  { to: "/", label: "Overview", end: true },
  { to: "/trends", label: "Trends" },
  { to: "/members", label: "Members" },
  { to: "/projects", label: "Projects" },
  { to: "/teams", label: "Teams" },
];

export default function Layout() {
  return (
    <PageChromeProvider>
      <Shell />
    </PageChromeProvider>
  );
}

function Shell() {
  const me = useMe();
  const isAdmin = useIsAdmin();
  const navigate = useNavigate();
  const { search } = useLocation();

  function logout() {
    endSession()
      .catch(() => undefined)
      .finally(() => navigate("/login"));
  }

  // Links keep the range in the URL, so switching pages keeps the dates
  const links = isAdmin ? [...NAV, { to: "/admin/budgets", label: "Budgets" }] : NAV;

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>TOKEN USAGE</h1>
        <nav>
          {links.map((l) => (
            <NavLink key={l.to} to={{ pathname: l.to, search }} end={l.end}>
              {l.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-footer">
          <div className="who" title={me.email}>
            {me.email}
          </div>
          {!DEMO && (
            <button className="logout" onClick={logout}>
              Log out
            </button>
          )}
        </div>
      </aside>
      <main className="main">
        {DemoBanner && (
          <Suspense fallback={null}>
            <DemoBanner />
          </Suspense>
        )}
        <TopBar />
        <Outlet />
      </main>
      <SessionGate />
    </div>
  );
}

function TopBar() {
  const chrome = useChrome();
  const [exporting, setExporting] = useState(false);

  async function exportCsv() {
    if (!chrome.exportCsv) return;
    setExporting(true);
    try {
      const out = await chrome.exportCsv();
      downloadCsv(out.filename, out.csv);
    } catch (e) {
      window.alert(e instanceof Error ? `Export failed: ${e.message}` : "Export failed.");
    } finally {
      setExporting(false);
    }
  }

  return (
    <div className="topbar">
      <h2>{chrome.title}</h2>
      <div className="controls">
        {!chrome.hideRange && <RangeControl />}
        {chrome.action ?? (
          <button className="btn btn-secondary" disabled={!chrome.exportCsv || exporting} onClick={exportCsv}>
            {exporting ? "Exporting…" : "Export CSV"}
          </button>
        )}
      </div>
    </div>
  );
}
