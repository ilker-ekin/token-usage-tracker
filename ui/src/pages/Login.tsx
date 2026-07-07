import { FormEvent, useState } from "react";
import { useNavigate } from "react-router-dom";
import { login, setToken } from "../api";

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const navigate = useNavigate();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError("");
    try {
      const res = await login(email, password);
      setToken(res.token);
      navigate("/");
    } catch {
      setError("Invalid email or password.");
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-box" onSubmit={submit}>
        <h1>Token Usage Tracker</h1>
        {error && <div className="error">{error}</div>}
        <label htmlFor="email">Email</label>
        <input
          id="email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          required
        />
        <label htmlFor="password">Password</label>
        <input
          id="password"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          required
        />
        <button type="submit">Sign in</button>
      </form>
    </div>
  );
}
