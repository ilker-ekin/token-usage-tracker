import { NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { logout as endSession } from "../api";
import { useDateRange } from "../dateRange";

const TITLES: Record<string, string> = {
  "/": "Overview",
  "/trends": "Trends",
  "/members": "Members",
  "/projects": "Projects",
  "/teams": "Teams",
};

export default function Layout() {
  const { from, to, setFrom, setTo } = useDateRange();
  const navigate = useNavigate();
  const { pathname } = useLocation();

  function logout() {
    endSession()
      .catch(() => undefined)
      .finally(() => navigate("/login"));
  }

  return (
    <div className="layout">
      <aside className="sidebar">
        <h1>TOKEN USAGE</h1>
        <nav>
          <NavLink to="/" end>Overview</NavLink>
          <NavLink to="/trends">Trends</NavLink>
          <NavLink to="/members">Members</NavLink>
          <NavLink to="/projects">Projects</NavLink>
          <NavLink to="/teams">Teams</NavLink>
        </nav>
        <button className="logout" onClick={logout}>
          Log out
        </button>
      </aside>
      <main className="main">
        <div className="topbar">
          <h2>{TITLES[pathname] ?? ""}</h2>
          <div className="range-picker">
            <span>From</span>
            <input type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
            <span>To</span>
            <input type="date" value={to} min={from} onChange={(e) => setTo(e.target.value)} />
          </div>
        </div>
        <Outlet />
      </main>
    </div>
  );
}
