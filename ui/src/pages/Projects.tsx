import { useLocation, useNavigate } from "react-router-dom";
import { fetchProjects, ProjectAnalytics } from "../api";
import { toCsv } from "../csv";
import { useDateRange } from "../dateRange";
import { delta, fmtCost, fmtNum } from "../format";
import { usePageChrome } from "../pageChrome";
import { useApi } from "../useApi";
import DataTable, { Column } from "../components/DataTable";
import { DeltaChip, ErrorState, Panel, SkeletonLines } from "../components/ui";

type Row = ProjectAnalytics & { previousCostUsd: number | null };

export default function Projects() {
  const { from, to, compare, previous } = useDateRange();
  const navigate = useNavigate();
  const { search } = useLocation();
  const data = useApi(
    () => Promise.all([fetchProjects(from, to), compare ? fetchProjects(previous.from, previous.to) : Promise.resolve(null)]),
    [from, to, compare],
  );

  const rows: Row[] =
    data.status === "ready"
      ? data.data[0].map((p) => ({
          ...p,
          previousCostUsd: data.data[1] ? (data.data[1].find((q) => q.projectId === p.projectId)?.costUsd ?? 0) : null,
        }))
      : [];

  const columns: Column<Row>[] = [
    { key: "name", label: "Project" },
    { key: "costUsd", label: "Total cost", numeric: true, render: (r) => fmtCost(r.costUsd) },
    ...(compare
      ? [
          {
            key: "previousCostUsd" as const,
            label: "Change",
            numeric: true,
            render: (r: Row) => <DeltaChip delta={delta(r.costUsd, r.previousCostUsd ?? 0)} />,
          },
        ]
      : []),
    { key: "inputTokens", label: "Input tokens", numeric: true, render: (r) => fmtNum(r.inputTokens) },
    { key: "outputTokens", label: "Output tokens", numeric: true, render: (r) => fmtNum(r.outputTokens) },
    { key: "linesAdded", label: "Lines +", numeric: true, render: (r) => fmtNum(r.linesAdded) },
    { key: "linesRemoved", label: "Lines -", numeric: true, render: (r) => fmtNum(r.linesRemoved) },
  ];

  usePageChrome(
    {
      title: "Projects",
      exportCsv:
        data.status === "ready"
          ? () => ({
              filename: `projects_${from}_${to}.csv`,
              csv: toCsv(
                [
                  { header: "project", value: (r) => r.name },
                  { header: "cost_usd", value: (r) => r.costUsd },
                  { header: "previous_cost_usd", value: (r) => r.previousCostUsd },
                  { header: "input_tokens", value: (r) => r.inputTokens },
                  { header: "output_tokens", value: (r) => r.outputTokens },
                  { header: "lines_added", value: (r) => r.linesAdded },
                  { header: "lines_removed", value: (r) => r.linesRemoved },
                ],
                rows,
              ),
            })
          : undefined,
    },
    [data.status, data.data, from, to, compare],
  );

  return (
    <Panel>
      {data.status === "loading" && <SkeletonLines lines={6} />}
      {data.status === "error" && <ErrorState error={data.error} onRetry={data.retry} title="Could not load projects" />}
      {data.status === "ready" && (
        <DataTable
          columns={columns}
          rows={rows}
          rowKey={(r) => r.projectId}
          onRowClick={(r) => navigate({ pathname: `/projects/${r.projectId}`, search })}
          emptyMessage="No project spend in the selected range."
        />
      )}
    </Panel>
  );
}
