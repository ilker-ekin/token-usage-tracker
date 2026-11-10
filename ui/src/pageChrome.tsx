import { createContext, ReactNode, useContext, useEffect, useState } from "react";

export interface CsvFile {
  filename: string;
  csv: string;
}

/** What a page contributes to the shared header. */
export interface PageChrome {
  title: string;
  /** Builds the CSV of what the page shows; absent while there is nothing to export. */
  exportCsv?: () => Promise<CsvFile> | CsvFile;
  /** Pages whose data is not tied to the date range (Budgets) hide the range control. */
  hideRange?: boolean;
  /** Replaces the Export button, e.g. "New budget". */
  action?: ReactNode;
}

interface ChromeContextValue {
  chrome: PageChrome;
  setChrome: (c: PageChrome) => void;
}

const ChromeContext = createContext<ChromeContextValue | null>(null);

export function PageChromeProvider({ children }: { children: ReactNode }) {
  const [chrome, setChrome] = useState<PageChrome>({ title: "" });
  return <ChromeContext.Provider value={{ chrome, setChrome }}>{children}</ChromeContext.Provider>;
}

export function useChrome(): PageChrome {
  const ctx = useContext(ChromeContext);
  if (!ctx) throw new Error("useChrome outside PageChromeProvider");
  return ctx.chrome;
}

/** Declares the page's header. Re-run when anything it closes over changes. */
export function usePageChrome(chrome: PageChrome, deps: unknown[]) {
  const ctx = useContext(ChromeContext);
  if (!ctx) throw new Error("usePageChrome outside PageChromeProvider");
  const { setChrome } = ctx;
  useEffect(() => {
    setChrome(chrome);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
}
