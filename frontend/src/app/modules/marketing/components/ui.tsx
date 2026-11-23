import { ReactNode, useEffect, useRef, useState } from "react";
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  Cog,
  Info,
  Loader2,
  MoreHorizontal,
  PenLine,
  Sparkles,
  UserCheck,
  X,
  XCircle,
} from "lucide-react";
import type { CampaignStatus, Provenance } from "../api/types";
import { campaignStatusMeta, provenanceMeta, scoreTone, Tone, toneColors } from "../theme";

type IconType = React.ComponentType<{ size?: number; className?: string; "aria-hidden"?: boolean }>;

/* ---------------- Button ---------------- */
export function Btn({
  children,
  variant,
  size,
  icon: Icon,
  busy,
  className,
  ...rest
}: {
  children?: ReactNode;
  variant?: "primary" | "danger" | "ok" | "ghost" | "ai";
  size?: "sm" | "lg";
  icon?: IconType;
  busy?: boolean;
} & React.ButtonHTMLAttributes<HTMLButtonElement>) {
  const cls = ["mk-btn", variant && `mk-btn--${variant}`, size && `mk-btn--${size}`, className].filter(Boolean).join(" ");
  return (
    <button type="button" className={cls} {...rest} disabled={rest.disabled || busy} aria-busy={busy || undefined}>
      {busy ? <Loader2 size={14} className="mk-spin" aria-hidden /> : Icon ? <Icon size={14} aria-hidden /> : null}
      {children}
    </button>
  );
}

/* ---------------- Badges & tags ---------------- */
export function Badge({ tone, children, dot = true, title }: { tone: Tone; children: ReactNode; dot?: boolean; title?: string }) {
  const c = toneColors[tone];
  return (
    <span className="mk-badge" style={{ color: c.fg, background: c.bg, borderColor: c.line }} title={title}>
      {dot && <span className="mk-badge__dot" aria-hidden />}
      {children}
    </span>
  );
}

export function StatusBadge({ status }: { status: CampaignStatus }) {
  const m = campaignStatusMeta[status];
  return <Badge tone={m.tone}>{m.label}</Badge>;
}

export function ProvenanceTag({ provenance }: { provenance: Provenance }) {
  const m = provenanceMeta[provenance];
  const Icon = provenance === "AI_GENERATED" ? Sparkles : provenance === "SYSTEM_GENERATED" ? Cog : provenance === "HUMAN_EDITED" ? PenLine : UserCheck;
  return (
    <span className={`mk-tag ${m.cls}`} title={m.hint}>
      <Icon size={12} aria-hidden />
      {m.label}
    </span>
  );
}

/** Transparency labels that aren't content provenance. */
export function TransparencyTag({ kind, title }: { kind: "AI_RECOMMENDED" | "HUMAN_APPROVED" | "SYSTEM" | "AI"; title?: string }) {
  if (kind === "AI_RECOMMENDED")
    return (
      <span className="mk-tag mk-tag--ai-rec" title={title ?? "A recommendation from the AI. It takes effect only when a person approves it."}>
        <Bot size={12} aria-hidden /> AI recommended
      </span>
    );
  if (kind === "HUMAN_APPROVED")
    return (
      <span className="mk-tag mk-tag--human" title={title}>
        <UserCheck size={12} aria-hidden /> Human approved
      </span>
    );
  if (kind === "AI")
    return (
      <span className="mk-tag mk-tag--ai" title={title}>
        <Sparkles size={12} aria-hidden /> AI
      </span>
    );
  return (
    <span className="mk-tag mk-tag--system" title={title}>
      <Cog size={12} aria-hidden /> System
    </span>
  );
}

/* ---------------- Panel ---------------- */
export function Panel({
  title,
  subtitle,
  actions,
  children,
  flush,
  className,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  flush?: boolean;
  className?: string;
}) {
  return (
    <section className={`mk-panel ${className ?? ""}`}>
      {(title || actions) && (
        <div className="mk-panel__head">
          <div style={{ minWidth: 0 }}>
            {title && <h3 className="mk-panel__title">{title}</h3>}
            {subtitle && <p className="mk-panel__sub">{subtitle}</p>}
          </div>
          {actions && <div className="mk-row">{actions}</div>}
        </div>
      )}
      <div className={flush ? "" : "mk-panel__body"} style={flush ? { paddingTop: 12 } : undefined}>
        {children}
      </div>
    </section>
  );
}

/* ---------------- Tabs ---------------- */
export function Tabs<T extends string>({
  tabs,
  value,
  onChange,
  label,
}: {
  tabs: { id: T; label: string; count?: number; warn?: boolean; icon?: IconType }[];
  value: T;
  onChange: (t: T) => void;
  label: string;
}) {
  return (
    <div className="mk-tabs" role="tablist" aria-label={label}>
      {tabs.map((t) => (
        <button
          key={t.id}
          role="tab"
          type="button"
          className="mk-tab"
          aria-selected={t.id === value}
          onClick={() => onChange(t.id)}
        >
          {t.icon && <t.icon size={14} aria-hidden />}
          {t.label}
          {t.count != null && t.count > 0 && <span className={`mk-count ${t.warn ? "mk-count--warn" : ""}`}>{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

/* ---------------- Callout ---------------- */
export function Callout({ tone, title, children, icon }: { tone: "ai" | "warn" | "bad" | "info" | "ok"; title?: ReactNode; children?: ReactNode; icon?: IconType }) {
  const Icon = icon ?? (tone === "ai" ? Sparkles : tone === "warn" ? AlertTriangle : tone === "bad" ? XCircle : tone === "ok" ? CheckCircle2 : Info);
  return (
    <div className={`mk-callout mk-callout--${tone}`} role={tone === "bad" ? "alert" : undefined}>
      <Icon size={16} aria-hidden />
      <div style={{ minWidth: 0 }}>
        {title && <div style={{ fontWeight: 650, marginBottom: children ? 2 : 0 }}>{title}</div>}
        {children}
      </div>
    </div>
  );
}

/* ---------------- States ---------------- */
export function EmptyState({ icon: Icon, title, children, action }: { icon: IconType; title: string; children?: ReactNode; action?: ReactNode }) {
  return (
    <div className="mk-empty">
      <div className="mk-empty__icon">
        <Icon size={20} aria-hidden />
      </div>
      <h4>{title}</h4>
      {children && <p style={{ margin: "0 auto 12px", maxWidth: 420 }}>{children}</p>}
      {action}
    </div>
  );
}

export function Skeleton({ rows = 4, height = 14 }: { rows?: number; height?: number }) {
  return (
    <div className="mk-stack" style={{ gap: 10 }} aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="mk-skel" style={{ height, width: `${92 - (i % 3) * 14}%` }} />
      ))}
    </div>
  );
}

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  return (
    <Callout tone="bad" title="Couldn't load this">
      <div>{error instanceof Error ? error.message : "Something went wrong."}</div>
      {onRetry && (
        <div style={{ marginTop: 8 }}>
          <Btn size="sm" onClick={onRetry}>
            Try again
          </Btn>
        </div>
      )}
    </Callout>
  );
}

/* ---------------- Score ---------------- */
export function ScoreMeter({ score }: { score: number | null }) {
  const c = toneColors[scoreTone(score)];
  return (
    <span className="mk-score" aria-label={score == null ? "Not scored" : `Score ${score} of 100`}>
      <span className="mk-score__val">{score ?? "—"}</span>
      <span className="mk-score__bar" aria-hidden>
        <span style={{ width: `${score ?? 0}%`, background: c.fg }} />
      </span>
    </span>
  );
}

/* ---------------- Menu ---------------- */
export type MenuItem = { label: string; icon?: IconType; onSelect: () => void; disabled?: boolean; danger?: boolean; hidden?: boolean } | "divider";

export function Menu({ items, label }: { items: MenuItem[]; label: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent) => !ref.current?.contains(e.target as Node) && setOpen(false);
    const esc = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", esc);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", esc);
    };
  }, [open]);
  const visible = items.filter((i) => i === "divider" || !i.hidden);
  return (
    <div ref={ref} style={{ position: "relative", display: "inline-block" }}>
      <Btn variant="ghost" className="mk-icon-btn" aria-label={label} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        <MoreHorizontal size={16} aria-hidden />
      </Btn>
      {open && (
        <div className="mk-menu" role="menu">
          {visible.map((item, i) =>
            item === "divider" ? (
              <hr key={i} />
            ) : (
              <button
                key={item.label}
                role="menuitem"
                type="button"
                disabled={item.disabled}
                className={item.danger ? "mk-menu--danger" : undefined}
                onClick={() => {
                  setOpen(false);
                  item.onSelect();
                }}
              >
                {item.icon && <item.icon size={14} aria-hidden />}
                {item.label}
              </button>
            )
          )}
        </div>
      )}
    </div>
  );
}

/* ---------------- Overlays ---------------- */
function useEscape(onClose: () => void) {
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", esc);
    return () => document.removeEventListener("keydown", esc);
  }, [onClose]);
}

export function Drawer({ title, subtitle, onClose, children, footer }: { title: ReactNode; subtitle?: ReactNode; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEscape(onClose);
  return (
    <div className="mk-root mk-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <aside className="mk-drawer" role="dialog" aria-modal="true" aria-label={typeof title === "string" ? title : "Details"}>
        <header className="mk-drawer__head">
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 16, fontWeight: 650 }}>{title}</div>
            {subtitle && <div className="mk-sub" style={{ marginTop: 2 }}>{subtitle}</div>}
          </div>
          <Btn variant="ghost" className="mk-icon-btn" aria-label="Close" onClick={onClose}>
            <X size={16} aria-hidden />
          </Btn>
        </header>
        <div className="mk-drawer__body">{children}</div>
        {footer && <footer className="mk-drawer__foot">{footer}</footer>}
      </aside>
    </div>
  );
}

/**
 * Confirmation for consequential actions. With `note`, collects a reason that
 * goes on the audit trail (required when `noteRequired`).
 */
export function ConfirmDialog({
  title,
  body,
  confirmLabel,
  tone = "primary",
  note,
  noteRequired,
  busy,
  onConfirm,
  onCancel,
  children,
}: {
  title: string;
  body?: ReactNode;
  confirmLabel: string;
  tone?: "primary" | "danger" | "ok";
  note?: string;
  noteRequired?: boolean;
  busy?: boolean;
  onConfirm: (note: string) => void;
  onCancel: () => void;
  children?: ReactNode;
}) {
  useEscape(onCancel);
  const [text, setText] = useState("");
  const blocked = noteRequired && text.trim().length < 3;
  return (
    <div className="mk-root mk-scrim mk-scrim--center" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <div className="mk-dialog" role="alertdialog" aria-modal="true" aria-labelledby="mk-dialog-title">
        <div className="mk-dialog__body">
          <h3 id="mk-dialog-title" style={{ margin: "0 0 6px", fontSize: 15 }}>
            {title}
          </h3>
          {body && <div className="mk-sub" style={{ fontSize: 13 }}>{body}</div>}
          {children}
          {note !== undefined && (
            <label className="mk-field" style={{ marginTop: 12 }}>
              <span className="mk-label">
                {note} {noteRequired && <span className="mk-req">*</span>}
              </span>
              <textarea className="mk-textarea" rows={3} maxLength={500} value={text} onChange={(e) => setText(e.target.value)} autoFocus />
              <span className="mk-help">Recorded in the campaign's audit trail.</span>
            </label>
          )}
        </div>
        <div className="mk-dialog__foot">
          <Btn onClick={onCancel}>Cancel</Btn>
          <Btn variant={tone === "danger" ? "danger" : tone === "ok" ? "ok" : "primary"} busy={busy} disabled={blocked} onClick={() => onConfirm(text.trim())}>
            {confirmLabel}
          </Btn>
        </div>
      </div>
    </div>
  );
}

/* ---------------- Form fields ---------------- */
export function Field({
  label,
  required,
  help,
  error,
  wide,
  children,
  htmlFor,
}: {
  label: string;
  required?: boolean;
  help?: ReactNode;
  error?: string;
  wide?: boolean;
  children: ReactNode;
  htmlFor?: string;
}) {
  return (
    <div className={`mk-field ${wide ? "mk-field--wide" : ""}`}>
      <label className="mk-label" htmlFor={htmlFor}>
        {label}
        {required && <span className="mk-req" aria-hidden>*</span>}
      </label>
      {children}
      {error ? <span className="mk-error" role="alert">{error}</span> : help ? <span className="mk-help">{help}</span> : null}
    </div>
  );
}

export function ChipGroup<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: { value: T; label: string }[];
  value: T[];
  onChange: (v: T[]) => void;
  label: string;
}) {
  return (
    <div className="mk-chips" role="group" aria-label={label}>
      {options.map((o) => {
        const on = value.includes(o.value);
        return (
          <button
            key={o.value}
            type="button"
            className="mk-chip"
            aria-pressed={on}
            onClick={() => onChange(on ? value.filter((v) => v !== o.value) : [...value, o.value])}
          >
            {on && <CheckCircle2 size={12} aria-hidden />}
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

/** Free-text list input: Enter or comma adds a value. */
export function TokenInput({ value, onChange, placeholder, id }: { value: string[]; onChange: (v: string[]) => void; placeholder?: string; id?: string }) {
  const [draft, setDraft] = useState("");
  const add = () => {
    const v = draft.trim().replace(/,$/, "");
    if (v && !value.includes(v) && value.length < 20) onChange([...value, v]);
    setDraft("");
  };
  return (
    <div className="mk-token-input">
      {value.map((v) => (
        <span className="mk-token" key={v}>
          {v}
          <button type="button" aria-label={`Remove ${v}`} onClick={() => onChange(value.filter((x) => x !== v))}>
            <X size={12} aria-hidden />
          </button>
        </span>
      ))}
      <input
        id={id}
        value={draft}
        placeholder={value.length ? "" : placeholder}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" || e.key === ",") {
            e.preventDefault();
            add();
          } else if (e.key === "Backspace" && !draft && value.length) {
            onChange(value.slice(0, -1));
          }
        }}
        onBlur={add}
      />
    </div>
  );
}

export function Segmented<T extends string>({ options, value, onChange, label }: { options: { value: T; label: string }[]; value: T; onChange: (v: T) => void; label: string }) {
  return (
    <div className="mk-seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function KeyValues({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="mk-kv">
      {rows.map(([k, v]) => (
        <div key={k} style={{ display: "contents" }}>
          <dt>{k}</dt>
          <dd>{v === null || v === undefined || v === "" ? <span className="mk-muted">—</span> : v}</dd>
        </div>
      ))}
    </dl>
  );
}
