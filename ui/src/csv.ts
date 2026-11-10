export interface CsvColumn<T> {
  header: string;
  value: (row: T) => string | number | null | undefined;
}

/** RFC 4180: fields with a comma, quote or line break are quoted, quotes doubled. */
function field(v: string | number | null | undefined): string {
  if (v === null || v === undefined) return "";
  const s = String(v);
  // A leading =, +, - or @ would run as a formula when opened in a spreadsheet
  const safe = /^[=+\-@]/.test(s) && !/^-?\d/.test(s) ? `'${s}` : s;
  return /[",\r\n]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export function toCsv<T>(columns: CsvColumn<T>[], rows: T[]): string {
  const lines = [columns.map((c) => field(c.header)).join(",")];
  for (const row of rows) lines.push(columns.map((c) => field(c.value(row))).join(","));
  return lines.join("\r\n") + "\r\n";
}

export function downloadCsv(filename: string, csv: string) {
  const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
