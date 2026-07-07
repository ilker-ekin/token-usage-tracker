import { createContext, useContext, useState, ReactNode } from "react";

function isoDaysAgo(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() - days);
  return d.toISOString().slice(0, 10);
}

interface DateRange {
  from: string;
  to: string;
  setFrom: (v: string) => void;
  setTo: (v: string) => void;
}

const DateRangeContext = createContext<DateRange | null>(null);

export function DateRangeProvider({ children }: { children: ReactNode }) {
  const [from, setFrom] = useState(isoDaysAgo(29));
  const [to, setTo] = useState(isoDaysAgo(0));
  return (
    <DateRangeContext.Provider value={{ from, to, setFrom, setTo }}>
      {children}
    </DateRangeContext.Provider>
  );
}

export function useDateRange(): DateRange {
  const ctx = useContext(DateRangeContext);
  if (!ctx) throw new Error("useDateRange outside provider");
  return ctx;
}
