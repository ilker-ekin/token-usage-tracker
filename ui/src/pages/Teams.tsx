import { useEffect, useState } from "react";
import { fetchTeams, TeamAnalytics } from "../api";
import { useDateRange } from "../dateRange";
import DataTable, { Column } from "../components/DataTable";

const columns: Column<TeamAnalytics>[] = [
  { key: "name", label: "Team" },
  { key: "memberCount", label: "Members", numeric: true },
  {
    key: "costUsd",
    label: "Total cost",
    numeric: true,
    render: (r) => `$${r.costUsd.toFixed(4)}`,
  },
  {
    key: "inputTokens",
    label: "Input tokens",
    numeric: true,
    render: (r) => r.inputTokens.toLocaleString(),
  },
  {
    key: "outputTokens",
    label: "Output tokens",
    numeric: true,
    render: (r) => r.outputTokens.toLocaleString(),
  },
];

export default function Teams() {
  const { from, to } = useDateRange();
  const [rows, setRows] = useState<TeamAnalytics[]>([]);

  useEffect(() => {
    fetchTeams(from, to).then(setRows).catch(() => setRows([]));
  }, [from, to]);

  return (
    <div className="panel">
      <DataTable columns={columns} rows={rows} />
    </div>
  );
}
