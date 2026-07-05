import { useMemo, useState } from "react";

export interface Column<T> {
  key: keyof T & string;
  label: string;
  numeric?: boolean;
  render?: (row: T) => string;
}

/** Click-to-sort table; numeric columns default to descending first. */
export default function DataTable<T>({ columns, rows }: { columns: Column<T>[]; rows: T[] }) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [desc, setDesc] = useState(true);

  const sorted = useMemo(() => {
    if (!sortKey) return rows;
    const col = columns.find((c) => c.key === sortKey);
    return [...rows].sort((a, b) => {
      const av = a[sortKey as keyof T];
      const bv = b[sortKey as keyof T];
      const cmp = col?.numeric
        ? Number(av) - Number(bv)
        : String(av).localeCompare(String(bv));
      return desc ? -cmp : cmp;
    });
  }, [rows, columns, sortKey, desc]);

  function toggle(col: Column<T>) {
    if (sortKey === col.key) {
      setDesc(!desc);
    } else {
      setSortKey(col.key);
      setDesc(col.numeric ?? false);
    }
  }

  if (rows.length === 0) {
    return <div className="empty">No data for the selected range.</div>;
  }

  return (
    <table>
      <thead>
        <tr>
          {columns.map((c) => (
            <th
              key={c.key}
              className={(c.numeric ? "num " : "") + (sortKey === c.key ? "sorted" : "")}
              onClick={() => toggle(c)}
            >
              {c.label}
              {sortKey === c.key ? (desc ? " ↓" : " ↑") : ""}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {sorted.map((row, i) => (
          <tr key={i}>
            {columns.map((c) => (
              <td key={c.key} className={c.numeric ? "num" : ""}>
                {c.render ? c.render(row) : String(row[c.key])}
              </td>
            ))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
