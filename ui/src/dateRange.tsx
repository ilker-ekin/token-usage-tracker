import { useMemo } from "react";
import { useSearchParams } from "react-router-dom";

/** All dates are UTC calendar days as YYYY-MM-DD, matching the API's rollups. */
export type Preset = "7d" | "30d" | "mtd" | "custom";

export const PRESETS: { id: Preset; label: string }[] = [
  { id: "7d", label: "7d" },
  { id: "30d", label: "30d" },
  { id: "mtd", label: "MTD" },
  { id: "custom", label: "Custom" },
];

export function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

export function addDays(isoDay: string, days: number): string {
  const d = new Date(`${isoDay}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

export function daysBetweenInclusive(from: string, to: string): number {
  const ms = Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`);
  return Math.round(ms / 86_400_000) + 1;
}

/** Every day from {@code from} to {@code to}, inclusive. */
export function eachDay(from: string, to: string): string[] {
  const out: string[] = [];
  for (let d = from; d <= to; d = addDays(d, 1)) out.push(d);
  return out;
}

/** The range of equal length that ends the day before {@code from}; same rule as the API. */
export function previousRange(from: string, to: string): { from: string; to: string } {
  const n = daysBetweenInclusive(from, to);
  const prevTo = addDays(from, -1);
  return { from: addDays(prevTo, -(n - 1)), to: prevTo };
}

export function resolvePreset(preset: Exclude<Preset, "custom">, today = todayUtc()) {
  if (preset === "7d") return { from: addDays(today, -6), to: today };
  if (preset === "mtd") return { from: `${today.slice(0, 8)}01`, to: today };
  return { from: addDays(today, -29), to: today };
}

const ISO_DAY = /^\d{4}-\d{2}-\d{2}$/;

export function parseRange(params: URLSearchParams, today = todayUtc()) {
  const raw = params.get("range");
  const compare = params.get("compare") !== "0";
  const from = params.get("from");
  const to = params.get("to");
  if (raw === "custom" && from && to && ISO_DAY.test(from) && ISO_DAY.test(to) && from <= to) {
    return { preset: "custom" as Preset, from, to, compare };
  }
  const preset: Exclude<Preset, "custom"> = raw === "7d" || raw === "mtd" ? raw : "30d";
  return { preset: preset as Preset, ...resolvePreset(preset, today), compare };
}

export interface DateRange {
  preset: Preset;
  from: string;
  to: string;
  compare: boolean;
  previous: { from: string; to: string };
  setPreset: (p: Exclude<Preset, "custom">) => void;
  setCustom: (from: string, to: string) => void;
  setCompare: (on: boolean) => void;
}

/**
 * The range lives in the URL (?range=7d|30d|mtd, or ?range=custom&from&to,
 * plus &compare=0 to turn comparison off), so a view can be bookmarked or
 * shared and survives a reload. Default: last 30 days, compare on.
 */
export function useDateRange(): DateRange {
  const [params, setParams] = useSearchParams();
  const parsed = useMemo(() => parseRange(params), [params]);

  return useMemo(() => {
    const update = (mutate: (p: URLSearchParams) => void) =>
      setParams(
        (prev) => {
          const next = new URLSearchParams(prev);
          mutate(next);
          return next;
        },
        { replace: true },
      );
    return {
      ...parsed,
      previous: previousRange(parsed.from, parsed.to),
      setPreset: (p) =>
        update((n) => {
          n.set("range", p);
          n.delete("from");
          n.delete("to");
        }),
      setCustom: (from, to) =>
        update((n) => {
          n.set("range", "custom");
          n.set("from", from <= to ? from : to);
          n.set("to", from <= to ? to : from);
        }),
      setCompare: (on) =>
        update((n) => {
          if (on) n.delete("compare");
          else n.set("compare", "0");
        }),
    };
  }, [parsed, setParams]);
}

/** Human form of a range, e.g. "Oct 12 – Nov 10, 2026". */
export function formatRange(from: string, to: string): string {
  const f = new Date(`${from}T00:00:00Z`);
  const t = new Date(`${to}T00:00:00Z`);
  const md = (d: Date) => d.toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });
  const sameYear = f.getUTCFullYear() === t.getUTCFullYear();
  const left = sameYear ? md(f) : `${md(f)}, ${f.getUTCFullYear()}`;
  return `${left} – ${md(t)}, ${t.getUTCFullYear()}`;
}
