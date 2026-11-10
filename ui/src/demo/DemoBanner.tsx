import { useState } from "react";
import { SESSION_EXPIRED, SESSION_RESTORED } from "../api";
import { demoSettings, getRole, resetDemoState, setRole, type DemoRole } from "./handler";

const REPO = "https://github.com/ilker-ekin/token-usage-tracker";

/**
 * Shown on every page of the demo build. Says what the data is, links to the
 * real stack, and offers a few switches so a visitor can see states that are
 * hard to reach in a demo: the member view, a session that has ended, and a
 * failing server.
 */
export default function DemoBanner() {
  const [role, setRoleState] = useState<DemoRole>(getRole());
  const [failing, setFailing] = useState(demoSettings.failRequests);

  function chooseRole(next: DemoRole) {
    setRole(next);
    setRoleState(next);
    // The session is read once at start-up, so a reload is the simple way to apply it
    window.location.reload();
  }

  function toggleFailing(on: boolean) {
    demoSettings.failRequests = on;
    setFailing(on);
    // Open views reload their data, so the change shows at once
    window.dispatchEvent(new Event(SESSION_RESTORED));
  }

  function reset() {
    resetDemoState();
    window.location.reload();
  }

  return (
    <div className="demo-banner" role="note">
      <p>
        <strong>Demo.</strong> Synthetic data that stays in your browser: nothing is sent anywhere, and no real
        accounts or webhooks are involved. Not affiliated with Anthropic, OpenAI or LiteLLM.{" "}
        <a href={`${REPO}#quick-start-docker-only`} target="_blank" rel="noreferrer">
          Run the real thing
        </a>
        .
      </p>
      <details className="demo-menu">
        <summary>Demo options</summary>
        <div className="demo-menu-panel">
          <fieldset>
            <legend>View as</legend>
            <label>
              <input type="radio" name="demo-role" checked={role === "admin"} onChange={() => chooseRole("admin")} /> Admin
              (sees Budgets)
            </label>
            <label>
              <input type="radio" name="demo-role" checked={role === "member"} onChange={() => chooseRole("member")} />{" "}
              Member
            </label>
          </fieldset>
          <label>
            <input type="checkbox" checked={failing} onChange={(e) => toggleFailing(e.target.checked)} /> Make the
            server fail (shows the error and retry state)
          </label>
          <button type="button" className="btn btn-ghost" onClick={() => window.dispatchEvent(new Event(SESSION_EXPIRED))}>
            Show the session-ended dialog (any password works)
          </button>
          <button type="button" className="btn btn-ghost" onClick={reset}>
            Reset demo data
          </button>
          <p className="hint">Pick a date range before the last 90 days to see the empty state.</p>
        </div>
      </details>
    </div>
  );
}
