import { useEffect, useState } from "react";
import { fetchProjects, ProjectAnalytics } from "../api";
import { useDateRange } from "../dateRange";
import DataTable, { Column } from "../components/DataTable";

const columns: Column<ProjectAnalytics>[] = [
  { key: "name", label: "Project" },
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
  {
    key: "linesAdded",
    label: "Lines +",
    numeric: true,
    render: (r) => r.linesAdded.toLocaleString(),
  },
  {
    key: "linesRemoved",
    label: "Lines -",
    numeric: true,
    render: (r) => r.linesRemoved.toLocaleString(),
  },
];

export default function Projects() {
  const { from, to } = useDateRange();
  const [rows, setRows] = useState<ProjectAnalytics[]>([]);

  useEffect(() => {
    fetchProjects(from, to).then(setRows).catch(() => setRows([]));
  }, [from, to]);

  return (
    <div className="panel">
      <DataTable columns={columns} rows={rows} />
    </div>
  );
}
