import { useEffect, useState } from "react";
import { DailyAnalytics, fetchDaily } from "../api";
import { useDateRange } from "../dateRange";

const fmtUsd = (v: number) =>
  v.toLocaleString("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 2 });
const fmtNum = (v: number) => v.toLocaleString("en-US");

export default function Overview() {
  const { from, to } = useDateRange();
  const [days, setDays] = useState<DailyAnalytics[]>([]);

  useEffect(() => {
    fetchDaily(from, to).then(setDays).catch(() => setDays([]));
  }, [from, to]);

  const cost = days.reduce((s, d) => s + d.costUsd, 0);
  const input = days.reduce((s, d) => s + d.inputTokens, 0);
  const output = days.reduce((s, d) => s + d.outputTokens, 0);

  return (
    <div className="cards">
      <div className="card">
        <div className="label">Total spend</div>
        <div className="value">{fmtUsd(cost)}</div>
      </div>
      <div className="card">
        <div className="label">Input tokens</div>
        <div className="value">{fmtNum(input)}</div>
      </div>
      <div className="card">
        <div className="label">Output tokens</div>
        <div className="value">{fmtNum(output)}</div>
      </div>
      <div className="card">
        <div className="label">Active days</div>
        <div className="value">{days.length}</div>
      </div>
    </div>
  );
}
