import { ReactNode } from "react";
import { colors } from "../../tokens";
import { Avatar, Badge } from "./ui";
import { Bell, Search, ArrowLeft, User, Settings, LogOut } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from "../ui/dropdown-menu";

const roleLabel: Record<string, string> = {
  ADMIN: "Admin",
  MANAGER: "Manager",
  SALES_REP: "Sales rep",
  SUPPORT_AGENT: "Support agent",
};

export function Topbar({
  title,
  badge,
  onBack,
  userName,
  userEmail,
  userInitials = "—",
  userRole,
  avatarUrl,
  onViewProfile,
  onOpenSettings,
  onLogout,
}: {
  title: string;
  badge?: string;
  onBack?: () => void;
  userName?: string;
  userEmail?: string;
  userInitials?: string;
  userRole?: string;
  avatarUrl?: string | null;
  onViewProfile?: () => void;
  onOpenSettings?: () => void;
  onLogout?: () => void;
}) {
  return (
    <header
      style={{
        height: 48,
        flexShrink: 0,
        background: colors.bgPrimary,
        borderBottom: `0.5px solid ${colors.border}`,
        display: "flex",
        alignItems: "center",
        padding: "0 20px",
        gap: 10,
      }}
    >
      {onBack && (
        <button
          onClick={onBack}
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "center",
            border: `0.5px solid ${colors.border}`,
            borderRadius: 6,
            width: 28,
            height: 28,
            background: "transparent",
            cursor: "pointer",
          }}
        >
          <ArrowLeft size={15} color={colors.textSecondary} />
        </button>
      )}
      <span style={{ fontSize: 15, fontWeight: 500, color: colors.textPrimary }}>{title}</span>
      {badge && <Badge label={badge} variant="blue" />}
      <div style={{ flex: 1 }} />
      <IconButton>
        <Search size={16} color={colors.textSecondary} />
      </IconButton>
      <IconButton>
        <Bell size={16} color={colors.textSecondary} />
      </IconButton>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button style={{ border: "none", background: "transparent", cursor: "pointer", padding: 0, borderRadius: "50%" }}>
            {avatarUrl ? (
              <img src={avatarUrl} alt="" width={28} height={28} style={{ borderRadius: "50%", objectFit: "cover" }} />
            ) : (
              <Avatar initials={userInitials} color={colors.primary} size={28} />
            )}
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" style={{ minWidth: 220 }}>
          <DropdownMenuLabel>
            <div style={{ display: "flex", alignItems: "center", gap: 6 }}>
              <span style={{ fontSize: 12, fontWeight: 600, color: colors.textPrimary }}>{userName ?? "—"}</span>
              {userRole && <Badge label={roleLabel[userRole] ?? userRole} variant={userRole === "ADMIN" ? "purple" : "blue"} />}
            </div>
            <div style={{ fontSize: 11, color: colors.textSecondary, fontWeight: 400 }}>{userEmail ?? ""}</div>
          </DropdownMenuLabel>
          <DropdownMenuItem onClick={onViewProfile}>
            <User size={14} />
            View profile
          </DropdownMenuItem>
          <DropdownMenuItem onClick={onOpenSettings}>
            <Settings size={14} />
            Settings
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={onLogout} variant="destructive">
            <LogOut size={14} />
            Log out
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </header>
  );
}

function IconButton({ children }: { children: ReactNode }) {
  return (
    <button
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        width: 30,
        height: 30,
        borderRadius: 6,
        border: "none",
        background: "transparent",
        cursor: "pointer",
      }}
    >
      {children}
    </button>
  );
}
