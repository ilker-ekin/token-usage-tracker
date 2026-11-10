import { ReactNode } from "react";
import { Skeleton } from "./ui";

/**
 * Headline number. While loading it shows a skeleton and on error a dash:
 * never a zero that was not measured.
 */
export default function KpiCard({
  label,
  value,
  comparison,
  caption,
  loading = false,
  failed = false,
}: {
  label: string;
  value?: ReactNode;
  comparison?: ReactNode;
  caption?: ReactNode;
  loading?: boolean;
  failed?: boolean;
}) {
  return (
    <div className="card">
      <div className="label">{label}</div>
      {loading ? (
        <>
          <Skeleton width="60%" height={28} />
          <Skeleton width="45%" height={12} />
        </>
      ) : (
        <>
          <div className="value">{failed ? "—" : value}</div>
          {!failed && (comparison || caption) && (
            <div className="comparison">
              {comparison}
              {caption && <span className="caption">{caption}</span>}
            </div>
          )}
        </>
      )}
    </div>
  );
}
