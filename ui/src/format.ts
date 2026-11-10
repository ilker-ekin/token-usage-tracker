export const fmtUsd = (v: number, digits = 2) =>
  v.toLocaleString("en-US", {
    style: "currency",
    currency: "USD",
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });

/** Small amounts keep enough decimals to not read as $0.00. */
export const fmtCost = (v: number) => (v !== 0 && Math.abs(v) < 0.01 ? fmtUsd(v, 4) : fmtUsd(v));

export const fmtNum = (v: number) => v.toLocaleString("en-US");

export const fmtCompact = (v: number) =>
  v.toLocaleString("en-US", { notation: "compact", maximumFractionDigits: 2 });

export const fmtPct = (ratio: number) => `${Math.round(ratio * 100)}%`;

export interface Delta {
  label: string;
  /** For spend, an increase is bad: "up" renders as danger, "down" as success. */
  direction: "up" | "down" | "flat" | "new";
}

/** Change from {@code previous} to {@code current}, e.g. "+12.4%". */
export function delta(current: number, previous: number): Delta {
  if (previous === 0) return current === 0 ? { label: "0%", direction: "flat" } : { label: "New", direction: "new" };
  const change = (current - previous) / previous;
  if (Math.abs(change) < 0.0005) return { label: "0%", direction: "flat" };
  // Beyond 10x the exact figure says nothing useful (a near-empty previous period)
  if (change > 9.99) return { label: ">+999%", direction: "up" };
  const pct = (change * 100).toFixed(Math.abs(change) < 0.1 ? 1 : 0);
  return { label: `${change > 0 ? "+" : ""}${pct}%`, direction: change > 0 ? "up" : "down" };
}

export const fmtDateTimeUtc = (iso: string) =>
  new Date(iso).toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZone: "UTC",
  });
