import { FormEvent, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ApiError, login } from "../api";

/** Only same-app paths, so ?next cannot send the user to another site. */
function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const [params] = useSearchParams();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    setBusy(true);
    try {
      await login(email, password);
      navigate(safeNext(params.get("next")), { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        setError("Too many failed attempts. Try again in a few minutes.");
      } else if (err instanceof ApiError && err.status === 401) {
        setError("Invalid email or password.");
      } else {
        setError(err instanceof Error ? err.message : "Sign-in failed.");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-box" onSubmit={submit}>
        <h1>Token Usage Tracker</h1>
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        <label htmlFor="email">Email</label>
        <input id="email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)} required />
        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <button type="submit" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
