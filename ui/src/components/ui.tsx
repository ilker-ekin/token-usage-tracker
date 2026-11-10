import { ReactNode } from "react";
import { ApiError } from "../api";
import { Delta } from "../format";

export type Tone = "neutral" | "success" | "warning" | "danger" | "info";

export function Chip({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return <span className={`chip chip-${tone}`}>{children}</span>;
}

/** Spend going up is shown as danger, going down as success. */
export function DeltaChip({ delta, invert = false }: { delta: Delta; invert?: boolean }) {
  const tone: Tone =
    delta.direction === "flat" || delta.direction === "new"
      ? "neutral"
      : (delta.direction === "up") !== invert
        ? "danger"
        : "success";
  return <Chip tone={tone}>{delta.label}</Chip>;
}

export function Panel({
  title,
  right,
  children,
  className = "",
}: {
  title?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={`panel ${className}`}>
      {(title || right) && (
        <header className="panel-header">
          {title && <h3>{title}</h3>}
          {right}
        </header>
      )}
      {children}
    </section>
  );
}

export function Bar({ ratio, tone = "default" }: { ratio: number; tone?: "default" | "warning" | "danger" | "muted" | "series-2" | "series-3" }) {
  const width = `${Math.max(0, Math.min(1, ratio)) * 100}%`;
  return (
    <div className="bar" role="presentation">
      <div className={`bar-fill bar-${tone}`} style={{ width }} />
    </div>
  );
}

export function Skeleton({ width = "100%", height = 14 }: { width?: number | string; height?: number }) {
  return <div className="skeleton" style={{ width, height }} aria-hidden="true" />;
}

export function SkeletonLines({ lines = 4 }: { lines?: number }) {
  return (
    <div className="stack" aria-busy="true" aria-label="Loading">
      {Array.from({ length: lines }, (_, i) => (
        <Skeleton key={i} width={`${100 - i * 6}%`} />
      ))}
    </div>
  );
}

export function StateBlock({
  title,
  body,
  actions,
  minHeight = 180,
}: {
  title: string;
  body?: ReactNode;
  actions?: ReactNode;
  minHeight?: number;
}) {
  return (
    <div className="state-block" style={{ minHeight }}>
      <strong>{title}</strong>
      {body && <p>{body}</p>}
      {actions && <div className="row gap-8">{actions}</div>}
    </div>
  );
}

/** A panel-sized error with its own retry, so one failed request does not blank the page. */
export function ErrorState({ error, onRetry, title = "Could not load this", minHeight }: {
  error: ApiError;
  onRetry: () => void;
  title?: string;
  minHeight?: number;
}) {
  return (
    <StateBlock
      title={title}
      body={error.message}
      minHeight={minHeight}
      actions={
        <button className="btn btn-secondary" onClick={onRetry}>
          Retry
        </button>
      }
    />
  );
}

export function ChevronRight() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <path d="m9 18 6-6-6-6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
