import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { DailyAnalytics } from "../api";
import { eachDay } from "../dateRange";
import { fmtCost } from "../format";

export interface SpendPoint {
  date: string;
  current: number;
  previousDate?: string;
  previous?: number;
}

/**
 * One point per day of the range. Days without usage are 0 (the API only
 * returns days with rollups). The previous period is aligned day by day:
 * the first day of each range shares an x position.
 */
export function buildSeries(
  from: string,
  to: string,
  current: DailyAnalytics[],
  previous?: { from: string; to: string; days: DailyAnalytics[] },
): SpendPoint[] {
  const byDay = new Map(current.map((d) => [d.date, d.costUsd]));
  const prevDays = previous ? eachDay(previous.from, previous.to) : [];
  const prevByDay = new Map((previous?.days ?? []).map((d) => [d.date, d.costUsd]));
  return eachDay(from, to).map((date, i) => ({
    date,
    current: byDay.get(date) ?? 0,
    ...(previous ? { previousDate: prevDays[i], previous: prevByDay.get(prevDays[i]) ?? 0 } : {}),
  }));
}

const shortDate = (iso: string) =>
  new Date(`${iso}T00:00:00Z`).toLocaleDateString("en-US", { month: "short", day: "numeric", timeZone: "UTC" });

export default function SpendChart({ points, height = 240 }: { points: SpendPoint[]; height?: number }) {
  const withPrevious = points.some((p) => p.previous !== undefined);
  return (
    <div style={{ height }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={points} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--chart-grid)" />
          <XAxis
            dataKey="date"
            tickFormatter={shortDate}
            fontSize={12}
            stroke="var(--text-secondary)"
            tickLine={false}
            axisLine={false}
            minTickGap={40}
          />
          <YAxis
            fontSize={12}
            stroke="var(--text-secondary)"
            tickLine={false}
            axisLine={false}
            width={64}
            tickFormatter={(v: number) => `$${v.toLocaleString("en-US", { maximumFractionDigits: 2 })}`}
          />
          <Tooltip
            formatter={(value, name, item) => {
              const p = item.payload as SpendPoint;
              const label = name === "previous" ? `Previous (${shortDate(p.previousDate ?? "")})` : "This period";
              return [fmtCost(Number(value)), label];
            }}
            labelFormatter={(label) => shortDate(String(label))}
          />
          {withPrevious && (
            <Line
              type="monotone"
              dataKey="previous"
              stroke="var(--chart-previous)"
              strokeDasharray="5 4"
              strokeWidth={2}
              dot={false}
              isAnimationActive={false}
            />
          )}
          <Line
            type="monotone"
            dataKey="current"
            stroke="var(--chart-current)"
            strokeWidth={2}
            dot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

export function ChartLegend({ showPrevious }: { showPrevious: boolean }) {
  return (
    <div className="legend">
      <span>
        <i className="swatch swatch-current" /> This period
      </span>
      {showPrevious && (
        <span>
          <i className="swatch swatch-previous" /> Previous period
        </span>
      )}
    </div>
  );
}
