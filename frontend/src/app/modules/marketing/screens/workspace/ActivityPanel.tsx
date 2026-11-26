import { useState } from "react";
import { Bot, Cog, History, UserRound } from "lucide-react";
import type { ActivityItem, ApprovalItem } from "../../api/types";
import { useCampaignActivity } from "../../api/hooks";
import { Badge, EmptyState, ErrorState, Panel, Segmented, Skeleton } from "../../components/ui";
import { fmtAgo, fmtDateTime } from "../../theme";

const actorLabel = { AI: "AI", HUMAN: "Human", SYSTEM: "System" } as const;

export function ActivityList({
  items,
  showCampaign,
  onOpen,
  compact,
}: {
  items: ActivityItem[];
  showCampaign?: boolean;
  onOpen?: (campaignId: string) => void;
  compact?: boolean;
}) {
  return (
    <ol className="mk-timeline">
      {items.map((a) => {
        const Icon = a.actorType === "AI" ? Bot : a.actorType === "HUMAN" ? UserRound : Cog;
        return (
          <li key={a.id}>
            <span className={`mk-actor mk-actor--${a.actorType}`} title={actorLabel[a.actorType]}>
              <Icon size={14} aria-hidden />
            </span>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: 12.5 }}>
                {showCampaign && a.campaignName && (
                  <button type="button" className="mk-link" onClick={() => onOpen?.(a.campaignId)}>
                    {a.campaignName}
                  </button>
                )}
                {showCampaign && a.campaignName && <span className="mk-muted"> · </span>}
                <span>{a.summary}</span>
              </div>
              <div className="mk-sub" style={{ marginTop: 2 }}>
                {actorLabel[a.actorType]}
                {a.actorName ? ` · ${a.actorName}` : ""} · <time dateTime={a.occurredAt} title={fmtDateTime(a.occurredAt)}>{compact ? fmtAgo(a.occurredAt) : fmtDateTime(a.occurredAt)}</time>
              </div>
              {!compact && a.detail && Object.keys(a.detail).length > 0 && (
                <details style={{ marginTop: 4 }}>
                  <summary className="mk-sub" style={{ cursor: "pointer" }}>Details</summary>
                  <pre style={{ margin: "6px 0 0", fontSize: 11.5, background: "var(--mk-sunken)", border: "1px solid var(--mk-line)", borderRadius: 6, padding: 8, whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>
                    {JSON.stringify(a.detail, null, 2)}
                  </pre>
                </details>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

export function ActivityPanel({ campaignId, approvals }: { campaignId: string; approvals: ApprovalItem[] }) {
  const { data, isLoading, error, refetch } = useCampaignActivity(campaignId);
  const [who, setWho] = useState<"ALL" | ActivityItem["actorType"]>("ALL");
  const items = (data ?? []).filter((a) => who === "ALL" || a.actorType === who);

  return (
    <div className="mk-grid-main">
      <Panel
        title="Audit trail"
        subtitle="Every AI result, human change and approval, with who and when"
        actions={
          <Segmented
            label="Filter by actor"
            value={who}
            onChange={setWho}
            options={[
              { value: "ALL", label: "All" },
              { value: "AI", label: "AI" },
              { value: "HUMAN", label: "Human" },
              { value: "SYSTEM", label: "System" },
            ]}
          />
        }
      >
        {error ? (
          <ErrorState error={error} onRetry={() => refetch()} />
        ) : isLoading ? (
          <Skeleton rows={6} />
        ) : items.length === 0 ? (
          <EmptyState icon={History} title="No activity" />
        ) : (
          <ActivityList items={items} />
        )}
      </Panel>
      <Panel title="Approval history">
        {approvals.length === 0 ? (
          <div className="mk-sub">No approval checkpoints yet.</div>
        ) : (
          <ul style={{ listStyle: "none", margin: 0, padding: 0 }}>
            {approvals.map((a) => (
              <li key={a.id} style={{ padding: "8px 0", borderBottom: "1px solid #EEEFF1" }}>
                <div className="mk-row" style={{ justifyContent: "space-between" }}>
                  <strong style={{ fontSize: 12.5 }}>{a.kind === "LEADS" ? "Lead approval" : a.kind === "CONTENT" ? "Content approval" : "Campaign approval"}</strong>
                  <Badge tone={a.status === "APPROVED" ? "ok" : a.status === "PENDING" ? "warn" : a.status === "REJECTED" ? "bad" : "neutral"}>
                    {a.status === "WITHDRAWN" ? "Superseded" : a.status.charAt(0) + a.status.slice(1).toLowerCase()}
                  </Badge>
                </div>
                <div className="mk-sub" style={{ marginTop: 2 }}>
                  Opened {fmtDateTime(a.submittedAt)}
                  {a.decidedAt && ` · closed ${fmtDateTime(a.decidedAt)}`}
                  {a.decidedBy && ` by ${a.decidedBy}`}
                </div>
                {a.note && <div className="mk-sub">{a.note}</div>}
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </div>
  );
}
