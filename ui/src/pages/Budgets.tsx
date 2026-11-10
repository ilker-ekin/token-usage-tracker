import { FormEvent, useState } from "react";
import { Navigate } from "react-router-dom";
import {
  ApiError,
  Budget,
  BudgetAlert,
  BudgetInput,
  BudgetScope,
  createBudget,
  deleteBudget,
  Directory,
  fetchBudgetAlerts,
  fetchBudgets,
  fetchDirectory,
  updateBudget,
} from "../api";
import { fmtPct, fmtUsd } from "../format";
import { usePageChrome } from "../pageChrome";
import { useIsAdmin } from "../session";
import { useApi } from "../useApi";
import Dialog from "../components/Dialog";
import KpiCard from "../components/KpiCard";
import { budgetRatio } from "../budget";
import { Bar, Chip, ErrorState, Panel, SkeletonLines } from "../components/ui";

type Editing = { mode: "create" } | { mode: "edit"; budget: Budget };

/** Admin only, like the API. Budgets are monthly (UTC), so there is no range control. */
export default function Budgets() {
  const isAdmin = useIsAdmin();
  if (!isAdmin) return <Navigate to="/" replace />;
  return <BudgetsPage />;
}

function BudgetsPage() {
  const [editing, setEditing] = useState<Editing | null>(null);
  const data = useApi(async () => {
    const [budgets, directory] = await Promise.all([fetchBudgets(), fetchDirectory()]);
    const alerts = await Promise.all(budgets.map((b) => fetchBudgetAlerts(b.id)));
    return { budgets, directory, alerts: new Map(budgets.map((b, i) => [b.id, alerts[i]])) };
  }, []);

  usePageChrome(
    {
      title: "Budgets",
      hideRange: true,
      action: (
        <button className="btn btn-primary" onClick={() => setEditing({ mode: "create" })}>
          New budget
        </button>
      ),
    },
    [],
  );

  const month = new Date().toLocaleDateString("en-US", { month: "long", year: "numeric", timeZone: "UTC" });

  return (
    <div className="page">
      <div className="cards">
        {data.status === "ready" ? (
          <SummaryCards budgets={data.data.budgets} alerts={data.data.alerts} nameOf={(b) => scopeName(b, data.data.directory)} />
        ) : (
          ["BUDGETS", "OVER LIMIT", "ABOVE 80%", "ALERTS SENT"].map((l) => (
            <KpiCard key={l} label={l} loading={data.status === "loading"} failed={data.status === "error"} />
          ))
        )}
      </div>

      <Panel title="All budgets" right={<span className="muted small">{month} · resets on the 1st (UTC)</span>} className="grow">
        {data.status === "loading" && <SkeletonLines lines={5} />}
        {data.status === "error" && <ErrorState error={data.error} onRetry={data.retry} title="Could not load budgets" />}
        {data.status === "ready" &&
          (data.data.budgets.length === 0 ? (
            <div className="empty">
              No budgets yet. Create one to get a webhook alert at 80% and 100% of a monthly limit.
            </div>
          ) : (
            <div className="budget-list">
              {data.data.budgets.map((b) => (
                <BudgetRow
                  key={b.id}
                  budget={b}
                  name={scopeName(b, data.data.directory)}
                  alerts={data.data.alerts.get(b.id) ?? []}
                  onEdit={() => setEditing({ mode: "edit", budget: b })}
                />
              ))}
            </div>
          ))}
      </Panel>

      {editing && data.status === "ready" && (
        <BudgetDialog
          editing={editing}
          directory={data.data.directory}
          taken={new Set(data.data.budgets.map((b) => `${b.scopeType}:${b.scopeId}`))}
          onClose={() => setEditing(null)}
          onSaved={() => {
            setEditing(null);
            data.retry();
          }}
        />
      )}
    </div>
  );
}

function scopeName(b: Budget, d: Directory) {
  return (b.scopeType === "PROJECT" ? d.projects : d.teams).find((x) => x.id === b.scopeId)?.name ?? "Unknown";
}

function thisMonthAlerts(alerts: BudgetAlert[]) {
  const month = new Date().toISOString().slice(0, 7);
  return alerts.filter((a) => a.periodStart.startsWith(month));
}

function SummaryCards({
  budgets,
  alerts,
  nameOf,
}: {
  budgets: Budget[];
  alerts: Map<string, BudgetAlert[]>;
  nameOf: (b: Budget) => string;
}) {
  const enabled = budgets.filter((b) => b.enabled);
  const over = enabled.filter((b) => budgetRatio(b) >= 1);
  const near = enabled.filter((b) => budgetRatio(b) >= 0.8 && budgetRatio(b) < 1);
  const sent = budgets.flatMap((b) => thisMonthAlerts(alerts.get(b.id) ?? [])).filter((a) => a.status === "SENT").length;
  const names = (bs: Budget[]) => (bs.length === 0 ? undefined : bs.length === 1 ? nameOf(bs[0]) : `${nameOf(bs[0])} +${bs.length - 1}`);
  return (
    <>
      <KpiCard label="BUDGETS" value={budgets.length} caption={budgets.length > enabled.length ? `${budgets.length - enabled.length} paused` : undefined} />
      <KpiCard label="OVER LIMIT" value={over.length} comparison={names(over) && <Chip tone="danger">{names(over)}</Chip>} />
      <KpiCard label="ABOVE 80%" value={near.length} comparison={names(near) && <Chip tone="warning">{names(near)}</Chip>} />
      <KpiCard label="ALERTS SENT" value={sent} caption="this month" />
    </>
  );
}

function BudgetRow({ budget, name, alerts, onEdit }: { budget: Budget; name: string; alerts: BudgetAlert[]; onEdit: () => void }) {
  const r = budgetRatio(budget);
  const latest = thisMonthAlerts(alerts)[0];
  const status = !budget.enabled
    ? { tone: "neutral" as const, label: "Paused" }
    : r >= 1
      ? { tone: "danger" as const, label: "Over limit" }
      : r >= 0.8
        ? { tone: "warning" as const, label: fmtPct(r) }
        : { tone: "neutral" as const, label: fmtPct(r) };
  return (
    <div className="budget-row">
      <div className="budget-name">
        <span className="strong">{name}</span>
        <span className="muted small">{budget.scopeType === "PROJECT" ? "Project" : "Team"}</span>
      </div>
      <div className="budget-progress">
        <div className="row space-between">
          <span className="small strong">
            {fmtUsd(budget.currentSpendUsd, 0)} of {fmtUsd(budget.monthlyLimitUsd, 0)}
          </span>
          <Chip tone={status.tone}>{status.label}</Chip>
        </div>
        <Bar ratio={r} tone={!budget.enabled ? "muted" : r >= 1 ? "danger" : r >= 0.8 ? "warning" : "default"} />
      </div>
      <div className="budget-alert">
        {!budget.enabled ? (
          <span className="muted small">Disabled · not evaluated</span>
        ) : latest ? (
          <>
            <span className="muted small">
              {latest.thresholdPct}% alert ·{" "}
              {latest.status === "SENT"
                ? `delivered ${new Date(latest.sentAt ?? latest.createdAt).toLocaleDateString("en-US", { month: "short", day: "numeric" })}`
                : latest.status === "FAILED"
                  ? `${latest.attempts} attempts failed`
                  : "delivering"}
            </span>
            <Chip tone={latest.status === "SENT" ? "success" : latest.status === "FAILED" ? "danger" : "info"}>
              {latest.status === "SENT" ? "Sent" : latest.status === "FAILED" ? "Failed" : "Pending"}
            </Chip>
          </>
        ) : (
          <span className="muted small">No alerts this month</span>
        )}
      </div>
      <div className="budget-action">
        <button className="btn btn-ghost" onClick={onEdit}>
          {latest?.status === "FAILED" ? "Check webhook" : "Edit"}
        </button>
      </div>
    </div>
  );
}

function BudgetDialog({
  editing,
  directory,
  taken,
  onClose,
  onSaved,
}: {
  editing: Editing;
  directory: Directory;
  taken: Set<string>;
  onClose: () => void;
  onSaved: () => void;
}) {
  const existing = editing.mode === "edit" ? editing.budget : null;
  const [scopeType, setScopeType] = useState<BudgetScope>(existing?.scopeType ?? "PROJECT");
  const options = (scopeType === "PROJECT" ? directory.projects : directory.teams).filter(
    (o) => existing || !taken.has(`${scopeType}:${o.id}`),
  );
  const [scopeId, setScopeId] = useState(existing?.scopeId ?? "");
  const [limit, setLimit] = useState(existing ? String(existing.monthlyLimitUsd) : "");
  const [webhookUrl, setWebhookUrl] = useState(existing?.webhookUrl ?? "");
  const [enabled, setEnabled] = useState(existing?.enabled ?? true);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const selectedScopeId = scopeId || options[0]?.id || "";

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErrors({});
    setFormError("");
    const amount = Number(limit.replace(/,/g, ""));
    if (!Number.isFinite(amount) || amount < 0.01) {
      setErrors({ monthlyLimitUsd: "Enter an amount of at least 0.01" });
      return;
    }
    const input: BudgetInput = { scopeType, scopeId: selectedScopeId, monthlyLimitUsd: amount, webhookUrl: webhookUrl.trim(), enabled };
    setBusy(true);
    try {
      if (existing) await updateBudget(existing.id, input);
      else await createBudget(input);
      onSaved();
    } catch (err) {
      showError(err);
    } finally {
      setBusy(false);
    }
  }

  function showError(err: unknown) {
    if (!(err instanceof ApiError)) {
      setFormError(String(err));
      return;
    }
    if (Object.keys(err.fieldErrors).length > 0) {
      setErrors(err.fieldErrors);
    } else if (err.message.startsWith("webhookUrl")) {
      // Webhook URL checks come back as a plain ProblemDetail detail
      setErrors({ webhookUrl: err.message });
    } else {
      setFormError(err.message);
    }
  }

  async function remove() {
    if (!existing) return;
    setBusy(true);
    try {
      await deleteBudget(existing.id);
      onSaved();
    } catch (err) {
      showError(err);
      setBusy(false);
    }
  }

  const spendNote =
    existing && Number(limit) > 0
      ? `Spend this month so far: ${fmtUsd(existing.currentSpendUsd)} (${fmtPct(existing.currentSpendUsd / Number(limit))} of this limit).`
      : undefined;

  return (
    <Dialog title={existing ? "Edit budget" : "New budget"} onClose={onClose}>
      <p className="muted">Alerts go to the webhook at 80% and 100% of the monthly limit.</p>
      <form className="form" onSubmit={submit}>
        <fieldset disabled={!!existing}>
          <legend>Applies to</legend>
          <div className="segmented">
            {(["PROJECT", "TEAM"] as const).map((s) => (
              <button
                key={s}
                type="button"
                className={scopeType === s ? "active" : ""}
                aria-pressed={scopeType === s}
                onClick={() => {
                  setScopeType(s);
                  setScopeId("");
                }}
              >
                {s === "PROJECT" ? "Project" : "Team"}
              </button>
            ))}
          </div>
          {existing && <span className="hint">The scope of a budget cannot be changed.</span>}
        </fieldset>
        <label>
          {scopeType === "PROJECT" ? "Project" : "Team"}
          <select value={selectedScopeId} onChange={(e) => setScopeId(e.target.value)} disabled={!!existing}>
            {options.length === 0 && <option value="">Every {scopeType === "PROJECT" ? "project" : "team"} already has a budget</option>}
            {options.map((o) => (
              <option key={o.id} value={o.id}>
                {o.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Monthly limit (USD)
          <input
            inputMode="decimal"
            value={limit}
            onChange={(e) => setLimit(e.target.value)}
            aria-invalid={!!errors.monthlyLimitUsd}
            required
          />
          {errors.monthlyLimitUsd ? <span className="field-error">{errors.monthlyLimitUsd}</span> : spendNote && <span className="hint">{spendNote}</span>}
        </label>
        <label>
          Webhook URL
          <input
            type="url"
            placeholder="https://example.com/hooks/budget"
            value={webhookUrl}
            onChange={(e) => setWebhookUrl(e.target.value)}
            aria-invalid={!!errors.webhookUrl}
            required
          />
          {errors.webhookUrl ? (
            <span className="field-error">{errors.webhookUrl}</span>
          ) : (
            <span className="hint">Must be https and reachable from the internet.</span>
          )}
        </label>
        <label className="switch">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
          <span className="track" aria-hidden="true" />
          Enabled
        </label>
        {formError && (
          <div className="field-error" role="alert">
            {formError}
          </div>
        )}
        <div className="row space-between">
          <div>
            {existing &&
              (confirmDelete ? (
                <div className="row gap-8">
                  <span className="small">Delete this budget and its alert history?</span>
                  <button type="button" className="btn btn-danger" onClick={remove} disabled={busy}>
                    Delete
                  </button>
                </div>
              ) : (
                <button type="button" className="btn btn-ghost danger-text" onClick={() => setConfirmDelete(true)}>
                  Delete budget
                </button>
              ))}
          </div>
          <div className="row gap-8">
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
            <button type="submit" className="btn btn-primary" disabled={busy || (!existing && !selectedScopeId)}>
              {busy ? "Saving…" : existing ? "Save" : "Create budget"}
            </button>
          </div>
        </div>
      </form>
    </Dialog>
  );
}
