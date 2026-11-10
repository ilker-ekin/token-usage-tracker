import { ReactNode, useEffect, useRef } from "react";

/** Modal over the page. Escape and the backdrop close it unless {@code dismissable} is false. */
export default function Dialog({
  title,
  children,
  onClose,
  dismissable = true,
  width = 520,
}: {
  title: ReactNode;
  children: ReactNode;
  onClose?: () => void;
  dismissable?: boolean;
  width?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    ref.current?.querySelector<HTMLElement>("input, select, button")?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && dismissable) onClose?.();
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      previous?.focus();
    };
  }, [dismissable, onClose]);

  return (
    <div className="scrim" onMouseDown={(e) => e.target === e.currentTarget && dismissable && onClose?.()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-label={typeof title === "string" ? title : undefined} ref={ref} style={{ width }}>
        {typeof title === "string" ? <h2>{title}</h2> : title}
        {children}
      </div>
    </div>
  );
}
