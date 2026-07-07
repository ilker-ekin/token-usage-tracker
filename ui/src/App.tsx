import { Navigate, Route, Routes } from "react-router-dom";
import { getToken } from "./api";
import { DateRangeProvider } from "./dateRange";
import Layout from "./components/Layout";
import Login from "./pages/Login";
import Overview from "./pages/Overview";
import Trends from "./pages/Trends";
import Members from "./pages/Members";
import Projects from "./pages/Projects";
import Teams from "./pages/Teams";

function RequireAuth({ children }: { children: JSX.Element }) {
  return getToken() ? children : <Navigate to="/login" replace />;
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
