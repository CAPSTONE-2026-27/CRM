import { createContext, useContext, useState, useRef, useEffect } from "react";
import { toast } from "sonner";
import { colors, BadgeVariant } from "../tokens";
import {
  MetricCard,
  MetricGrid,
  Card,
  AIInsightBox,
  Badge,
  Button,
  Avatar,
  BotStatusRow,
  Row,
  ProgressBar,
  Stack,
  ToggleRow,
  Field,
} from "../components/crm/ui";
import { KanbanBoard, Column } from "../components/crm/KanbanBoard";
import { LeadOutputModal } from "../components/crm/LeadOutputModal";
import { WorkflowBuilder } from "../components/crm/WorkflowBuilder";
import { Filter, Search, Plus, Send, Brain, Zap, Upload, Download, Trash2, X } from "lucide-react";
import { WizardId, MISSING_FIELD_LABELS } from "./Wizards";
import {
  useDashboardSummary,
  useLeads,
  useLeadStats,
  useDeals,
  useUpdateDealStage,
  useAccounts,
  useContacts,
  useCases,
  useCampaigns,
  useRpaBots,
  useRpaBotRuns,
  useWorkflows,
  useActivateWorkflow,
  useReportingSummary,
  useSecuritySummary,
  useAuditLog,
  useUsers,
  useDeleteUser,
  useSetUserStatus,
  useResetUserPassword,
  useDeleteLead,
  useBulkDeleteLeads,
  useDeleteRpaBot,
  useImportLeads,
  useUpdateLead,
  useUpdateUser,
  type LeadImportResult,
} from "../lib/queries";
import { streamCopilotChat, downloadFile, type CopilotMessage } from "../lib/apiClient";
import type { Lead, RpaBotRun, UserRow } from "../lib/types";
import { useAuth } from "../lib/auth";
import { PERMISSION_CATALOG, ROLE_DEFAULT_PERMISSIONS, type Role } from "../components/crm/Sidebar";

type Nav = (w: WizardId) => void;

/* ============ shared local helpers ============ */
const TableContext = createContext<string>("");

function ActionBar({
  left,
  right,
}: {
  left: { label: string; icon?: React.ComponentType<{ size?: number; color?: string }> }[];
  right: { label: string; icon?: React.ComponentType<{ size?: number; color?: string }>; variant?: "default" | "primary"; onClick?: () => void }[];
}) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
      <div style={{ display: "flex", gap: 8 }}>
        {left.map((b) => (
          <Button key={b.label} label={b.label} icon={b.icon} />
        ))}
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        {right.map((b) => (
          <Button key={b.label} label={b.label} icon={b.icon} variant={b.variant} onClick={b.onClick} />
        ))}
      </div>
    </div>
  );
}

function Table({ headers, cols, children }: { headers: React.ReactNode[]; cols: string; children: React.ReactNode }) {
  return (
    <div>
      <div style={{ display: "grid", gridTemplateColumns: cols, gap: 8, padding: "0 0 8px", borderBottom: `0.5px solid ${colors.border}` }}>
        {headers.map((h, i) => (
          <span key={i} style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary }}>{h}</span>
        ))}
      </div>
      <TableContext.Provider value={cols}>{children}</TableContext.Provider>
    </div>
  );
}

function TableRow({ children, onClick }: { children: React.ReactNode; onClick?: () => void }) {
  const cols = useContext(TableContext);
  return (
    <div
      onClick={onClick}
      style={{
        display: "grid", gridTemplateColumns: cols, gap: 8, padding: "9px 0",
        borderBottom: `0.5px solid ${colors.border}`, alignItems: "center", fontSize: 12,
        cursor: onClick ? "pointer" : undefined,
      }}
    >
      {children}
    </div>
  );
}

function Cell({ children, muted }: { children: React.ReactNode; muted?: boolean }) {
  return <div style={{ color: muted ? colors.textSecondary : colors.textPrimary, fontSize: 12 }}>{children}</div>;
}

/* ---- search + filter toolbar helpers ---- */
function SearchInput({
  value,
  onChange,
  placeholder = "Search…",
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 6,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        padding: "0 10px",
        height: 32,
        background: "#FFFFFF",
        minWidth: 220,
      }}
    >
      <Search size={14} color={colors.textTertiary} />
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        style={{
          border: "none",
          outline: "none",
          fontSize: 12,
          flex: 1,
          background: "transparent",
          color: colors.textPrimary,
          fontFamily: "inherit",
        }}
      />
    </div>
  );
}

function FilterSelect({
  value,
  onChange,
  options,
}: {
  value: string;
  onChange: (v: string) => void;
  options: string[];
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 6,
        border: `0.5px solid ${colors.border}`,
        borderRadius: 6,
        padding: "0 8px 0 10px",
        height: 32,
        background: "#FFFFFF",
      }}
    >
      <Filter size={14} color={colors.textTertiary} />
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        style={{
          border: "none",
          outline: "none",
          fontSize: 12,
          background: "transparent",
          color: colors.textPrimary,
          fontFamily: "inherit",
          cursor: "pointer",
        }}
      >
        {options.map((o) => (
          <option key={o} value={o}>{o}</option>
        ))}
      </select>
    </div>
  );
}

function EmptyRow() {
  return (
    <div style={{ padding: "16px 0", textAlign: "center", fontSize: 12, color: colors.textTertiary }}>
      No matching results
    </div>
  );
}

/* generic empty-state placeholder shown until backend data is connected */
function EmptyState({ message }: { message: string }) {
  return (
    <div style={{ padding: "32px 0", textAlign: "center", fontSize: 12, color: colors.textTertiary }}>
      {message}
    </div>
  );
}

function LoadingState() {
  return (
    <div style={{ padding: "32px 0", textAlign: "center", fontSize: 12, color: colors.textTertiary }}>
      Loading…
    </div>
  );
}

function formatCurrency(value: string | number | null | undefined): string {
  const n = Number(value ?? 0);
  return `$${n.toLocaleString(undefined, { maximumFractionDigits: 0 })}`;
}

function initialsFromName(name: string): string {
  const parts = name.trim().split(/\s+/);
  return ((parts[0]?.[0] ?? "") + (parts[1]?.[0] ?? "")).toUpperCase() || "—";
}

const leadStatusVariant: Record<string, BadgeVariant> = { HOT: "green", WARM: "blue", NEW: "amber", COLD: "purple" };
const botStatusMap: Record<string, "running" | "scheduled" | "error"> = {
  RUNNING: "running",
  SCHEDULED: "scheduled",
  ERROR: "error",
  SUCCESS: "running",
};

/* ============ S01 Dashboard ============ */
export function Dashboard() {
  const { data, isLoading } = useDashboardSummary();

  const recentLeads = data?.recentLeads ?? [];
  const pipelineStages = data?.pipelineByStage ?? [];
  const botActivity = data?.recentBotRuns ?? [];
  const maxStageValue = Math.max(1, ...pipelineStages.map((p) => Number(p._sum.value ?? 0)));

  return (
    <Stack>
      <MetricGrid columns={4}>
        <MetricCard label="Total leads" value={isLoading ? "—" : String(data?.metrics.totalLeads ?? 0)} />
        <MetricCard label="Pipeline value" value={isLoading ? "—" : formatCurrency(data?.metrics.pipelineValue)} />
        <MetricCard label="Open cases" value={isLoading ? "—" : String(data?.metrics.openCases ?? 0)} />
        <MetricCard
          label="RPA bots active"
          value={isLoading ? "—" : `${data?.metrics.rpaBotsActive ?? 0}/${data?.metrics.rpaBotsTotal ?? 0}`}
        />
      </MetricGrid>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
        <Card title="Recent leads">
          {isLoading && <LoadingState />}
          {!isLoading &&
            recentLeads.map((r) => (
              <Row
                key={r.id}
                avatar={initialsFromName(r.fullName)}
                name={r.fullName}
                sub={`${r.company}${r.estimatedDealValue ? " · " + formatCurrency(r.estimatedDealValue) : ""}`}
                badge={{ label: r.status, variant: leadStatusVariant[r.status] ?? "blue" }}
              />
            ))}
          {!isLoading && recentLeads.length === 0 && <EmptyState message="No leads yet" />}
        </Card>
        <Card title="Pipeline stages">
          {isLoading && <LoadingState />}
          {!isLoading &&
            pipelineStages.map((p) => (
              <ProgressBar
                key={p.stage}
                label={`${p.stage.replace(/_/g, " ")} · ${p._count._all} deals`}
                value={formatCurrency(p._sum.value)}
                pct={Math.round((Number(p._sum.value ?? 0) / maxStageValue) * 100)}
                color={colors.primary}
              />
            ))}
          {!isLoading && pipelineStages.length === 0 && <EmptyState message="No pipeline data yet" />}
        </Card>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
        <Card title="Deal coach insights">
          <EmptyState message="Ask the deal coach for insights about leads, deals, and cases" />
        </Card>
        <Card title="RPA bot activity">
          {isLoading && <LoadingState />}
          <Stack gap={8}>
            {!isLoading &&
              botActivity.map((b) => (
                <BotStatusRow
                  key={b.id}
                  name={b.bot?.name ?? "Bot"}
                  sub={`${b.status === "SUCCESS" ? "Completed" : b.status} · ${b.tasksCompleted} task(s)`}
                  status={botStatusMap[b.status] ?? "scheduled"}
                />
              ))}
          </Stack>
          {!isLoading && botActivity.length === 0 && <EmptyState message="No bot activity yet" />}
        </Card>
      </div>
    </Stack>
  );
}

/* ============ S02 Lead Management ============ */
export const scoreVariant = (score: number | null | undefined): BadgeVariant => {
  if (score == null) return "purple";
  if (score >= 80) return "green";
  if (score >= 50) return "blue";
  return "amber";
};

const LEADS_PAGE_SIZE = 20;

export function Leads({ onNavigate }: { onNavigate: Nav }) {
  const [query, setQuery] = useState("");
  const [debouncedQuery, setDebouncedQuery] = useState("");
  const [status, setStatus] = useState("All statuses");
  const [page, setPage] = useState(0);

  // Search debounces 300ms before becoming a query param — dropdown filters
  // (status) apply immediately since they're a single click, not typing.
  useEffect(() => {
    const t = setTimeout(() => setDebouncedQuery(query.trim()), 300);
    return () => clearTimeout(t);
  }, [query]);

  // Any filter change invalidates the current page position.
  useEffect(() => {
    setPage(0);
  }, [debouncedQuery, status]);

  const statusParam = status === "All statuses" ? undefined : status.toUpperCase();
  const { data: leadsPage, isLoading } = useLeads({
    q: debouncedQuery || undefined,
    status: statusParam,
    page,
    size: LEADS_PAGE_SIZE,
  });
  const { data: stats } = useLeadStats();
  const { data: users } = useUsers();
  const rows = leadsPage?.content ?? [];
  const totalElements = leadsPage?.totalElements ?? 0;
  const totalPages = leadsPage?.totalPages ?? 0;
  const usersById = new Map((users ?? []).map((u) => [u.id, u]));
  const [selectedLead, setSelectedLead] = useState<Lead | null>(null);
  const [editingLead, setEditingLead] = useState<Lead | null>(null);
  const [outputLead, setOutputLead] = useState<Lead | null>(null);
  // Mirrors the backend rule in guardLeadAssignment — only these roles may
  // change lead ownership; everyone else can still edit the lead's details.
  const { user: currentUser } = useAuth();
  const canAssignLeads = currentUser?.role === "ADMIN" || currentUser?.role === "MANAGER";
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const deleteLead = useDeleteLead();
  const bulkDeleteLeads = useBulkDeleteLeads();
  const importLeads = useImportLeads();
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [lastImportResult, setLastImportResult] = useState<LeadImportResult | null>(null);
  const [isExporting, setIsExporting] = useState(false);

  // Exports every field of every lead matching the current search/filter —
  // so an unfiltered view (the default) downloads the whole lead database.
  const handleExportCsv = async () => {
    setIsExporting(true);
    try {
      const params = new URLSearchParams();
      if (debouncedQuery) params.set("q", debouncedQuery);
      if (statusParam) params.set("status", statusParam);
      const qs = params.toString();
      await downloadFile(`/leads/export${qs ? `?${qs}` : ""}`, "leads.csv");
      toast.success("Leads exported to CSV");
    } catch (err) {
      toast.error("Export failed", { description: err instanceof Error ? err.message : undefined });
    } finally {
      setIsExporting(false);
    }
  };

  const handleCsvFile = (file: File) => {
    const reader = new FileReader();
    reader.onload = () => {
      importLeads.mutate(String(reader.result), {
        onSuccess: (result) => {
          setLastImportResult(result);
          toast.success(`Imported ${result.imported} lead(s)`, {
            description: "AI scoring is running in the background. See the import summary below for details.",
          });
        },
        onError: (err) => toast.error("Import failed", { description: err instanceof Error ? err.message : undefined }),
      });
    };
    reader.readAsText(file);
  };

  // Selection is scoped to the current page — matches what "select all" can
  // actually mean once the list is server-paginated.
  const allPageSelected = rows.length > 0 && rows.every((r) => selectedIds.has(r.id));
  const toggleSelectAll = () => {
    setSelectedIds(allPageSelected ? new Set() : new Set(rows.map((r) => r.id)));
  };
  const toggleSelect = (id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleBulkRemove = () => {
    const count = selectedIds.size;
    if (!window.confirm(`Remove ${count} lead(s)? This can't be undone.`)) return;
    bulkDeleteLeads.mutate(Array.from(selectedIds), {
      onError: (err) => toast.error("Failed to remove leads", { description: err instanceof Error ? err.message : undefined }),
      onSuccess: (result) => {
        toast.success(`${result.deleted} lead(s) removed`);
        setSelectedIds(new Set());
      },
    });
  };

  const handleRemove = (id: string, name: string) => {
    if (!window.confirm(`Remove lead "${name}"? This can't be undone.`)) return;
    deleteLead.mutate(id, {
      onError: (err) => toast.error("Failed to remove lead", { description: err instanceof Error ? err.message : undefined }),
      onSuccess: () => toast.success(`${name} removed`),
    });
  };

  return (
    <Stack>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div style={{ display: "flex", gap: 8 }}>
          <SearchInput value={query} onChange={setQuery} placeholder="Search name, company, product…" />
          <FilterSelect value={status} onChange={setStatus} options={["All statuses", "Hot", "Warm", "Cold", "New"]} />
        </div>
        <div style={{ display: "flex", gap: 8 }}>
          {selectedIds.size > 0 && (
            <Button
              label={bulkDeleteLeads.isPending ? "Removing…" : `Remove selected (${selectedIds.size})`}
              icon={Trash2}
              onClick={handleBulkRemove}
            />
          )}
          <input
            ref={fileInputRef}
            type="file"
            accept=".csv,text/csv"
            style={{ display: "none" }}
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleCsvFile(file);
              e.target.value = "";
            }}
          />
          <Button
            label={importLeads.isPending ? "Importing…" : "Import CSV"}
            icon={Upload}
            onClick={() => fileInputRef.current?.click()}
          />
          <Button
            label={isExporting ? "Exporting…" : "Export CSV"}
            icon={Download}
            onClick={handleExportCsv}
          />
          <Button label="Add lead" icon={Plus} variant="primary" onClick={() => onNavigate("F03")} />
        </div>
      </div>
      {lastImportResult && (
        <ImportResultsPanel result={lastImportResult} onDismiss={() => setLastImportResult(null)} />
      )}
      <MetricGrid columns={4}>
        <MetricCard label="Total leads" value={!stats ? "—" : String(stats.totalLeads)} />
        <MetricCard label="AI scored" value={!stats ? "—" : String(stats.aiScored)} />
        <MetricCard label="CSV imported" value={!stats ? "—" : String(stats.csvImported)} />
        <MetricCard label="Bot imported" value={!stats ? "—" : String(stats.botImported)} />
      </MetricGrid>
      <Card title={`Lead list (${totalElements})`}>
        <Table
          headers={[
            <input type="checkbox" checked={allPageSelected} onChange={toggleSelectAll} style={{ cursor: "pointer" }} />,
            "Name / Company", "Product", "Source", "AI Score", "Status", "Assigned", "",
          ]}
          cols="0.4fr 2fr 1.3fr 1fr 0.8fr 0.8fr 1fr 1.5fr"
        >
          {rows.map((r) => (
            <TableRow key={r.id} onClick={() => setSelectedLead(r)}>
              <Cell>
                <input
                  type="checkbox"
                  checked={selectedIds.has(r.id)}
                  onClick={(e) => e.stopPropagation()}
                  onChange={() => toggleSelect(r.id)}
                  style={{ cursor: "pointer" }}
                />
              </Cell>
              <Cell>
                <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                  <Avatar initials={initialsFromName(r.fullName)} />
                  <div>
                    <div style={{ fontWeight: 500 }}>{r.fullName}</div>
                    <div style={{ fontSize: 11, color: colors.textSecondary }}>{r.company}</div>
                  </div>
                </div>
              </Cell>
              <Cell>{r.product ?? "—"}</Cell>
              <Cell muted>{r.sourceChannel ?? "—"}</Cell>
              <Cell>{r.aiScore != null ? <Badge label={String(r.aiScore)} variant={scoreVariant(r.aiScore)} /> : "—"}</Cell>
              <Cell><Badge label={r.status} variant={leadStatusVariant[r.status] ?? "blue"} /></Cell>
              <Cell muted>{r.assignedToId ? (usersById.get(r.assignedToId)?.fullName ?? "Assigned") : "Unassigned"}</Cell>
              <Cell>
                <div style={{ display: "flex", gap: 10 }}>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setEditingLead(r);
                    }}
                    style={{ border: "none", background: "transparent", color: colors.primary, fontSize: 12, cursor: "pointer", padding: 0 }}
                  >
                    Edit
                  </button>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      setOutputLead(r);
                    }}
                    style={{ border: "none", background: "transparent", color: colors.aiPurple, fontSize: 12, cursor: "pointer", padding: 0, whiteSpace: "nowrap" }}
                  >
                    Lead output
                  </button>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      handleRemove(r.id, r.fullName);
                    }}
                    style={{ border: "none", background: "transparent", color: colors.danger, fontSize: 12, cursor: "pointer", padding: 0 }}
                  >
                    Remove
                  </button>
                </div>
              </Cell>
            </TableRow>
          ))}
          {isLoading && <LoadingState />}
          {!isLoading && rows.length === 0 && <EmptyState message={totalElements === 0 ? "No leads yet" : "No matching results"} />}
        </Table>
        {totalPages > 1 && (
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", paddingTop: 12 }}>
            <span style={{ fontSize: 11, color: colors.textSecondary }}>
              Page {page + 1} of {totalPages}
            </span>
            <div style={{ display: "flex", gap: 8 }}>
              {page > 0 && <Button label="Previous" onClick={() => setPage((p) => p - 1)} />}
              {page < totalPages - 1 && <Button label="Next" onClick={() => setPage((p) => p + 1)} />}
            </div>
          </div>
        )}
      </Card>
      {selectedLead && (
        <LeadDetailModal
          lead={selectedLead}
          assignee={selectedLead.assignedToId ? usersById.get(selectedLead.assignedToId) : undefined}
          onEdit={() => {
            setEditingLead(selectedLead);
            setSelectedLead(null);
          }}
          onClose={() => setSelectedLead(null)}
        />
      )}
      {editingLead && (
        <EditLeadModal
          lead={editingLead}
          users={users ?? []}
          canAssign={canAssignLeads}
          onClose={() => setEditingLead(null)}
        />
      )}
      {outputLead && <LeadOutputModal lead={outputLead} onClose={() => setOutputLead(null)} />}
    </Stack>
  );
}

function ImportResultsPanel({ result, onDismiss }: { result: LeadImportResult; onDismiss: () => void }) {
  return (
    <div style={{ background: "#FFFFFF", border: `0.5px solid ${colors.border}`, borderRadius: 8, padding: 16 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 10 }}>
        <div>
          <div style={{ fontSize: 14, fontWeight: 600, marginBottom: 2 }}>CSV import summary</div>
          <div style={{ fontSize: 12, color: colors.textSecondary }}>
            {result.imported} imported · {result.warnings.length} with missing fields · {result.failed.length} skipped
          </div>
        </div>
        <button onClick={onDismiss} style={{ border: "none", background: "transparent", cursor: "pointer", padding: 4, color: colors.textSecondary }}>
          <X size={16} />
        </button>
      </div>

      {result.warnings.length > 0 && (
        <div style={{ marginBottom: result.failed.length > 0 ? 12 : 0 }}>
          <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 6 }}>
            Imported with missing fields ({result.warnings.length})
          </div>
          <div style={{ maxHeight: 160, overflowY: "auto", border: `0.5px solid ${colors.border}`, borderRadius: 6 }}>
            {result.warnings.map((w) => (
              <div key={w.row} style={{ display: "flex", justifyContent: "space-between", padding: "7px 10px", fontSize: 12, borderBottom: `0.5px solid ${colors.border}` }}>
                <span style={{ color: colors.textPrimary }}>Row {w.row}</span>
                <span style={{ color: "#7A5B00" }}>{w.missingFields.map((f) => MISSING_FIELD_LABELS[f] ?? f).join(", ")}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {result.failed.length > 0 && (
        <div>
          <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 6 }}>
            Skipped ({result.failed.length})
          </div>
          <div style={{ maxHeight: 200, overflowY: "auto", border: `0.5px solid ${colors.border}`, borderRadius: 6 }}>
            {result.failed.map((f, i) => (
              <div key={`${f.row}-${i}`} style={{ display: "flex", justifyContent: "space-between", gap: 12, padding: "7px 10px", fontSize: 12, borderBottom: `0.5px solid ${colors.border}` }}>
                <span style={{ color: colors.textPrimary, flexShrink: 0 }}>Row {f.row}</span>
                <span style={{ color: colors.danger, textAlign: "right" }}>{f.reason}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

const LEAD_STATUS_OPTIONS = ["NEW", "WARM", "HOT", "COLD"];
const UNASSIGNED = "— Unassigned —";

// Lead ownership is set by hand here; nothing assigns leads automatically.
// The assignment control only renders for managers/admins, and the backend
// enforces the same rule (see guardLeadAssignment in server/src/routes/crm.ts).
function EditLeadModal({
  lead,
  users,
  canAssign,
  onClose,
}: {
  lead: Lead;
  users: UserRow[];
  canAssign: boolean;
  onClose: () => void;
}) {
  const [form, setForm] = useState({
    fullName: lead.fullName ?? "",
    company: lead.company ?? "",
    email: lead.email ?? "",
    phone: lead.phone ?? "",
    industry: lead.industry ?? "",
    product: lead.product ?? "",
    estimatedDealValue: lead.estimatedDealValue != null ? String(lead.estimatedDealValue) : "",
    sourceChannel: lead.sourceChannel ?? "",
    status: lead.status ?? "NEW",
    notes: lead.notes ?? "",
  });
  const set = (key: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [key]: v }));

  const salesReps = users.filter((u) => u.role === "SALES_REP");
  const initialAssignee = salesReps.find((u) => u.id === lead.assignedToId)?.fullName ?? UNASSIGNED;
  const [assigneeName, setAssigneeName] = useState(initialAssignee);

  const updateLead = useUpdateLead();

  const save = () => {
    if (!form.fullName.trim() || !form.company.trim()) {
      toast.error("Full name and company are required");
      return;
    }
    if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
      toast.error("Enter a valid email address, or leave it blank");
      return;
    }
    const dealValue = parseFloat(form.estimatedDealValue.replace(/[^0-9.]/g, ""));

    const patch: Record<string, unknown> = {
      fullName: form.fullName.trim(),
      company: form.company.trim(),
      email: form.email.trim() || undefined,
      phone: form.phone.trim() || undefined,
      industry: form.industry.trim() || undefined,
      product: form.product.trim() || undefined,
      estimatedDealValue: Number.isFinite(dealValue) ? dealValue : undefined,
      sourceChannel: form.sourceChannel.trim() || undefined,
      status: form.status,
      notes: form.notes.trim() || undefined,
    };

    // Only send assignedToId when this user is allowed to change it — the
    // backend rejects the field outright for other roles.
    if (canAssign) {
      patch.assignedToId = assigneeName === UNASSIGNED ? null : salesReps.find((u) => u.fullName === assigneeName)?.id ?? null;
    }

    updateLead.mutate(
      { id: lead.id, ...patch },
      {
        onSuccess: () => {
          toast.success(`${form.fullName} updated`);
          onClose();
        },
        onError: (err) => toast.error("Failed to update lead", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}
    >
      <div onClick={(e) => e.stopPropagation()} style={{ background: "#FFFFFF", borderRadius: 8, width: 560, maxHeight: "85vh", overflowY: "auto", padding: 20 }}>
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 2 }}>Edit lead</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 16 }}>
          Created {new Date(lead.createdAt).toLocaleString()} · Last modified {new Date(lead.updatedAt).toLocaleString()}
        </div>

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
          <Field required label="Full name" value={form.fullName} onChange={set("fullName")} />
          <Field required label="Company" value={form.company} onChange={set("company")} />
          <Field label="Email" value={form.email} onChange={set("email")} placeholder="name@company.com" />
          <Field label="Phone" value={form.phone} onChange={set("phone")} placeholder="+1 (555) 012-3456" />
          <Field label="Industry" value={form.industry} onChange={set("industry")} />
          <Field label="Product" value={form.product} onChange={set("product")} />
          <Field label="Estimated deal value" value={form.estimatedDealValue} onChange={set("estimatedDealValue")} placeholder="31000" />
          <Field label="Source channel" value={form.sourceChannel} onChange={set("sourceChannel")} />
          <Field label="Status" type="select" value={form.status} onChange={set("status")} options={LEAD_STATUS_OPTIONS} />
          {canAssign && (
            <Field
              label="Assigned to"
              type="select"
              value={assigneeName}
              onChange={setAssigneeName}
              options={[UNASSIGNED, ...salesReps.map((u) => u.fullName)]}
            />
          )}
        </div>

        {!canAssign && (
          <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 10 }}>
            Only managers and admins can change who a lead is assigned to.
          </div>
        )}
        {canAssign && salesReps.length === 0 && (
          <div style={{ fontSize: 11, color: colors.textSecondary, marginTop: 10 }}>
            No sales representatives in your organization yet — add one from User management to assign this lead.
          </div>
        )}

        <div style={{ marginTop: 12 }}>
          <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>Notes</label>
          <textarea
            value={form.notes}
            onChange={(e) => set("notes")(e.target.value)}
            style={{ width: "100%", border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: "8px 10px", fontSize: 12, minHeight: 64, resize: "vertical", outline: "none", fontFamily: "inherit" }}
          />
        </div>

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 16 }}>
          <Button label="Cancel" onClick={onClose} />
          <Button label={updateLead.isPending ? "Saving…" : "Save changes"} variant="primary" onClick={save} />
        </div>
      </div>
    </div>
  );
}

function LeadDetailModal({
  lead,
  assignee,
  onEdit,
  onClose,
}: {
  lead: Lead;
  assignee: UserRow | undefined;
  onEdit: () => void;
  onClose: () => void;
}) {
  const fields: [string, string][] = [
    ["Email", lead.email ?? "—"],
    ["Phone", lead.phone ?? "—"],
    ["Industry", lead.industry ?? "—"],
    ["Employee count", lead.employeeCount ?? "—"],
    ["Product", lead.product ?? "—"],
    ["Estimated deal value", lead.estimatedDealValue != null ? formatCurrency(lead.estimatedDealValue) : "—"],
    ["Source channel", lead.sourceChannel ?? "—"],
    ["Capture method", lead.captureMethod ?? "—"],
    ["Assigned to", assignee?.fullName ?? "Unassigned"],
    ["Created", new Date(lead.createdAt).toLocaleString()],
    ["Last modified", new Date(lead.updatedAt).toLocaleString()],
  ];

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}
    >
      <div onClick={(e) => e.stopPropagation()} style={{ background: "#FFFFFF", borderRadius: 8, width: 520, maxHeight: "85vh", overflowY: "auto", padding: 20 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 4 }}>
          <Avatar initials={initialsFromName(lead.fullName)} />
          <div>
            <div style={{ fontSize: 15, fontWeight: 600 }}>{lead.fullName}</div>
            <div style={{ fontSize: 12, color: colors.textSecondary }}>{lead.company}</div>
          </div>
        </div>

        <div style={{ display: "flex", gap: 8, margin: "14px 0" }}>
          <Badge label={lead.status} variant={leadStatusVariant[lead.status] ?? "blue"} />
          {assignee && <Badge label={`Assigned to ${assignee.fullName}`} variant="blue" />}
        </div>

        <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 8 }}>
          AI scoring
        </div>
        {lead.aiScore != null ? (
          <div style={{ display: "flex", alignItems: "center", gap: 14, background: colors.aiLight, border: "0.5px solid #AFA9EC", borderRadius: 8, padding: 14, marginBottom: 16 }}>
            <div style={{ width: 48, height: 48, borderRadius: "50%", background: "#FFFFFF", border: `2px solid ${colors.aiPurple}`, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 16, fontWeight: 600, color: colors.aiPurple, flexShrink: 0 }}>
              {lead.aiScore}
            </div>
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 13, fontWeight: 600, color: "#3C3489", marginBottom: 2 }}>{lead.aiScoreLabel ?? "Scored"}</div>
              <div style={{ fontSize: 12, color: "#3C3489" }}>{lead.aiScoreReason ?? "No reason recorded."}</div>
            </div>
          </div>
        ) : (
          <div style={{ fontSize: 12, color: colors.textSecondary, padding: "8px 0", marginBottom: 16 }}>
            This lead hasn't been scored yet — AI scoring may have been unavailable when it was created or last edited.
          </div>
        )}

        <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 4 }}>
          Details
        </div>
        <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden", marginBottom: 16 }}>
          {fields.map(([label, value], i) => (
            <div key={label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
              <span style={{ color: colors.textSecondary }}>{label}</span>
              <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{value}</span>
            </div>
          ))}
        </div>

        {lead.notes && (
          <>
            <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 4 }}>
              Notes
            </div>
            <div style={{ fontSize: 12, color: colors.textPrimary, marginBottom: 16, whiteSpace: "pre-wrap" }}>{lead.notes}</div>
          </>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8 }}>
          <Button label="Close" onClick={onClose} />
          <Button label="Edit lead" variant="primary" onClick={onEdit} />
        </div>
      </div>
    </div>
  );
}

/* ============ S03 Sales Pipeline Kanban ============ */
const stageToColumnId: Record<string, string> = {
  PROSPECTING: "prospecting",
  QUALIFICATION: "qualification",
  PROPOSAL: "proposal",
  NEGOTIATION: "negotiation",
  CLOSED_WON: "won",
  CLOSED_LOST: "lost",
};

const columnIdToStage: Record<string, string> = {
  prospecting: "PROSPECTING",
  qualification: "QUALIFICATION",
  proposal: "PROPOSAL",
  negotiation: "NEGOTIATION",
  won: "CLOSED_WON",
  lost: "CLOSED_LOST",
};

export function Pipeline({ onNavigate }: { onNavigate: Nav }) {
  const { data: deals, isLoading } = useDeals();
  const updateDealStage = useUpdateDealStage();

  const columns: Column[] = [
    { id: "prospecting", title: "Prospecting", cards: [] },
    { id: "qualification", title: "Qualification", cards: [] },
    { id: "proposal", title: "Proposal", cards: [] },
    { id: "negotiation", title: "Negotiation", cards: [] },
    { id: "won", title: "Closed won", cards: [] },
    { id: "lost", title: "Closed lost", cards: [] },
  ];

  for (const deal of deals ?? []) {
    const columnId = stageToColumnId[deal.stage];
    const column = columns.find((c) => c.id === columnId);
    if (!column) continue;
    const isClosed = deal.stage === "CLOSED_WON" || deal.stage === "CLOSED_LOST";
    column.cards.push({
      id: deal.id,
      name: deal.name,
      value: formatCurrency(deal.value),
      probability: isClosed ? (deal.stage === "CLOSED_WON" ? "Won" : "Lost") : `${deal.probability ?? 0}%`,
      variant: (deal.probability ?? 0) >= 70 ? "green" : (deal.probability ?? 0) >= 40 ? "blue" : "amber",
    });
  }

  const pool: Column = { id: "unassigned", title: "Unassigned deals", cards: [] };

  return (
    <Stack>
      <ActionBar left={[]} right={[{ label: "New deal", icon: Plus, variant: "primary", onClick: () => onNavigate("F07") }]} />
      {isLoading ? (
        <LoadingState />
      ) : (
        <KanbanBoard
          initialColumns={columns}
          initialPool={pool}
          onCardMoved={(cardId, toColumn) => {
            const stage = columnIdToStage[toColumn];
            if (stage) updateDealStage.mutate({ id: cardId, stage });
          }}
        />
      )}
    </Stack>
  );
}

/* ============ S04 Accounts 360 ============ */
export function Accounts({ onNavigate }: { onNavigate: Nav }) {
  const { data: accounts, isLoading: accountsLoading } = useAccounts();
  const { data: contacts, isLoading: contactsLoading } = useContacts();

  return (
    <Stack>
      <ActionBar left={[]} right={[{ label: "Add account", icon: Plus, variant: "primary", onClick: () => onNavigate("F04") }]} />
      <Card title={`Accounts (${accounts?.length ?? 0})`}>
        {accountsLoading && <LoadingState />}
        {!accountsLoading &&
          (accounts ?? []).map((a) => (
            <Row
              key={a.id}
              avatar={initialsFromName(a.name)}
              name={a.name}
              sub={`${a.industry ?? "—"}${a.relationshipValue ? " · " + formatCurrency(a.relationshipValue) : ""}`}
              badge={a.aiSentimentScore != null ? { label: `Sentiment ${a.aiSentimentScore}%`, variant: a.aiSentimentScore >= 50 ? "green" : "amber" } : undefined}
            />
          ))}
        {!accountsLoading && (accounts ?? []).length === 0 && <EmptyState message="No accounts yet. Add an account to get started." />}
      </Card>
      <Card title="Key contacts">
        {contactsLoading && <LoadingState />}
        {!contactsLoading &&
          (contacts ?? []).map((c) => (
            <Row
              key={c.id}
              avatar={initialsFromName(c.fullName)}
              name={c.fullName}
              sub={c.jobTitle ?? ""}
              badge={c.isPrimary ? { label: "Primary", variant: "green" } : { label: "Secondary", variant: "blue" }}
            />
          ))}
        {!contactsLoading && (contacts ?? []).length === 0 && <EmptyState message="No contacts yet" />}
      </Card>
    </Stack>
  );
}

/* ============ S05 Cases & Tickets ============ */
const casePriorityVariant: Record<string, BadgeVariant> = { LOW: "blue", MEDIUM: "amber", HIGH: "red", CRITICAL: "red" };
const caseStatusVariant: Record<string, BadgeVariant> = {
  OPEN: "blue",
  IN_PROGRESS: "amber",
  ESCALATED: "red",
  RESOLVED: "green",
  CLOSED: "purple",
};

function slaLabel(deadline: string | null | undefined): { label: string; color: string } {
  if (!deadline) return { label: "—", color: colors.textSecondary };
  const diffMs = new Date(deadline).getTime() - Date.now();
  if (diffMs <= 0) return { label: "Breached", color: colors.danger };
  const hours = Math.round(diffMs / 3_600_000);
  return { label: `${hours}h left`, color: hours <= 4 ? colors.danger : colors.success };
}

export function Cases() {
  const { data: cases, isLoading } = useCases();
  const rows = cases ?? [];
  return (
    <Stack>
      <Card title="Case list">
        <Table headers={["Case ID", "Subject", "Source", "Priority", "SLA", "Status"]} cols="0.7fr 2fr 1fr 1fr 1fr 1fr">
          {rows.map((r) => {
            const sla = slaLabel(r.slaDeadline);
            return (
              <TableRow key={r.id}>
                <Cell><span style={{ fontWeight: 500 }}>#{r.caseNumber}</span></Cell>
                <Cell>{r.subject}</Cell>
                <Cell muted>{r.source ?? "—"}</Cell>
                <Cell><Badge label={r.priority} variant={casePriorityVariant[r.priority] ?? "blue"} /></Cell>
                <Cell><span style={{ color: sla.color, fontWeight: 500 }}>{sla.label}</span></Cell>
                <Cell><Badge label={r.status.replace(/_/g, " ")} variant={caseStatusVariant[r.status] ?? "blue"} /></Cell>
              </TableRow>
            );
          })}
          {isLoading && <LoadingState />}
          {!isLoading && rows.length === 0 && <EmptyState message="No cases yet" />}
        </Table>
      </Card>
    </Stack>
  );
}

/* ============ S06 Workflow Engine Canvas ============ */
export function Workflow({ onNavigate }: { onNavigate: Nav }) {
  const { data: workflows, isLoading } = useWorkflows();
  const activate = useActivateWorkflow();
  const rows = workflows ?? [];

  return (
    <Stack>
      <ActionBar left={[]} right={[{ label: "New workflow", icon: Plus, variant: "primary", onClick: () => onNavigate("F05") }]} />
      <Card title={`Saved workflows (${rows.length})`}>
        {isLoading && <LoadingState />}
        {!isLoading &&
          rows.map((w) => (
            <div
              key={w.id}
              style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "9px 0", borderBottom: `0.5px solid ${colors.border}` }}
            >
              <div>
                <div style={{ fontSize: 12, fontWeight: 500, color: colors.textPrimary }}>{w.name}</div>
                <div style={{ fontSize: 11, color: colors.textSecondary }}>{w.triggerEvent ?? "No trigger set"}</div>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <Badge label={w.isActive ? "Active" : "Draft"} variant={w.isActive ? "green" : "purple"} />
                {!w.isActive && <Button label="Activate" onClick={() => activate.mutate(w.id)} />}
              </div>
            </div>
          ))}
        {!isLoading && rows.length === 0 && <EmptyState message="No workflows saved yet — build one from “New workflow”" />}
      </Card>
      <Card title="Workflow builder">
        <WorkflowBuilder />
      </Card>
    </Stack>
  );
}

/* ============ S07 RPA Bot Control Room ============ */
const botStatusVariant: Record<string, BadgeVariant> = {
  REGISTERED: "blue",
  SCHEDULED: "amber",
  RUNNING: "green",
  ERROR: "red",
  DEPLOYED: "green",
};

export function RPA({ onNavigate }: { onNavigate: Nav }) {
  const { user } = useAuth();
  const canManageBots = user?.role === "ADMIN" || user?.role === "MANAGER";
  const { data: bots, isLoading: botsLoading } = useRpaBots();
  const { data: runs, isLoading: runsLoading } = useRpaBotRuns();
  const deleteBot = useDeleteRpaBot();

  const rows = bots ?? [];
  const runsList = runs ?? [];
  const today = new Date().toDateString();
  const tasksToday = runsList.filter((r) => new Date(r.startedAt).toDateString() === today).reduce((sum, r) => sum + r.tasksCompleted, 0);
  const runningCount = rows.filter((b) => b.status === "RUNNING").length;
  const scheduledCount = rows.filter((b) => b.status === "SCHEDULED").length;

  const lastRunByBot = new Map<string, RpaBotRun>();
  for (const run of runsList) {
    if (!lastRunByBot.has(run.botId)) lastRunByBot.set(run.botId, run);
  }

  const handleRemove = (id: string, name: string) => {
    if (!window.confirm(`Remove bot "${name}"? This can't be undone.`)) return;
    deleteBot.mutate(id, {
      onError: (err) =>
        toast.error("Failed to remove bot", {
          description: err instanceof Error ? err.message : "This bot may have run history that must be preserved.",
        }),
      onSuccess: () => toast.success(`${name} removed`),
    });
  };

  return (
    <Stack>
      <ActionBar
        left={[]}
        right={
          canManageBots
            ? [
                { label: "Deploy bot", icon: Zap, onClick: () => onNavigate("F06") },
                { label: "Create bot", icon: Plus, variant: "primary", onClick: () => onNavigate("F01") },
              ]
            : []
        }
      />
      <MetricGrid columns={4}>
        <MetricCard label="Total bots" value={botsLoading ? "—" : String(rows.length)} />
        <MetricCard label="Running" value={botsLoading ? "—" : String(runningCount)} />
        <MetricCard label="Scheduled" value={botsLoading ? "—" : String(scheduledCount)} />
        <MetricCard label="Tasks today" value={runsLoading ? "—" : String(tasksToday)} />
      </MetricGrid>
      <Card title="Bot registry">
        <Table headers={["Bot name", "Platform", "Last run", "Tasks done", "Status", ""]} cols="2fr 1fr 1fr 1fr 1fr 0.7fr">
          {rows.map((r) => {
            const lastRun = lastRunByBot.get(r.id);
            return (
              <TableRow key={r.id}>
                <Cell>
                  <div style={{ fontWeight: 500 }}>{r.name}</div>
                </Cell>
                <Cell muted>{r.platform.replace(/_/g, " ")}</Cell>
                <Cell muted>{lastRun ? new Date(lastRun.startedAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) : "—"}</Cell>
                <Cell muted>{lastRun?.tasksCompleted ?? 0}</Cell>
                <Cell><Badge label={r.status} variant={botStatusVariant[r.status] ?? "blue"} /></Cell>
                <Cell>
                  {canManageBots && (
                    <button
                      onClick={() => handleRemove(r.id, r.name)}
                      style={{ border: "none", background: "transparent", color: colors.danger, fontSize: 12, cursor: "pointer", padding: 0 }}
                    >
                      Remove
                    </button>
                  )}
                </Cell>
              </TableRow>
            );
          })}
          {botsLoading && <LoadingState />}
          {!botsLoading && rows.length === 0 && <EmptyState message="No bots registered yet" />}
        </Table>
      </Card>
    </Stack>
  );
}

/* ============ S08 Deal Coach ============ */
export function Copilot() {
  const [messages, setMessages] = useState<CopilotMessage[]>([]);
  const [input, setInput] = useState("");
  const [streaming, setStreaming] = useState(false);
  const [unavailable, setUnavailable] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, streaming]);

  const send = async () => {
    const text = input.trim();
    if (!text || streaming) return;
    const next: CopilotMessage[] = [...messages, { role: "user", content: text }];
    setMessages(next);
    setInput("");
    setUnavailable(false);
    setStreaming(true);
    setMessages((m) => [...m, { role: "assistant", content: "" }]);

    await streamCopilotChat(next, {
      onDelta: (delta) =>
        setMessages((m) => {
          const copy = [...m];
          copy[copy.length - 1] = { role: "assistant", content: copy[copy.length - 1].content + delta };
          return copy;
        }),
      onError: (message) => {
        setUnavailable(true);
        setMessages((m) => {
          const copy = [...m];
          copy[copy.length - 1] = { role: "assistant", content: message };
          return copy;
        });
      },
      onDone: () => setStreaming(false),
    }).catch(() => {
      setUnavailable(true);
      setStreaming(false);
    });
    setStreaming(false);
  };

  return (
    <div style={{ display: "grid", gridTemplateColumns: "1.4fr 1fr", gap: 16, alignItems: "start" }}>
      <Card pad={0}>
        <div style={{ padding: 16, borderBottom: `0.5px solid ${colors.border}`, display: "flex", alignItems: "center", gap: 8 }}>
          <Brain size={16} color={colors.aiPurple} />
          <span style={{ fontSize: 13, fontWeight: 600 }}>Deal coach chat</span>
          <Badge label="Tool-use enabled" variant="blue" />
          {unavailable && <Badge label="Model unreachable" variant="red" />}
        </div>
        <div style={{ padding: 16, display: "flex", flexDirection: "column", gap: 12, minHeight: 360, maxHeight: 480, overflowY: "auto" }}>
          {messages.map((m, i) => (
            <div
              key={i}
              style={{
                alignSelf: m.role === "user" ? "flex-end" : "flex-start",
                maxWidth: "78%",
                background: m.role === "user" ? colors.primary : colors.aiLight,
                color: m.role === "user" ? "#FFFFFF" : "#3C3489",
                borderRadius: 10,
                padding: "9px 12px",
                fontSize: 12,
                lineHeight: 1.5,
                whiteSpace: "pre-wrap",
              }}
            >
              {m.content || (streaming && i === messages.length - 1 ? "…" : "")}
            </div>
          ))}
          {messages.length === 0 && (
            <div style={{ margin: "auto", textAlign: "center", fontSize: 12, color: colors.textTertiary }}>
              Ask the deal coach a question to get started
            </div>
          )}
          <div ref={bottomRef} />
        </div>
        <div style={{ padding: 12, borderTop: `0.5px solid ${colors.border}`, display: "flex", gap: 8 }}>
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && send()}
            placeholder="Ask AI about leads, deals, cases..."
            style={{ flex: 1, border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: "8px 10px", fontSize: 12, outline: "none" }}
          />
          <button
            onClick={send}
            disabled={streaming}
            style={{ background: colors.primary, border: "none", borderRadius: 6, width: 36, display: "flex", alignItems: "center", justifyContent: "center", cursor: streaming ? "default" : "pointer", opacity: streaming ? 0.6 : 1 }}
          >
            <Send size={15} color="#FFFFFF" />
          </button>
        </div>
      </Card>
      <Stack>
        <Card title="AI capabilities">
          <Stack gap={8}>
            {[
              "Lead scoring & intent classification (self-hosted LLM)",
              "Deal closure prediction & revenue forecast",
              "Auto email drafting & proposal generation",
              "Search leads, pipeline, and cases via tool calls",
            ].map((t) => (
              <AIInsightBox key={t} text={t} />
            ))}
          </Stack>
        </Card>
        <Card title="AI model status">
          <BotStatusRow
            name="Self-hosted model"
            sub={unavailable ? "Unreachable — check AI_BASE_URL" : "Connected via OpenAI-compatible endpoint"}
            status={unavailable ? "error" : "running"}
          />
        </Card>
      </Stack>
    </div>
  );
}

/* ============ Marketing Automation ============ */
const campaignStatusVariant: Record<string, BadgeVariant> = { DRAFT: "purple", SCHEDULED: "amber", ACTIVE: "green", COMPLETED: "blue" };

export function Marketing({ onNavigate }: { onNavigate: Nav }) {
  const { data: campaignsData, isLoading } = useCampaigns();
  const campaigns = campaignsData ?? [];
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("All statuses");
  const q = query.trim().toLowerCase();
  const filteredCampaigns = campaigns.filter((c) => {
    const matchesQuery = !q || c.name.toLowerCase().includes(q) || (c.segment ?? "").toLowerCase().includes(q);
    const matchesStatus = status === "All statuses" || c.status.toLowerCase() === status.toLowerCase();
    return matchesQuery && matchesStatus;
  });
  const activeCampaigns = campaigns.filter((c) => c.status === "ACTIVE").length;
  const totalSent = campaigns.reduce((sum, c) => sum + c.sentCount, 0);
  const withOpenRate = campaigns.filter((c) => c.openRatePct != null);
  const avgOpenRate = withOpenRate.length
    ? Math.round(withOpenRate.reduce((sum, c) => sum + (c.openRatePct ?? 0), 0) / withOpenRate.length)
    : null;

  return (
    <Stack>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <div style={{ display: "flex", gap: 8 }}>
          <SearchInput value={query} onChange={setQuery} placeholder="Search campaign, segment…" />
          <FilterSelect value={status} onChange={setStatus} options={["All statuses", "Active", "Scheduled", "Draft"]} />
        </div>
        <Button label="New campaign" icon={Plus} variant="primary" onClick={() => onNavigate("F08")} />
      </div>
      <MetricGrid columns={4}>
        <MetricCard label="Active campaigns" value={isLoading ? "—" : String(activeCampaigns)} />
        <MetricCard label="Emails sent" value={isLoading ? "—" : String(totalSent)} />
        <MetricCard label="Avg open rate" value={isLoading ? "—" : avgOpenRate != null ? `${avgOpenRate}%` : "—"} />
        <MetricCard label="Conversions" value="—" />
      </MetricGrid>
      <Card title="Email nurture journey">
        <WorkflowBuilder />
      </Card>
      <Card title={`Campaign performance (${filteredCampaigns.length})`}>
        <Table headers={["Campaign", "Channel", "Segment", "Sent", "Open rate", "Status"]} cols="1.4fr 1fr 1.2fr 0.8fr 0.9fr 0.9fr">
          {filteredCampaigns.map((c) => (
            <TableRow key={c.id}>
              <Cell><span style={{ fontWeight: 500 }}>{c.name}</span></Cell>
              <Cell muted>{c.channel.replace(/_/g, " ")}</Cell>
              <Cell muted>{c.segment ?? "—"}</Cell>
              <Cell muted>{c.sentCount}</Cell>
              <Cell>{c.openRatePct != null ? `${c.openRatePct}%` : "—"}</Cell>
              <Cell><Badge label={c.status} variant={campaignStatusVariant[c.status] ?? "blue"} /></Cell>
            </TableRow>
          ))}
          {isLoading && <LoadingState />}
          {!isLoading && filteredCampaigns.length === 0 && <EmptyState message={campaigns.length === 0 ? "No campaigns yet" : "No matching results"} />}
        </Table>
      </Card>
    </Stack>
  );
}

/* ============ S09 Analytics ============ */
export function Analytics() {
  const { data, isLoading } = useReportingSummary();
  const trend = data?.revenueTrend ?? [];
  const values = trend.map((t) => Number(t.revenue));
  const labels = trend.map((t) => new Date(t.month).toLocaleDateString(undefined, { month: "short" }));
  const max = values.length ? Math.max(...values) : 0;

  return (
    <Stack>
      <MetricGrid columns={4}>
        <MetricCard label="Revenue (MTD)" value={isLoading ? "—" : formatCurrency(data?.revenueMtd)} />
        <MetricCard label="Win rate" value={isLoading ? "—" : `${data?.winRatePct ?? 0}%`} />
        <MetricCard label="Avg deal size" value={isLoading ? "—" : formatCurrency(data?.avgDealSize)} />
        <MetricCard label="Churn risk" value={isLoading ? "—" : String(data?.churnRiskCount ?? 0)} subtext={isLoading ? undefined : "Accounts flagged"} />
      </MetricGrid>
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
        <Card title="Revenue trend (monthly, closed-won)">
          {isLoading && <LoadingState />}
          {!isLoading && values.length === 0 && <EmptyState message="No closed-won deals yet" />}
          {!isLoading && values.length > 0 && (
            <div style={{ display: "flex", alignItems: "flex-end", gap: 14, height: 200, padding: "8px 4px 0" }}>
              {values.map((v, i) => (
                <div key={i} style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 6, height: "100%", justifyContent: "flex-end" }}>
                  <div
                    style={{
                      width: "100%",
                      height: `${max ? (v / max) * 100 : 0}%`,
                      background: i === values.length - 1 ? colors.primary : "#B5D4F4",
                      borderRadius: "4px 4px 0 0",
                    }}
                  />
                  <span style={{ fontSize: 10, color: colors.textSecondary, whiteSpace: "nowrap" }}>{labels[i]}</span>
                </div>
              ))}
            </div>
          )}
        </Card>
        <Card title="AI predictions">
          <EmptyState message="Ask the deal coach for forecasts and churn predictions" />
        </Card>
      </div>
    </Stack>
  );
}

/* ============ S10 Security & Audit ============ */
// The server records a controlled severity per entry, which is a more reliable
// badge source than pattern-matching the free-text event name.
const auditSeverityVariant: Record<string, BadgeVariant> = {
  alert: "red",
  warning: "amber",
  ok: "green",
  info: "blue",
};
const userRoleVariant: Record<string, BadgeVariant> = { ADMIN: "purple", MANAGER: "blue", SALES_REP: "green", SUPPORT_AGENT: "amber", MARKETING: "blue" };
const userRoleLabel: Record<string, string> = { ADMIN: "Admin", MANAGER: "Manager", SALES_REP: "Sales representative", SUPPORT_AGENT: "Support agent", MARKETING: "Marketing" };
const userStatusVariant: Record<string, BadgeVariant> = { ACTIVE: "green", INACTIVE: "amber" };
const EDITABLE_ROLES: Role[] = ["SALES_REP", "SUPPORT_AGENT", "MARKETING", "MANAGER", "ADMIN"];

/* Edit-user modal — an admin can change a user's role and per-screen access
   at any time. Picking a role re-seeds the toggles to that role's defaults,
   which can then be overridden. Changes take effect on the user's next
   token refresh (~15 min) or re-login. */
function EditUserModal({ user, onClose }: { user: UserRow; onClose: () => void }) {
  const [role, setRole] = useState<Role>(user.role);
  const [permissions, setPermissions] = useState<string[]>(user.permissions ?? []);
  const updateUser = useUpdateUser();
  const isAdmin = role === "ADMIN";

  const selectRole = (r: Role) => {
    setRole(r);
    setPermissions(ROLE_DEFAULT_PERMISSIONS[r]);
  };
  const toggle = (key: string) =>
    setPermissions((p) => (p.includes(key) ? p.filter((k) => k !== key) : [...p, key]));

  const save = () => {
    updateUser.mutate(
      { id: user.id, role, permissions },
      {
        onSuccess: () => {
          toast.success(`${user.fullName} updated`, { description: "Changes apply on their next login or within ~15 minutes." });
          onClose();
        },
        onError: (err) => toast.error("Failed to update user", { description: err instanceof Error ? err.message : undefined }),
      }
    );
  };

  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}
    >
      <div onClick={(e) => e.stopPropagation()} style={{ background: "#FFFFFF", borderRadius: 8, width: 480, maxHeight: "85vh", overflowY: "auto", padding: 20 }}>
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 4 }}>Edit access — {user.fullName}</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 16 }}>{user.email}</div>

        <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 8 }}>Role</div>
        <div style={{ display: "flex", gap: 8, flexWrap: "wrap", marginBottom: 16 }}>
          {EDITABLE_ROLES.map((r) => (
            <button
              key={r}
              onClick={() => selectRole(r)}
              style={{
                border: `1px solid ${role === r ? colors.primary : colors.border}`,
                background: role === r ? colors.primaryLight : "#FFFFFF",
                color: role === r ? colors.primary : colors.textPrimary,
                borderRadius: 6, padding: "6px 12px", fontSize: 12, fontWeight: 500, cursor: "pointer",
              }}
            >
              {userRoleLabel[r]}
            </button>
          ))}
        </div>

        <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, marginBottom: 4 }}>Screen access</div>
        {isAdmin ? (
          <div style={{ fontSize: 12, color: colors.textSecondary, padding: "8px 0 16px" }}>
            Admins always have full access — individual toggles don't apply.
          </div>
        ) : (
          <div style={{ marginBottom: 12 }}>
            {PERMISSION_CATALOG.map((p) => (
              <ToggleRow key={p.key} label={p.label} on={permissions.includes(p.key)} onChange={() => toggle(p.key)} />
            ))}
          </div>
        )}

        <div style={{ display: "flex", justifyContent: "flex-end", gap: 8, marginTop: 8 }}>
          <Button label="Cancel" onClick={onClose} />
          <Button label={updateUser.isPending ? "Saving…" : "Save changes"} variant="primary" onClick={save} />
        </div>
      </div>
    </div>
  );
}

function ResetPasswordResultModal({ fullName, temporaryPassword, onClose }: { fullName: string; temporaryPassword: string; onClose: () => void }) {
  const copy = () => {
    navigator.clipboard?.writeText(temporaryPassword).then(() => toast.success("Copied to clipboard"));
  };
  return (
    <div
      onClick={onClose}
      style={{ position: "fixed", inset: 0, background: "rgba(0,0,0,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 50, padding: 20 }}
    >
      <div onClick={(e) => e.stopPropagation()} style={{ background: "#FFFFFF", borderRadius: 8, width: 420, padding: 20 }}>
        <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 4 }}>Password reset — {fullName}</div>
        <div style={{ fontSize: 12, color: colors.textSecondary, marginBottom: 16 }}>
          Share this temporary password with them securely. It won't be shown again, and they'll be required to set a new one on their next login.
        </div>
        <div
          style={{
            display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8,
            border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: "10px 12px", marginBottom: 16,
            fontFamily: "monospace", fontSize: 14, background: colors.bgSecondary,
          }}
        >
          <span>{temporaryPassword}</span>
          <Button label="Copy" onClick={copy} />
        </div>
        <div style={{ display: "flex", justifyContent: "flex-end" }}>
          <Button label="Done" variant="primary" onClick={onClose} />
        </div>
      </div>
    </div>
  );
}

export function Security({ onNavigate }: { onNavigate: Nav }) {
  const { user } = useAuth();
  const canAddUsers = user?.role === "ADMIN";
  const { data: summary, isLoading: summaryLoading } = useSecuritySummary();
  const { data: auditLog, isLoading: auditLoading } = useAuditLog();
  const { data: users, isLoading: usersLoading } = useUsers();
  const deleteUser = useDeleteUser();
  const setUserStatus = useSetUserStatus();
  const resetUserPassword = useResetUserPassword();
  const [editingUser, setEditingUser] = useState<UserRow | null>(null);
  const [resetResult, setResetResult] = useState<{ fullName: string; temporaryPassword: string } | null>(null);
  const rows = auditLog?.content ?? [];
  const userRows = users ?? [];

  const handleRemoveUser = (id: string, name: string) => {
    if (!window.confirm(`Remove ${name}? They will lose access immediately.`)) return;
    deleteUser.mutate(id, {
      onError: (err) => toast.error("Failed to remove user", { description: err instanceof Error ? err.message : undefined }),
      onSuccess: () => toast.success(`${name} removed`),
    });
  };

  const handleToggleStatus = (u: UserRow) => {
    const next = u.status === "ACTIVE" ? "INACTIVE" : "ACTIVE";
    if (next === "INACTIVE" && !window.confirm(`Deactivate ${u.fullName}? They will lose access immediately.`)) return;
    setUserStatus.mutate(
      { id: u.id, status: next },
      {
        onError: (err) => toast.error("Failed to update status", { description: err instanceof Error ? err.message : undefined }),
        onSuccess: () => toast.success(`${u.fullName} ${next === "ACTIVE" ? "activated" : "deactivated"}`),
      }
    );
  };

  const handleResetPassword = (u: UserRow) => {
    if (!window.confirm(`Reset ${u.fullName}'s password? Their current sessions will be signed out.`)) return;
    resetUserPassword.mutate(
      { id: u.id },
      {
        onError: (err) => toast.error("Failed to reset password", { description: err instanceof Error ? err.message : undefined }),
        onSuccess: (result) => setResetResult({ fullName: u.fullName, temporaryPassword: result.temporaryPassword }),
      }
    );
  };

  return (
    <Stack>
      <ActionBar left={[]} right={canAddUsers ? [{ label: "Add user", icon: Plus, variant: "primary", onClick: () => onNavigate("F02") }] : []} />
      <MetricGrid columns={4}>
        <MetricCard label="Audit events (24h)" value={summaryLoading ? "—" : String(summary?.auditEvents24h ?? 0)} />
        <MetricCard label="Failed logins" value={summaryLoading ? "—" : String(summary?.failedLogins24h ?? 0)} />
        <MetricCard label="MFA coverage" value={summaryLoading ? "—" : `${summary?.mfaCoveragePct ?? 0}%`} />
        <MetricCard label="Compliance" value="Self-hosted AI" />
      </MetricGrid>
      {canAddUsers && (
        <Card title={`Team members (${userRows.length})`}>
          <Table headers={["Name", "Email", "Role", "Status", "Access", ""]} cols="1.2fr 1.4fr 0.8fr 0.7fr 0.7fr 1.4fr">
            {userRows.map((u) => (
              <TableRow key={u.id}>
                <Cell>
                  <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                    <Avatar initials={initialsFromName(u.fullName)} />
                    <span style={{ fontWeight: 500 }}>{u.fullName}</span>
                    {u.id === user?.id && <span style={{ fontSize: 11, color: colors.textTertiary }}>(you)</span>}
                  </div>
                </Cell>
                <Cell muted>{u.email}</Cell>
                <Cell><Badge label={userRoleLabel[u.role] ?? u.role} variant={userRoleVariant[u.role] ?? "blue"} /></Cell>
                <Cell><Badge label={u.status === "ACTIVE" ? "Active" : "Inactive"} variant={userStatusVariant[u.status] ?? "blue"} /></Cell>
                <Cell muted>{u.role === "ADMIN" ? "All" : `${u.permissions?.length ?? 0} screen(s)`}</Cell>
                <Cell>
                  <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
                    <button
                      onClick={() => setEditingUser(u)}
                      style={{ border: "none", background: "transparent", color: colors.primary, fontSize: 12, cursor: "pointer", padding: 0 }}
                    >
                      Edit
                    </button>
                    {u.id !== user?.id && (
                      <>
                        <button
                          onClick={() => handleToggleStatus(u)}
                          style={{ border: "none", background: "transparent", color: colors.textSecondary, fontSize: 12, cursor: "pointer", padding: 0 }}
                        >
                          {u.status === "ACTIVE" ? "Deactivate" : "Activate"}
                        </button>
                        <button
                          onClick={() => handleResetPassword(u)}
                          style={{ border: "none", background: "transparent", color: colors.textSecondary, fontSize: 12, cursor: "pointer", padding: 0 }}
                        >
                          Reset password
                        </button>
                        <button
                          onClick={() => handleRemoveUser(u.id, u.fullName)}
                          style={{ border: "none", background: "transparent", color: colors.danger, fontSize: 12, cursor: "pointer", padding: 0 }}
                        >
                          Remove
                        </button>
                      </>
                    )}
                  </div>
                </Cell>
              </TableRow>
            ))}
          </Table>
          {usersLoading && <LoadingState />}
          {!usersLoading && userRows.length === 0 && <EmptyState message="No users yet" />}
        </Card>
      )}
      {editingUser && <EditUserModal user={editingUser} onClose={() => setEditingUser(null)} />}
      {resetResult && (
        <ResetPasswordResultModal
          fullName={resetResult.fullName}
          temporaryPassword={resetResult.temporaryPassword}
          onClose={() => setResetResult(null)}
        />
      )}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 16 }}>
        <Card title="Recent audit log">
          {auditLoading && <LoadingState />}
          {!auditLoading &&
            rows.map((r) => {
              const actor = r.actorUser?.fullName ?? r.actorLabel ?? r.actorType ?? "system";
              const entity = r.relatedEntityType
                ? `${r.relatedEntityType}${r.relatedEntityId ? " #" + r.relatedEntityId : ""}`
                : "";
              return (
                <Row
                  key={r.id}
                  time={new Date(r.occurredAt).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
                  name={r.event ?? "Event"}
                  sub={[actor, entity, r.detail].filter(Boolean).join(" · ")}
                  badge={{ label: r.severity ?? "info", variant: auditSeverityVariant[r.severity] ?? "blue" }}
                />
              );
            })}
          {!auditLoading && rows.length === 0 && <EmptyState message="No audit events yet" />}
        </Card>
        <Card title="Security posture">
          <ProgressBar label="Encryption (TLS + AES-256)" value="Active" pct={100} color={colors.success} />
          <ProgressBar label="MFA adoption" value={`${summary?.mfaCoveragePct ?? 0}%`} pct={summary?.mfaCoveragePct ?? 0} color={colors.success} />
          <ProgressBar label="AI inference" value="Self-hosted, no third-party calls" pct={100} color={colors.success} />
        </Card>
      </div>
    </Stack>
  );
}
