import { useEffect, useState } from "react";
import { fetchMembers, MemberAnalytics } from "../api";
import { useDateRange } from "../dateRange";
import DataTable, { Column } from "../components/DataTable";

const columns: Column<MemberAnalytics>[] = [
  { key: "displayName", label: "Member" },
  { key: "email", label: "Email" },
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

export default function Members() {
  const { from, to } = useDateRange();
  const [rows, setRows] = useState<MemberAnalytics[]>([]);

  useEffect(() => {
    fetchMembers(from, to).then(setRows).catch(() => setRows([]));
  }, [from, to]);

  return (
    <div className="panel">
      <DataTable columns={columns} rows={rows} />
    </div>
  );
}
