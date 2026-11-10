import { fetchTeams, TeamAnalytics } from "../api";
import { toCsv } from "../csv";
import { useDateRange } from "../dateRange";
import { fmtCost, fmtNum } from "../format";
import { usePageChrome } from "../pageChrome";
import { useApi } from "../useApi";
import DataTable, { Column } from "../components/DataTable";
import { ErrorState, Panel, SkeletonLines } from "../components/ui";

const columns: Column<TeamAnalytics>[] = [
  { key: "name", label: "Team" },
  { key: "memberCount", label: "Members", numeric: true },
  { key: "costUsd", label: "Total cost", numeric: true, render: (r) => fmtCost(r.costUsd) },
  { key: "inputTokens", label: "Input tokens", numeric: true, render: (r) => fmtNum(r.inputTokens) },
  { key: "outputTokens", label: "Output tokens", numeric: true, render: (r) => fmtNum(r.outputTokens) },
];

export default function Teams() {
  const { from, to } = useDateRange();
  const rows = useApi(() => fetchTeams(from, to), [from, to]);

  usePageChrome(
    {
      title: "Teams",
      exportCsv:
        rows.status === "ready"
          ? () => ({
              filename: `teams_${from}_${to}.csv`,
              csv: toCsv(
                [
                  { header: "team", value: (r) => r.name },
                  { header: "members", value: (r) => r.memberCount },
                  { header: "cost_usd", value: (r) => r.costUsd },
                  { header: "input_tokens", value: (r) => r.inputTokens },
                  { header: "output_tokens", value: (r) => r.outputTokens },
                ],
                rows.data,
              ),
            })
          : undefined,
    },
    [rows.status, rows.data, from, to],
  );

  return (
    <Panel>
      {rows.status === "loading" && <SkeletonLines lines={5} />}
      {rows.status === "error" && <ErrorState error={rows.error} onRetry={rows.retry} title="Could not load teams" />}
      {rows.status === "ready" && (
        <>
          <DataTable columns={columns} rows={rows.data} rowKey={(r) => r.teamId} emptyMessage="No team usage in the selected range." />
          {rows.data.length > 0 && <p className="muted small footnote">Usage by members without a team is not counted here.</p>}
        </>
      )}
    </Panel>
  );
}
