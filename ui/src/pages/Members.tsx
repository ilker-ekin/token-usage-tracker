import { fetchMembers, MemberAnalytics } from "../api";
import { toCsv } from "../csv";
import { useDateRange } from "../dateRange";
import { fmtCost, fmtNum } from "../format";
import { usePageChrome } from "../pageChrome";
import { useApi } from "../useApi";
import DataTable, { Column } from "../components/DataTable";
import { ErrorState, Panel, SkeletonLines } from "../components/ui";

const columns: Column<MemberAnalytics>[] = [
  { key: "displayName", label: "Member" },
  { key: "email", label: "Email" },
  { key: "costUsd", label: "Total cost", numeric: true, render: (r) => fmtCost(r.costUsd) },
  { key: "inputTokens", label: "Input tokens", numeric: true, render: (r) => fmtNum(r.inputTokens) },
  { key: "outputTokens", label: "Output tokens", numeric: true, render: (r) => fmtNum(r.outputTokens) },
  { key: "linesAdded", label: "Lines +", numeric: true, render: (r) => fmtNum(r.linesAdded) },
  { key: "linesRemoved", label: "Lines -", numeric: true, render: (r) => fmtNum(r.linesRemoved) },
];

export default function Members() {
  const { from, to } = useDateRange();
  const rows = useApi(() => fetchMembers(from, to), [from, to]);

  usePageChrome(
    {
      title: "Members",
      exportCsv:
        rows.status === "ready"
          ? () => ({
              filename: `members_${from}_${to}.csv`,
              csv: toCsv(
                [
                  { header: "member", value: (r) => r.displayName },
                  { header: "email", value: (r) => r.email },
                  { header: "cost_usd", value: (r) => r.costUsd },
                  { header: "input_tokens", value: (r) => r.inputTokens },
                  { header: "output_tokens", value: (r) => r.outputTokens },
                  { header: "lines_added", value: (r) => r.linesAdded },
                  { header: "lines_removed", value: (r) => r.linesRemoved },
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
      {rows.status === "loading" && <SkeletonLines lines={8} />}
      {rows.status === "error" && <ErrorState error={rows.error} onRetry={rows.retry} title="Could not load members" />}
      {rows.status === "ready" && (
        <DataTable columns={columns} rows={rows.data} rowKey={(r) => r.memberId} emptyMessage="No member usage in the selected range." />
      )}
    </Panel>
  );
}
