import { Link, useLocation } from "react-router-dom";
import { Budget, Summary, Totals, fetchBudgets, fetchDaily, fetchDirectory, fetchModels, fetchProjects, fetchSummary } from "../api";
import { budgetRatio } from "../budget";
import { toCsv } from "../csv";
import { addDays, formatRange, todayUtc, useDateRange } from "../dateRange";
import { delta, fmtCompact, fmtCost, fmtPct, fmtUsd } from "../format";
import { usePageChrome } from "../pageChrome";
import { useIsAdmin } from "../session";
import { ApiState, useApi } from "../useApi";
import KpiCard from "../components/KpiCard";
import SpendChart, { buildSeries, ChartLegend } from "../components/SpendChart";
import { Bar, ChevronRight, DeltaChip, ErrorState, Panel, SkeletonLines, Skeleton, StateBlock, Chip } from "../components/ui";

const MODEL_TONES = ["default", "series-2", "series-3"] as const;


export default function Overview() {
  const range = useDateRange();
  const { from, to, compare, previous } = range;
  const isAdmin = useIsAdmin();
  const { search } = useLocation();

  const summary = useApi(() => fetchSummary(from, to), [from, to]);
  const daily = useApi(
    () => Promise.all([fetchDaily(from, to), compare ? fetchDaily(previous.from, previous.to) : Promise.resolve(null)]),
    [from, to, compare],
  );
  const models = useApi(() => fetchModels(from, to), [from, to]);
  const projects = useApi(
    () => Promise.all([fetchProjects(from, to), compare ? fetchProjects(previous.from, previous.to) : Promise.resolve(null)]),
    [from, to, compare],
  );
  const budgets = useApi(() => (isAdmin ? Promise.all([fetchBudgets(), fetchDirectory()]) : Promise.resolve(null)), [isAdmin]);

  const series =
    daily.status === "ready"
      ? buildSeries(from, to, daily.data[0], daily.data[1] ? { ...previous, days: daily.data[1] } : undefined)
      : [];

  usePageChrome(
    {
      title: "Overview",
      exportCsv:
        daily.status === "ready"
          ? () => ({
              filename: `daily-spend_${from}_${to}.csv`,
              csv: toCsv(
                [
                  { header: "date", value: (p) => p.date },
                  { header: "cost_usd", value: (p) => p.current },
                  ...(compare
                    ? [
                        { header: "previous_date", value: (p: (typeof series)[number]) => p.previousDate },
                        { header: "previous_cost_usd", value: (p: (typeof series)[number]) => p.previous },
                      ]
                    : []),
                ],
                series,
              ),
            })
          : undefined,
    },
    [daily.status, from, to, compare, series.length],
  );

  const failures = [summary, daily, models, projects, budgets].filter((s) => s.status === "error");
  const retryAll = () => failures.forEach((s) => s.retry());

  // A real zero: the summary loaded and the range has no usage at all
  const empty = summary.status === "ready" && summary.data.current.costUsd === 0 && summary.data.current.inputTokens === 0;

  const totalModelCost = models.status === "ready" ? models.data.reduce((s, m) => s + m.costUsd, 0) : 0;

  return (
    <div className="page">
      {failures.length > 0 && (
        <div className="banner banner-danger" role="alert">
          <div>
            <strong>Some usage data could not be loaded</strong>
            <div>{failures[0].error?.message} Your date range is kept.</div>
          </div>
          <button className="btn btn-secondary" onClick={retryAll}>
            Retry
          </button>
        </div>
      )}

      <div className="cards">
        <SummaryKpi label="TOTAL SPEND" state={summary} pick={(t) => t.costUsd} format={fmtUsd} compare={compare} days={range} />
        <SummaryKpi label="INPUT TOKENS" state={summary} pick={(t) => t.inputTokens} format={fmtCompact} compare={compare} days={range} neutral />
        <SummaryKpi label="OUTPUT TOKENS" state={summary} pick={(t) => t.outputTokens} format={fmtCompact} compare={compare} days={range} neutral />
        {isAdmin ? (
          <KpiCard
            label="BUDGETS AT RISK"
            loading={budgets.status === "loading"}
            failed={budgets.status === "error"}
            value={budgets.status === "ready" && budgets.data ? atRiskValue(budgets.data[0]) : undefined}
            comparison={budgets.status === "ready" && budgets.data ? overLimitChip(budgets.data[0]) : undefined}
            caption="this month"
          />
        ) : (
          <SummaryKpi label="CACHE READ TOKENS" state={summary} pick={(t) => t.cacheReadTokens} format={fmtCompact} compare={compare} days={range} neutral />
        )}
      </div>

      {empty ? (
        <Panel className="grow">
          <StateBlock
            minHeight={320}
            title={`No usage between ${formatRange(from, to)}`}
            body="No events were reported for this organization in the selected range. Usage appears here as soon as a client sends POST /usage."
            actions={
              <button className="btn btn-primary" onClick={() => range.setCustom(addDays(todayUtc(), -89), todayUtc())}>
                Show last 90 days
              </button>
            }
          />
        </Panel>
      ) : (
        <>
          <div className="grid-main-side">
            <Panel title="Daily spend" right={<ChartLegend showPrevious={compare} />}>
              {daily.status === "loading" && <Skeleton height={240} />}
              {daily.status === "error" && <ErrorState error={daily.error} onRetry={daily.retry} title="Could not load this chart" minHeight={240} />}
              {daily.status === "ready" && <SpendChart points={series} />}
            </Panel>

            <Panel title="Spend by model">
              {models.status === "loading" && <SkeletonLines lines={5} />}
              {models.status === "error" && <ErrorState error={models.error} onRetry={models.retry} />}
              {models.status === "ready" &&
                (models.data.length === 0 ? (
                  <div className="empty">No usage in the selected range.</div>
                ) : (
                  <div className="stack gap-14">
                    {models.data.slice(0, 6).map((m, i) => (
                      <div key={m.model} className="stack gap-6">
                        <div className="row space-between">
                          <span className="strong small">{m.model}</span>
                          <span className="muted small">
                            {fmtCost(m.costUsd)} · {fmtPct(totalModelCost ? m.costUsd / totalModelCost : 0)}
                          </span>
                        </div>
                        <Bar ratio={totalModelCost ? m.costUsd / totalModelCost : 0} tone={MODEL_TONES[i % MODEL_TONES.length]} />
                      </div>
                    ))}
                    <CacheNote cacheRead={models.data.reduce((s, m) => s + m.cacheReadTokens, 0)} />
                  </div>
                ))}
            </Panel>
          </div>

          <div className="grid-main-side">
            <Panel
              title="Top projects"
              right={
                <Link className="btn btn-ghost" to={{ pathname: "/projects", search }}>
                  View all
                </Link>
              }
            >
              {projects.status === "loading" && <SkeletonLines lines={5} />}
              {projects.status === "error" && <ErrorState error={projects.error} onRetry={projects.retry} title="Could not load projects" />}
              {projects.status === "ready" && <TopProjects current={projects.data[0]} previous={projects.data[1]} search={search} />}
            </Panel>

            {isAdmin && (
              <Panel
                title="Budgets this month"
                right={
                  <Link className="btn btn-ghost" to={{ pathname: "/admin/budgets", search }}>
                    Manage
                  </Link>
                }
              >
                {budgets.status === "loading" && <SkeletonLines lines={4} />}
                {budgets.status === "error" && <ErrorState error={budgets.error} onRetry={budgets.retry} />}
                {budgets.status === "ready" && budgets.data && <BudgetGlance budgets={budgets.data[0]} directory={budgets.data[1]} />}
              </Panel>
            )}
          </div>
        </>
      )}
    </div>
  );
}

type SummaryState = ApiState<Summary> & { retry: () => void };

function SummaryKpi({
  label,
  state,
  pick,
  format,
  compare,
  days,
  neutral = false,
}: {
  label: string;
  state: SummaryState;
  pick: (t: Totals) => number;
  format: (v: number) => string;
  compare: boolean;
  days: { from: string; to: string };
  neutral?: boolean;
}) {
  if (state.status !== "ready") {
    return <KpiCard label={label} loading={state.status === "loading"} failed={state.status === "error"} />;
  }
  const current = pick(state.data.current);
  const prior = pick(state.data.previous);
  const d = delta(current, prior);
  // Nothing in either period: a "0%" comparison would only add noise
  const showComparison = compare && (current !== 0 || prior !== 0);
  const n = Math.round((Date.parse(`${days.to}T00:00:00Z`) - Date.parse(`${days.from}T00:00:00Z`)) / 86_400_000) + 1;
  return (
    <KpiCard
      label={label}
      value={format(current)}
      comparison={showComparison ? neutral ? <Chip>{d.label}</Chip> : <DeltaChip delta={d} /> : undefined}
      caption={showComparison ? `vs previous ${n} day${n === 1 ? "" : "s"}` : undefined}
    />
  );
}

function atRiskValue(budgets: Budget[]) {
  const enabled = budgets.filter((b) => b.enabled);
  const atRisk = enabled.filter((b) => budgetRatio(b) >= 0.8).length;
  return `${atRisk} of ${enabled.length}`;
}

function overLimitChip(budgets: Budget[]) {
  const over = budgets.filter((b) => b.enabled && budgetRatio(b) >= 1).length;
  return over > 0 ? <Chip tone="warning">{over} over limit</Chip> : <Chip tone="success">none over limit</Chip>;
}

function CacheNote({ cacheRead }: { cacheRead: number }) {
  if (cacheRead === 0) return null;
  return <p className="muted small">{fmtCompact(cacheRead)} input tokens were served from the prompt cache.</p>;
}

function TopProjects({
  current,
  previous,
  search,
}: {
  current: Awaited<ReturnType<typeof fetchProjects>>;
  previous: Awaited<ReturnType<typeof fetchProjects>> | null;
  search: string;
}) {
  if (current.length === 0) return <div className="empty">No project spend in the selected range.</div>;
  const total = current.reduce((s, p) => s + p.costUsd, 0);
  const prevById = new Map((previous ?? []).map((p) => [p.projectId, p.costUsd]));
  return (
    <table>
      <thead>
        <tr>
          <th>Project</th>
          <th className="num">Spend</th>
          {previous && <th className="num">Change</th>}
          <th>Share</th>
          <th aria-label="Open" />
        </tr>
      </thead>
      <tbody>
        {current.slice(0, 5).map((p) => (
          <tr key={p.projectId}>
            <td className="strong">
              <Link className="row-link" to={{ pathname: `/projects/${p.projectId}`, search }}>
                {p.name}
              </Link>
            </td>
            <td className="num">{fmtCost(p.costUsd)}</td>
            {previous && (
              <td className="num">
                <DeltaChip delta={delta(p.costUsd, prevById.get(p.projectId) ?? 0)} />
              </td>
            )}
            <td className="share-cell">
              <Bar ratio={total ? p.costUsd / total : 0} />
            </td>
            <td className="open-cell">
              <ChevronRight />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function BudgetGlance({ budgets, directory }: { budgets: Budget[]; directory: Awaited<ReturnType<typeof fetchDirectory>> }) {
  if (budgets.length === 0) return <div className="empty">No budgets yet.</div>;
  const nameOf = (b: Budget) =>
    (b.scopeType === "PROJECT" ? directory.projects : directory.teams).find((x) => x.id === b.scopeId)?.name ?? "Unknown";
  const sorted = [...budgets].filter((b) => b.enabled).sort((a, b) => budgetRatio(b) - budgetRatio(a));
  return (
    <div className="stack gap-14">
      {sorted.slice(0, 4).map((b) => {
        const r = budgetRatio(b);
        return (
          <div key={b.id} className="stack gap-6">
            <div className="row space-between">
              <span className="strong small">
                {nameOf(b)}
                {b.scopeType === "TEAM" && " team"}
              </span>
              <Chip tone={r >= 1 ? "danger" : r >= 0.8 ? "warning" : "neutral"}>{r >= 1 ? "Over limit" : fmtPct(r)}</Chip>
            </div>
            <Bar ratio={r} tone={r >= 1 ? "danger" : r >= 0.8 ? "warning" : "default"} />
            <span className="muted small">
              {fmtUsd(b.currentSpendUsd, 0)} of {fmtUsd(b.monthlyLimitUsd, 0)}
            </span>
          </div>
        );
      })}
    </div>
  );
}

