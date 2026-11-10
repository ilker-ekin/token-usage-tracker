import { ReactNode, useMemo, useState } from "react";
import { ChevronRight } from "./ui";

export interface Column<T> {
  key: keyof T & string;
  label: string;
  numeric?: boolean;
  render?: (row: T) => ReactNode;
}

/**
 * Click-to-sort table; numeric columns default to descending first. With
 * {@code onRowClick} every row is a link to its drill-down.
 */
export default function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  emptyMessage = "Nothing in the selected range.",
}: {
  columns: Column<T>[];
  rows: T[];
  rowKey: (row: T) => string;
  onRowClick?: (row: T) => void;
  emptyMessage?: string;
}) {
  const [sortKey, setSortKey] = useState<string | null>(null);
  const [desc, setDesc] = useState(true);

  const sorted = useMemo(() => {
    if (!sortKey) return rows;
    const col = columns.find((c) => c.key === sortKey);
    return [...rows].sort((a, b) => {
      const av = a[sortKey as keyof T];
      const bv = b[sortKey as keyof T];
      const cmp = col?.numeric ? Number(av) - Number(bv) : String(av).localeCompare(String(bv));
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
    return <div className="empty">{emptyMessage}</div>;
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
              aria-sort={sortKey === c.key ? (desc ? "descending" : "ascending") : undefined}
            >
              {c.label}
              {sortKey === c.key ? (desc ? " ↓" : " ↑") : ""}
            </th>
          ))}
          {onRowClick && <th aria-label="Open" />}
        </tr>
      </thead>
      <tbody>
        {sorted.map((row) => (
          <tr
            key={rowKey(row)}
            className={onRowClick ? "clickable" : ""}
            onClick={onRowClick ? () => onRowClick(row) : undefined}
            onKeyDown={onRowClick ? (e) => e.key === "Enter" && onRowClick(row) : undefined}
            tabIndex={onRowClick ? 0 : undefined}
          >
            {columns.map((c) => (
              <td key={c.key} className={c.numeric ? "num" : ""}>
                {c.render ? c.render(row) : String(row[c.key])}
              </td>
            ))}
            {onRowClick && (
              <td className="open-cell">
                <ChevronRight />
              </td>
            )}
          </tr>
        ))}
      </tbody>
    </table>
  );
}
