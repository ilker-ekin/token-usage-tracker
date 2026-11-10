import { useEffect, useState } from "react";
import { Link, useLocation, useParams } from "react-router-dom";
import {
  fetchBudgets,
  fetchDirectory,
  fetchModels,
  fetchProjects,
  fetchUsage,
  UsageEventDetail,
} from "../api";
import { toCsv } from "../csv";
import { useDateRange } from "../dateRange";
import { delta, fmtCompact, fmtCost, fmtDateTimeUtc, fmtNum, fmtPct, fmtUsd } from "../format";
import { usePageChrome } from "../pageChrome";
import { useIsAdmin } from "../session";
import { useApi } from "../useApi";
import KpiCard from "../components/KpiCard";
import { budgetRatio } from "../budget";
import { Bar, Chip, ChevronRight, DeltaChip, ErrorState, Panel, SkeletonLines } from "../components/ui";

const PAGE_SIZE = 50;
/** The API caps one page at 200 events. */
const EXPORT_PAGE_SIZE = 200;
const EXPORT_MAX_EVENTS = 20_000;

export default function ProjectDetail() {
  const { projectId = "" } = useParams();
  const { from, to, compare, previous } = useDateRange();
  const { search } = useLocation();
  const isAdmin = useIsAdmin();
  const [memberId, setMemberId] = useState("");
  const [model, setModel] = useState("");
  const [page, setPage] = useState(0);

  // A new range or filter starts from the first page
  useEffect(() => setPage(0), [from, to, memberId, model, projectId]);

  const directory = useApi(fetchDirectory, []);
  const spend = useApi(
    () => Promise.all([fetchProjects(from, to), compare ? fetchProjects(previous.from, previous.to) : Promise.resolve(null)]),
    [from, to, compare],
  );
  const models = useApi(() => fetchModels(from, to, projectId), [from, to, projectId]);
  const eventCount = useApi(() => fetchUsage({ from, to, projectId, size: 1 }), [from, to, projectId]);
  const events = useApi(
    () => fetchUsage({ from, to, projectId, memberId: memberId || undefined, model: model || undefined, page, size: PAGE_SIZE }),
    [from, to, projectId, memberId, model, page],
  );
  const budget = useApi(
    () => (isAdmin ? fetchBudgets().then((bs) => bs.find((b) => b.scopeType === "PROJECT" && b.scopeId === projectId) ?? null) : Promise.resolve(null)),
    [isAdmin, projectId],
  );

  const name =
    directory.status === "ready" ? (directory.data.projects.find((p) => p.id === projectId)?.name ?? "Unknown project") : "Project";
  const memberName = (id: string) =>
    directory.status === "ready" ? (directory.data.members.find((m) => m.id === id)?.displayName ?? "Unknown") : "…";

  const current = spend.status === "ready" ? spend.data[0].find((p) => p.projectId === projectId) : undefined;
  const prior = spend.status === "ready" && spend.data[1] ? spend.data[1].find((p) => p.projectId === projectId) : undefined;

  usePageChrome(
    {
      title: name,
      exportCsv: () => exportEvents(projectId, name, from, to, memberId, model, memberName),
    },
    [projectId, name, from, to, memberId, model, directory.status],
  );

  const totalModelCost = models.status === "ready" ? models.data.reduce((s, m) => s + m.costUsd, 0) : 0;

  return (
    <div className="page">
      <nav className="breadcrumb" aria-label="Breadcrumb">
        <Link to={{ pathname: "/projects", search }}>Projects</Link>
        <ChevronRight />
        <span>{name}</span>
      </nav>

      <div className="cards">
        <KpiCard
          label="SPEND"
          loading={spend.status === "loading"}
          failed={spend.status === "error"}
          value={fmtUsd(current?.costUsd ?? 0)}
          comparison={compare ? <DeltaChip delta={delta(current?.costUsd ?? 0, prior?.costUsd ?? 0)} /> : undefined}
          caption={compare ? "vs previous period" : undefined}
        />
        <KpiCard
          label="EVENTS"
          loading={eventCount.status === "loading"}
          failed={eventCount.status === "error"}
          value={eventCount.status === "ready" ? fmtNum(eventCount.data.totalElements) : undefined}
        />
        <KpiCard
          label="TOKENS IN / OUT"
          loading={spend.status === "loading"}
          failed={spend.status === "error"}
          value={`${fmtCompact(current?.inputTokens ?? 0)} / ${fmtCompact(current?.outputTokens ?? 0)}`}
        />
        {isAdmin && (
          <KpiCard
            label="BUDGET"
            loading={budget.status === "loading"}
            failed={budget.status === "error"}
            value={
              budget.status === "ready"
                ? budget.data
                  ? `${fmtPct(budgetRatio(budget.data))} of ${fmtUsd(budget.data.monthlyLimitUsd, 0)}`
                  : "No budget"
                : undefined
            }
            comparison={
              budget.status === "ready" && budget.data && budgetRatio(budget.data) >= 0.8 ? (
                <Chip tone={budgetRatio(budget.data) >= 1 ? "danger" : "warning"}>
                  {budgetRatio(budget.data) >= 1 ? "Over limit" : "Above 80%"}
                </Chip>
              ) : undefined
            }
            caption={budget.status === "ready" && budget.data ? "this month" : undefined}
          />
        )}
      </div>

      <Panel title="Spend by model">
        {models.status === "loading" && <SkeletonLines lines={3} />}
        {models.status === "error" && <ErrorState error={models.error} onRetry={models.retry} />}
        {models.status === "ready" &&
          (models.data.length === 0 ? (
            <div className="empty">No usage on this project in the selected range.</div>
          ) : (
            <div className="model-grid">
              {models.data.map((m) => (
                <div key={m.model} className="stack gap-6">
                  <div className="row space-between">
                    <span className="strong small">{m.model}</span>
                    <span className="muted small">
                      {fmtCost(m.costUsd)} · {fmtPct(totalModelCost ? m.costUsd / totalModelCost : 0)}
                    </span>
                  </div>
                  <Bar ratio={totalModelCost ? m.costUsd / totalModelCost : 0} />
                </div>
              ))}
            </div>
          ))}
      </Panel>

      <Panel
        title="Events"
        className="grow"
        right={
          <div className="row gap-8">
            <select aria-label="Member" value={memberId} onChange={(e) => setMemberId(e.target.value)}>
              <option value="">Member: All</option>
              {directory.status === "ready" &&
                directory.data.members.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.displayName}
                  </option>
                ))}
            </select>
            <select aria-label="Model" value={model} onChange={(e) => setModel(e.target.value)}>
              <option value="">Model: All</option>
              {models.status === "ready" &&
                models.data.map((m) => (
                  <option key={m.model} value={m.model}>
                    {m.model}
                  </option>
                ))}
            </select>
          </div>
        }
      >
        {events.status === "loading" && <SkeletonLines lines={8} />}
        {events.status === "error" && <ErrorState error={events.error} onRetry={events.retry} title="Could not load events" />}
        {events.status === "ready" &&
          (events.data.totalElements === 0 ? (
            <div className="empty">No events match these filters in the selected range.</div>
          ) : (
            <>
              <EventsTable events={events.data.content} memberName={memberName} />
              <div className="row space-between pager">
                <span className="muted small">
                  {fmtNum(page * PAGE_SIZE + 1)}–{fmtNum(page * PAGE_SIZE + events.data.content.length)} of{" "}
                  {fmtNum(events.data.totalElements)} events
                </span>
                <div className="row gap-8">
                  <button className="btn btn-secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>
                    Previous
                  </button>
                  <button
                    className="btn btn-secondary"
                    disabled={page + 1 >= events.data.totalPages}
                    onClick={() => setPage(page + 1)}
                  >
                    Next
                  </button>
                </div>
              </div>
            </>
          ))}
      </Panel>
    </div>
  );
}

function EventsTable({ events, memberName }: { events: UsageEventDetail[]; memberName: (id: string) => string }) {
  return (
    <table>
      <thead>
        <tr>
          <th>Time (UTC)</th>
          <th>Member</th>
          <th>Model</th>
          <th className="num">Input</th>
          <th className="num">Cache read</th>
          <th className="num">Output</th>
          <th className="num">Cost</th>
        </tr>
      </thead>
      <tbody>
        {events.map((e) => (
          <tr key={e.id}>
            <td className="nowrap">{fmtDateTimeUtc(e.eventTime)}</td>
            <td>{memberName(e.memberId)}</td>
            <td>{e.model}</td>
            <td className="num">{fmtNum(e.inputTokens)}</td>
            <td className="num">{fmtNum(e.cacheReadTokens)}</td>
            <td className="num">{fmtNum(e.outputTokens)}</td>
            <td className="num">{fmtCost(e.costUsd)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

/** Every matching event in the range, not just the visible page, up to a cap. */
async function exportEvents(
  projectId: string,
  projectName: string,
  from: string,
  to: string,
  memberId: string,
  model: string,
  memberName: (id: string) => string,
) {
  const all: UsageEventDetail[] = [];
  for (let page = 0; all.length < EXPORT_MAX_EVENTS; page++) {
    const res = await fetchUsage({
      from,
      to,
      projectId,
      memberId: memberId || undefined,
      model: model || undefined,
      page,
      size: EXPORT_PAGE_SIZE,
    });
    all.push(...res.content);
    if (page + 1 >= res.totalPages) break;
  }
  return {
    filename: `${projectName}_events_${from}_${to}.csv`,
    csv: toCsv(
      [
        { header: "event_time_utc", value: (e) => e.eventTime },
        { header: "member", value: (e) => memberName(e.memberId) },
        { header: "model", value: (e) => e.model },
        { header: "input_tokens", value: (e) => e.inputTokens },
        { header: "cache_read_tokens", value: (e) => e.cacheReadTokens },
        { header: "cache_creation_tokens", value: (e) => e.cacheCreationTokens },
        { header: "output_tokens", value: (e) => e.outputTokens },
        { header: "cost_usd", value: (e) => e.costUsd },
        { header: "event_id", value: (e) => e.id },
      ],
      all,
    ),
  };
}
