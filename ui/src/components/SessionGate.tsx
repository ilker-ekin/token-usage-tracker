import { FormEvent, useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, login, logout, SESSION_EXPIRED, SESSION_RESTORED } from "../api";
import { useMe } from "../session";
import Dialog from "./Dialog";
import { Chip } from "./ui";

/**
 * Replaces the old hard redirect to /login. When any request finds the
 * session gone, the page stays mounted with its range and filters, and this
 * dialog signs the same member in again in place. Open views then reload.
 */
export default function SessionGate() {
  const me = useMe();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const onExpired = () => setOpen(true);
    window.addEventListener(SESSION_EXPIRED, onExpired);
    return () => window.removeEventListener(SESSION_EXPIRED, onExpired);
  }, []);

  if (!open) return null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await login(me.email, password);
      setOpen(false);
      setPassword("");
      window.dispatchEvent(new Event(SESSION_RESTORED));
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        const mins = err.retryAfterSeconds ? Math.ceil(err.retryAfterSeconds / 60) : 15;
        setError(`Too many attempts. Try again in about ${mins} minute${mins === 1 ? "" : "s"}.`);
      } else if (err instanceof ApiError && err.status === 401) {
        setError("That password is not right.");
      } else {
        setError(err instanceof Error ? err.message : "Sign-in failed.");
      }
    } finally {
      setBusy(false);
    }
  }

  async function switchAccount() {
    await logout().catch(() => undefined);
    navigate("/login", { replace: true });
    window.location.reload();
  }

  return (
    <Dialog title={<SessionTitle />} dismissable={false} width={420}>
      <p className="muted">Your session has ended. Sign in again to pick up where you left off.</p>
      <form className="form" onSubmit={submit}>
        <label>
          Email
          <input value={me.email} readOnly className="readonly" />
        </label>
        <label>
          Password
          <input
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </label>
        {error && <div className="field-error" role="alert">{error}</div>}
        <div className="row space-between">
          <button type="button" className="btn btn-ghost" onClick={switchAccount}>
            Use another account
          </button>
          <button type="submit" className="btn btn-primary" disabled={busy || !password}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function SessionTitle() {
  return (
    <div className="stack gap-8">
      <div>
        <Chip tone="warning">Session expired</Chip>
      </div>
      <h2>Sign in again to continue</h2>
    </div>
  );
}
