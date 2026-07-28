// Design tokens derived from techcrm-config.json

export const colors = {
  primary: "#185FA5",
  primaryLight: "#E6F1FB",
  primaryDark: "#0C447C",
  success: "#3B6D11",
  successLight: "#EAF3DE",
  warning: "#854F0B",
  warningLight: "#FAEEDA",
  danger: "#A32D2D",
  dangerLight: "#FCEBEB",
  aiPurple: "#534AB7",
  aiLight: "#EEEDFE",
  rpaGreen: "#3B6D11",
  bgPrimary: "#FFFFFF",
  bgSecondary: "#F5F6F8",
  textPrimary: "#141414",
  textSecondary: "#5C5C5C",
  textTertiary: "#9A9A9A",
  border: "#E2E3E5",
} as const;

export type BadgeVariant = "blue" | "green" | "amber" | "red" | "purple";

export const badgeVariants: Record<BadgeVariant, { bg: string; text: string }> = {
  blue: { bg: "#E6F1FB", text: "#185FA5" },
  green: { bg: "#EAF3DE", text: "#3B6D11" },
  amber: { bg: "#FAEEDA", text: "#854F0B" },
  red: { bg: "#FCEBEB", text: "#A32D2D" },
  purple: { bg: "#EEEDFE", text: "#534AB7" },
};

export type FlowNodeType =
  | "trigger"
  | "condition"
  | "ai"
  | "rpa"
  | "action"
  | "log";

export const flowNodeTypes: Record<FlowNodeType, { bg: string; color: string }> = {
  trigger: { bg: "#E6F1FB", color: "#0C447C" },
  condition: { bg: "#FAEEDA", color: "#633806" },
  ai: { bg: "#EEEDFE", color: "#3C3489" },
  rpa: { bg: "#EAF3DE", color: "#27500A" },
  action: { bg: "#EAF3DE", color: "#27500A" },
  log: { bg: "#FAECE7", color: "#4A1B0C" },
};

export type StatusKind = "running" | "scheduled" | "error";

export const statusDotColors: Record<StatusKind, string> = {
  running: "#639922",
  scheduled: "#BA7517",
  error: "#E24B4A",
};

// font size scale (px)
export const fs = {
  xs: 10,
  sm: 11,
  base: 12,
  md: 13,
  lg: 15,
  xl: 18,
  "2xl": 22,
  "3xl": 28,
} as const;
