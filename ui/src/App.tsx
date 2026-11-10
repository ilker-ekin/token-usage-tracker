import { Navigate, Route, Routes } from "react-router-dom";
import Layout from "./components/Layout";
import Budgets from "./pages/Budgets";
import Login from "./pages/Login";
import Members from "./pages/Members";
import Overview from "./pages/Overview";
import ProjectDetail from "./pages/ProjectDetail";
import Projects from "./pages/Projects";
import Teams from "./pages/Teams";
import Trends from "./pages/Trends";
import { RequireSession } from "./session";

/*
 * SPA routes must not start with an API prefix that nginx and the Vite dev
 * server proxy (/auth, /analytics, /usage, /model-pricing, /budgets), or a
 * reload of that page would return API JSON. That is why Budgets lives at
 * /admin/budgets.
 */
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireSession>
            <Layout />
          </RequireSession>
        }
      >
        <Route index element={<Overview />} />
        <Route path="trends" element={<Trends />} />
        <Route path="members" element={<Members />} />
        <Route path="projects" element={<Projects />} />
        <Route path="projects/:projectId" element={<ProjectDetail />} />
        <Route path="teams" element={<Teams />} />
        <Route path="admin/budgets" element={<Budgets />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
