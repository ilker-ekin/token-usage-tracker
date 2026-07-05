import { useEffect, useState } from "react";
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { DailyAnalytics, fetchDaily } from "../api";
import { useDateRange } from "../dateRange";

export default function Trends() {
  const { from, to } = useDateRange();
  const [days, setDays] = useState<DailyAnalytics[]>([]);

  useEffect(() => {
    fetchDaily(from, to).then(setDays).catch(() => setDays([]));
  }, [from, to]);

  if (days.length === 0) {
    return <div className="panel empty">No data for the selected range.</div>;
  }

  return (
    <div className="panel" style={{ height: 420 }}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={days} margin={{ top: 10, right: 20, bottom: 10, left: 10 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#eaecf0" />
          <XAxis dataKey="date" fontSize={12} />
          <YAxis
            fontSize={12}
            tickFormatter={(v: number) => `$${v.toFixed(2)}`}
            width={70}
          />
          <Tooltip formatter={(v) => [`$${Number(v).toFixed(4)}`, "Spend"]} />
          <Line
            type="monotone"
            dataKey="costUsd"
            stroke="#101828"
            strokeWidth={2}
            dot={false}
            name="Spend"
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
