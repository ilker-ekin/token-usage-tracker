import { useEffect, useState } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { fetchMe } from "./api";
import { DateRangeProvider } from "./dateRange";
import Layout from "./components/Layout";
import Login from "./pages/Login";
import Overview from "./pages/Overview";
import Trends from "./pages/Trends";
import Members from "./pages/Members";
import Projects from "./pages/Projects";
import Teams from "./pages/Teams";

/** The session cookie is httpOnly, so the only way to know it is valid is to ask. */
function RequireAuth({ children }: { children: JSX.Element }) {
  const [state, setState] = useState<"checking" | "in" | "out">("checking");
  useEffect(() => {
    let active = true;
    fetchMe()
      .then(() => active && setState("in"))
      .catch(() => active && setState("out"));
    return () => {
      active = false;
    };
  }, []);
  if (state === "checking") return null;
  return state === "in" ? children : <Navigate to="/login" replace />;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <DateRangeProvider>
              <Layout />
            </DateRangeProvider>
          </RequireAuth>
        }
      >
        <Route index element={<Overview />} />
        <Route path="trends" element={<Trends />} />
        <Route path="members" element={<Members />} />
        <Route path="projects" element={<Projects />} />
        <Route path="teams" element={<Teams />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
