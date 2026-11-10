import { fetchDaily } from "../api";
import { toCsv } from "../csv";
import { useDateRange } from "../dateRange";
import { usePageChrome } from "../pageChrome";
import { useApi } from "../useApi";
import SpendChart, { buildSeries, ChartLegend } from "../components/SpendChart";
import { ErrorState, Panel, Skeleton } from "../components/ui";

export default function Trends() {
  const { from, to, compare, previous } = useDateRange();
  const daily = useApi(
    () => Promise.all([fetchDaily(from, to), compare ? fetchDaily(previous.from, previous.to) : Promise.resolve(null)]),
    [from, to, compare],
  );
  const series =
    daily.status === "ready"
      ? buildSeries(from, to, daily.data[0], daily.data[1] ? { ...previous, days: daily.data[1] } : undefined)
      : [];

  usePageChrome(
    {
      title: "Trends",
      exportCsv:
        daily.status === "ready"
          ? () => ({
              filename: `daily-spend_${from}_${to}.csv`,
              csv: toCsv(
                [
                  { header: "date", value: (p) => p.date },
                  { header: "cost_usd", value: (p) => p.current },
                  { header: "previous_date", value: (p) => p.previousDate },
                  { header: "previous_cost_usd", value: (p) => p.previous },
                ],
                series,
              ),
            })
          : undefined,
    },
    [daily.status, from, to, compare, series.length],
  );

  const empty = daily.status === "ready" && daily.data[0].length === 0;

  return (
    <Panel title="Daily spend" right={<ChartLegend showPrevious={compare} />}>
      {daily.status === "loading" && <Skeleton height={420} />}
      {daily.status === "error" && <ErrorState error={daily.error} onRetry={daily.retry} title="Could not load this chart" minHeight={420} />}
      {daily.status === "ready" &&
        (empty && !daily.data[1]?.length ? (
          <div className="empty">No usage in the selected range.</div>
        ) : (
          <SpendChart points={series} height={420} />
        ))}
    </Panel>
  );
}
