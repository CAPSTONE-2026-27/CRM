import { ReactNode } from "react";
import {
  colors,
  badgeVariants,
  BadgeVariant,
  flowNodeTypes,
  FlowNodeType,
  statusDotColors,
  StatusKind,
} from "../../tokens";
import { Brain, Bot, ArrowRight, Check } from "lucide-react";

/* ---------------- Badge ---------------- */
export function Badge({
  label,
  variant = "blue",
}: {
  label: string;
  variant?: BadgeVariant;
}) {
  const v = badgeVariants[variant];
  return (
    <span
      style={{
        background: v.bg,
        color: v.text,
        borderRadius: 20,
        padding: "2px 8px",
        fontSize: 11,
        fontWeight: 500,
        lineHeight: 1.5,
        whiteSpace: "nowrap",
      }}
    >
      {label}
    </span>
  );
}

/* ---------------- Button ---------------- */
export function Button({
  label,
  icon: Icon,
  variant = "default",
  onClick,
  full,
}: {
  label: string;
  icon?: React.ComponentType<{ size?: number; color?: string }>;
  variant?: "default" | "primary";
  onClick?: () => void;
  full?: boolean;
}) {
  const isPrimary = variant === "primary";
  return (
    <button
      onClick={onClick}
      style={{
        display: "inline-flex",
        alignItems: "center",
        justifyContent: "center",
        gap: 6,
        background: isPrimary ? colors.primary : "transparent",
        border: `0.5px solid ${isPrimary ? colors.primary : "#C4C5C8"}`,
        color: isPrimary ? "#FFFFFF" : colors.textPrimary,
        borderRadius: 6,
        padding: "7px 14px",
        fontSize: 12,
        fontWeight: 500,
        cursor: "pointer",
        width: full ? "100%" : undefined,
      }}
    >
      {Icon && <Icon size={14} color={isPrimary ? "#FFFFFF" : colors.textSecondary} />}
      {label}
    </button>
  );
}

/* ---------------- Card ---------------- */
export function Card({
  title,
  right,
  children,
  pad = 16,
}: {
  title?: string;
  right?: ReactNode;
  children: ReactNode;
  pad?: number;
}) {
  return (
    <div
      style={{
        background: colors.bgPrimary,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 8,
        padding: pad,
      }}
    >
      {title && (
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 13, fontWeight: 600, color: colors.textPrimary }}>
            {title}
          </span>
          {right}
        </div>
      )}
      {children}
    </div>
  );
}

/* ---------------- MetricCard ---------------- */
export function MetricCard({
  label,
  value,
  subtext,
  valueColor,
  trend,
}: {
  label: string;
  value: string;
  subtext?: string;
  valueColor?: string;
  trend?: "up" | "down";
}) {
  const subColor =
    trend === "up" ? colors.success : trend === "down" ? colors.danger : colors.textTertiary;
  return (
    <div
      style={{
        background: colors.bgSecondary,
        borderRadius: 8,
        padding: 12,
        border: `0.5px solid ${colors.border}`,
      }}
    >
      <div style={{ fontSize: 11, color: colors.textSecondary }}>{label}</div>
      <div
        style={{
          fontSize: 22,
          fontWeight: 500,
          color: valueColor || colors.textPrimary,
          margin: "4px 0 2px",
        }}
      >
        {value}
      </div>
      {subtext && <div style={{ fontSize: 11, color: subColor }}>{subtext}</div>}
    </div>
  );
}

export function MetricGrid({ columns, children }: { columns: number; children: ReactNode }) {
  return (
    <div
      style={{
        display: "grid",
        gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
        gap: 12,
      }}
    >
      {children}
    </div>
  );
}

/* ---------------- Insight boxes ---------------- */
export function AIInsightBox({ text }: { text: string }) {
  return (
    <div
      style={{
        background: colors.aiLight,
        border: "0.5px solid #AFA9EC",
        borderRadius: 6,
        padding: "10px 12px",
        fontSize: 12,
        color: "#3C3489",
        display: "flex",
        gap: 8,
        alignItems: "flex-start",
      }}
    >
      <Brain size={16} color={colors.aiPurple} style={{ flexShrink: 0, marginTop: 1 }} />
      <span>{text}</span>
    </div>
  );
}

export function RPAInsightBox({ text }: { text: string }) {
  return (
    <div
      style={{
        background: colors.successLight,
        border: "0.5px solid #97C459",
        borderRadius: 6,
        padding: "10px 12px",
        fontSize: 12,
        color: "#27500A",
        display: "flex",
        gap: 8,
        alignItems: "flex-start",
      }}
    >
      <Bot size={16} color={colors.rpaGreen} style={{ flexShrink: 0, marginTop: 1 }} />
      <span>{text}</span>
    </div>
  );
}

/* ---------------- Avatar ---------------- */
export function Avatar({
  initials,
  color = colors.primary,
  size = 30,
}: {
  initials: string;
  color?: string;
  size?: number;
}) {
  return (
    <div
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        background: color,
        color: "#FFFFFF",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        fontSize: size < 28 ? 10 : 11,
        fontWeight: 600,
        flexShrink: 0,
      }}
    >
      {initials}
    </div>
  );
}

/* ---------------- StatusDot + BotStatusRow ---------------- */
export function StatusDot({ status }: { status: StatusKind }) {
  return (
    <span
      style={{
        width: 7,
        height: 7,
        borderRadius: "50%",
        background: statusDotColors[status],
        display: "inline-block",
        flexShrink: 0,
      }}
    />
  );
}

export function BotStatusRow({
  name,
  sub,
  status,
}: {
  name: string;
  sub: string;
  status: StatusKind;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        padding: "8px 10px",
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        fontSize: 12,
      }}
    >
      <StatusDot status={status} />
      <div>
        <div style={{ color: colors.textPrimary, fontWeight: 500 }}>{name}</div>
        <div style={{ color: colors.textSecondary, fontSize: 11 }}>{sub}</div>
      </div>
    </div>
  );
}

/* ---------------- Switch + ToggleRow ---------------- */
export function Switch({ on, onChange }: { on: boolean; onChange?: () => void }) {
  return (
    <button
      onClick={onChange}
      style={{
        width: 34,
        height: 18,
        borderRadius: 20,
        background: on ? colors.primary : colors.bgSecondary,
        border: on ? "none" : `0.5px solid ${colors.border}`,
        position: "relative",
        cursor: "pointer",
        flexShrink: 0,
        padding: 0,
      }}
    >
      <span
        style={{
          position: "absolute",
          top: 1.5,
          left: on ? 17 : 1,
          width: 14,
          height: 14,
          borderRadius: "50%",
          background: "#FFFFFF",
          boxShadow: "0 1px 2px rgba(0,0,0,0.2)",
          transition: "left .15s",
        }}
      />
    </button>
  );
}

export function ToggleRow({
  label,
  on,
  onChange,
}: {
  label: string;
  on: boolean;
  onChange?: () => void;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "10px 0",
        borderBottom: `0.5px solid ${colors.border}`,
        fontSize: 12,
        color: colors.textPrimary,
      }}
    >
      <span>{label}</span>
      <Switch on={on} onChange={onChange} />
    </div>
  );
}

/* ---------------- Field (text / select) ---------------- */
export function Field({
  label,
  type = "text",
  value,
  placeholder,
  options,
  onChange,
  required,
}: {
  label: string;
  type?: "text" | "select" | "number";
  value?: string;
  placeholder?: string;
  options?: string[];
  // When provided, the field is a real controlled input and edits are kept.
  // Omit only for decorative/demo fields that are never sent to the backend.
  onChange?: (v: string) => void;
  // Marks the label with a red asterisk. Validation itself lives with the
  // form that owns the field, since only it knows what "valid" means.
  required?: boolean;
}) {
  const inputStyle: React.CSSProperties = {
    width: "100%",
    border: `0.5px solid ${colors.border}`,
    borderRadius: 6,
    padding: "7px 10px",
    fontSize: 12,
    color: colors.textPrimary,
    background: "#FFFFFF",
    outline: "none",
  };
  return (
    <div>
      <label
        style={{
          fontSize: 11,
          fontWeight: 500,
          color: colors.textSecondary,
          display: "block",
          marginBottom: 5,
        }}
      >
        {label}
        {required && (
          <span aria-hidden="true" style={{ color: colors.danger, marginLeft: 3 }}>
            *
          </span>
        )}
      </label>
      {type === "select" ? (
        onChange ? (
          <select style={inputStyle} value={value} onChange={(e) => onChange(e.target.value)}>
            {(options || []).map((o) => (
              <option key={o}>{o}</option>
            ))}
          </select>
        ) : (
          <select style={inputStyle} defaultValue={value}>
            {(options || []).map((o) => (
              <option key={o}>{o}</option>
            ))}
          </select>
        )
      ) : onChange ? (
        <input
          type={type === "number" ? "number" : "text"}
          style={inputStyle}
          value={value ?? ""}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
        />
      ) : (
        <input type={type === "number" ? "number" : "text"} style={inputStyle} defaultValue={value} placeholder={placeholder} />
      )}
    </div>
  );
}

/* ---------------- FlowNode + FlowCanvas ---------------- */
export function FlowNode({
  type,
  title,
  label,
}: {
  type: FlowNodeType;
  title: string;
  label: string;
}) {
  const t = flowNodeTypes[type];
  return (
    <div
      style={{
        background: t.bg,
        color: t.color,
        borderRadius: 6,
        padding: "10px 14px",
        minWidth: 110,
        textAlign: "center",
      }}
    >
      <div style={{ fontSize: 10, opacity: 0.7, fontWeight: 500 }}>{title}</div>
      <div style={{ fontSize: 12, fontWeight: 500, marginTop: 2 }}>{label}</div>
    </div>
  );
}

export function FlowCanvas({
  nodes,
}: {
  nodes: { type: FlowNodeType; title: string; label: string }[];
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 0,
        flexWrap: "wrap",
        rowGap: 12,
        background: colors.bgSecondary,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        padding: 16,
      }}
    >
      {nodes.map((n, i) => (
        <div key={n.title + i} style={{ display: "flex", alignItems: "center" }}>
          <FlowNode type={n.type} title={n.title} label={n.label} />
          {i < nodes.length - 1 && (
            <div style={{ width: 20, display: "flex", justifyContent: "center" }}>
              <ArrowRight size={16} color={colors.textTertiary} />
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

/* ---------------- Stepper ---------------- */
export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <div style={{ display: "flex", alignItems: "center", marginBottom: 4, flexWrap: "wrap", rowGap: 8 }}>
      {steps.map((step, i) => {
        const state = i < current ? "done" : i === current ? "current" : "todo";
        const bg =
          state === "done" ? colors.success : state === "current" ? colors.primary : colors.bgSecondary;
        const fg = state === "todo" ? colors.textTertiary : "#FFFFFF";
        return (
          <div key={step} style={{ display: "flex", alignItems: "center" }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <div
                style={{
                  width: 22,
                  height: 22,
                  borderRadius: "50%",
                  background: bg,
                  color: fg,
                  border: state === "todo" ? `0.5px solid ${colors.border}` : "none",
                  display: "flex",
                  alignItems: "center",
                  justifyContent: "center",
                  fontSize: 11,
                  fontWeight: 600,
                  flexShrink: 0,
                }}
              >
                {state === "done" ? <Check size={13} /> : i + 1}
              </div>
              <span
                style={{
                  fontSize: 12,
                  color: state === "current" ? colors.textPrimary : colors.textSecondary,
                  fontWeight: state === "current" ? 600 : 400,
                  whiteSpace: "nowrap",
                }}
              >
                {step}
              </span>
            </div>
            {i < steps.length - 1 && (
              <div style={{ width: 20, height: 0.5, background: colors.border, margin: "0 12px" }} />
            )}
          </div>
        );
      })}
    </div>
  );
}

/* ---------------- OptionCard ---------------- */
export function OptionCard({
  icon: Icon,
  label,
  sub,
  selected,
  onClick,
}: {
  icon: React.ComponentType<{ size?: number; color?: string }>;
  label: string;
  sub?: string;
  selected?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      onClick={onClick}
      style={{
        flex: 1,
        border: selected ? `2px solid ${colors.primary}` : `0.5px solid ${colors.border}`,
        background: selected ? colors.primaryLight : "#FFFFFF",
        borderRadius: 6,
        padding: 12,
        textAlign: "center",
        cursor: "pointer",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 6,
      }}
    >
      <Icon size={20} color={selected ? colors.primary : colors.textSecondary} />
      <div style={{ fontSize: 12, fontWeight: 500, color: colors.textPrimary }}>{label}</div>
      {sub && <div style={{ fontSize: 11, color: colors.textTertiary }}>{sub}</div>}
    </button>
  );
}

/* ---------------- ProgressBar / PipelineBar ---------------- */
export function ProgressBar({
  label,
  value,
  pct,
  color,
}: {
  label: string;
  value?: string;
  pct: number;
  color: string;
}) {
  return (
    <div style={{ marginBottom: 12 }}>
      <div
        style={{
          display: "flex",
          justifyContent: "space-between",
          fontSize: 12,
          marginBottom: 5,
          color: colors.textPrimary,
        }}
      >
        <span>{label}</span>
        {value && <span style={{ color: colors.textSecondary }}>{value}</span>}
      </div>
      <div
        style={{
          height: 6,
          borderRadius: 20,
          background: colors.bgSecondary,
          overflow: "hidden",
        }}
      >
        <div style={{ width: `${pct}%`, height: "100%", background: color, borderRadius: 20 }} />
      </div>
    </div>
  );
}

/* ---------------- Section helpers ---------------- */
export function Stack({ children, gap = 16 }: { children: ReactNode; gap?: number }) {
  return <div style={{ display: "flex", flexDirection: "column", gap }}>{children}</div>;
}

export function Row({
  avatar,
  avatarColor,
  name,
  sub,
  time,
  badge,
}: {
  avatar?: string;
  avatarColor?: string;
  name: string;
  sub?: string;
  time?: string;
  badge?: { label: string; variant: BadgeVariant };
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "8px 0",
        borderBottom: `0.5px solid ${colors.border}`,
      }}
    >
      {time && (
        <span style={{ fontSize: 11, color: colors.textTertiary, width: 36, flexShrink: 0 }}>
          {time}
        </span>
      )}
      {avatar && <Avatar initials={avatar} color={avatarColor} />}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 12, fontWeight: 500, color: colors.textPrimary }}>{name}</div>
        {sub && <div style={{ fontSize: 11, color: colors.textSecondary }}>{sub}</div>}
      </div>
      {badge && <Badge label={badge.label} variant={badge.variant} />}
    </div>
  );
}
