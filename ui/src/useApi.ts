import { DependencyList, useCallback, useEffect, useRef, useState } from "react";
import { ApiError, SESSION_RESTORED } from "./api";

export type ApiState<T> =
  | { status: "loading"; data?: undefined; error?: undefined }
  | { status: "error"; data?: undefined; error: ApiError }
  | { status: "ready"; data: T; error?: undefined };

/**
 * Runs {@code load} whenever {@code deps} change and tracks loading, error and
 * data separately, so a failed request is never shown as empty data. A stale
 * response (deps changed while it was in flight) is dropped.
 *
 * A 401 is not shown as an error: the session gate is asking the user to sign
 * in again, so the view keeps the data it had (or keeps loading) behind the
 * dialog and reloads by itself once the session is restored.
 */
export function useApi<T>(load: () => Promise<T>, deps: DependencyList) {
  const [state, setState] = useState<ApiState<T>>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);
  const loadRef = useRef(load);
  loadRef.current = load;
  const lastReady = useRef<ApiState<T> | null>(null);

  useEffect(() => {
    let active = true;
    setState({ status: "loading" });
    loadRef
      .current()
      .then((data) => {
        if (!active) return;
        const ready: ApiState<T> = { status: "ready", data };
        lastReady.current = ready;
        setState(ready);
      })
      .catch((e: unknown) => {
        if (!active) return;
        const error = e instanceof ApiError ? e : new ApiError(0, String(e));
        if (error.status === 401) {
          setState(lastReady.current ?? { status: "loading" });
          return;
        }
        setState({ status: "error", error });
      });
    return () => {
      active = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, attempt]);

  const retry = useCallback(() => setAttempt((a) => a + 1), []);

  useEffect(() => {
    window.addEventListener(SESSION_RESTORED, retry);
    return () => window.removeEventListener(SESSION_RESTORED, retry);
  }, [retry]);

  return { ...state, retry };
}
