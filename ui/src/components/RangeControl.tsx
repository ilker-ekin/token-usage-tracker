import { useState } from "react";
import { formatRange, PRESETS, useDateRange } from "../dateRange";

/** Presets, the resolved dates (editable as Custom), and the previous-period toggle. */
export default function RangeControl() {
  const range = useDateRange();
  const [editing, setEditing] = useState(false);

  return (
    <div className="range-control">
      <div className="segmented" role="group" aria-label="Date range">
        {PRESETS.map((p) => (
          <button
            key={p.id}
            className={range.preset === p.id ? "active" : ""}
            aria-pressed={range.preset === p.id}
            onClick={() => (p.id === "custom" ? setEditing(true) : (setEditing(false), range.setPreset(p.id)))}
          >
            {p.label}
          </button>
        ))}
      </div>
      {editing || range.preset === "custom" ? (
        <div className="date-inputs">
          <input
            type="date"
            aria-label="From"
            value={range.from}
            max={range.to}
            onChange={(e) => e.target.value && range.setCustom(e.target.value, range.to)}
          />
          <span>–</span>
          <input
            type="date"
            aria-label="To"
            value={range.to}
            min={range.from}
            onChange={(e) => e.target.value && range.setCustom(range.from, e.target.value)}
          />
        </div>
      ) : (
        <button className="range-label" onClick={() => setEditing(true)} title="Pick custom dates">
          {formatRange(range.from, range.to)}
        </button>
      )}
      <label className="switch">
        <input type="checkbox" checked={range.compare} onChange={(e) => range.setCompare(e.target.checked)} />
        <span className="track" aria-hidden="true" />
        Compare to previous period
      </label>
    </div>
  );
}
