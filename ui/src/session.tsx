import { createContext, ReactNode, useContext, useEffect, useState } from "react";
import { Navigate, useLocation } from "react-router-dom";
import { fetchMe, Me } from "./api";

const SessionContext = createContext<Me | null>(null);

/** The signed-in member. Only valid inside {@link RequireSession}. */
export function useMe(): Me {
  const me = useContext(SessionContext);
  if (!me) throw new Error("useMe outside RequireSession");
  return me;
}

export function useIsAdmin(): boolean {
  return useMe().roles.includes("ADMIN");
}

/**
 * The session cookie is httpOnly, so the only way to know it is valid is to
 * ask. Without a session the user goes to /login, which brings them back to
 * the page (and range) they asked for.
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const [state, setState] = useState<{ status: "checking" } | { status: "in"; me: Me } | { status: "out" }>({
    status: "checking",
  });
  const location = useLocation();

  useEffect(() => {
    let active = true;
    fetchMe()
      .then((me) => active && setState({ status: "in", me }))
      .catch(() => active && setState({ status: "out" }));
    return () => {
      active = false;
    };
  }, []);

  if (state.status === "checking") return <div className="boot" aria-busy="true" />;
  if (state.status === "out") {
    const back = location.pathname + location.search;
    return <Navigate to={`/login?next=${encodeURIComponent(back)}`} replace />;
  }
  return <SessionContext.Provider value={state.me}>{children}</SessionContext.Provider>;
}
