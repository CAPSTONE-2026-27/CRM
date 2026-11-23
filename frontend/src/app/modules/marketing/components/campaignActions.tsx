import { ReactNode, useState } from "react";
import { toast } from "sonner";
import { BarChart3, Copy, Eye, Pause, PencilLine, Play, Trash2, XCircle } from "lucide-react";
import type { CampaignStatus } from "../api/types";
import { useCampaignCommand, useDeleteCampaign } from "../api/hooks";
import { ConfirmDialog, MenuItem } from "./ui";

export type Nav = {
  openCampaign: (id: string, tab?: string) => void;
  editCampaign: (id: string) => void;
  create: () => void;
  home: (tab?: string) => void;
};

const EDITABLE: CampaignStatus[] = ["DRAFT", "AI_PROCESSING", "PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"];
const DELETABLE: CampaignStatus[] = ["DRAFT", "PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH", "COMPLETED", "CANCELLED"];

type Pending = { kind: "pause" | "resume" | "cancel" | "delete"; id: string; name: string } | null;

const errMsg = (e: unknown) => (e instanceof Error ? e.message : "Please try again.");

/**
 * Lifecycle actions with their confirmation dialogs. Returns menu items for a
 * campaign plus the dialog element to render once per screen.
 */
export function useCampaignActions(nav: Nav, isApprover: boolean) {
  const command = useCampaignCommand();
  const del = useDeleteCampaign();
  const [pending, setPending] = useState<Pending>(null);

  const duplicate = (id: string, name: string) =>
    command.mutate(
      { id, command: "duplicate" },
      {
        onSuccess: (copy) => {
          toast.success("Campaign duplicated", { description: `"${copy.name}" was created as a draft with the same inputs.` });
          nav.editCampaign(copy.id);
        },
        onError: (e) => toast.error(`Couldn't duplicate "${name}"`, { description: errMsg(e) }),
      }
    );

  const menuFor = (c: { id: string; name: string; status: CampaignStatus }): MenuItem[] => [
    { label: "View", icon: Eye, onSelect: () => nav.openCampaign(c.id) },
    { label: "Edit", icon: PencilLine, onSelect: () => nav.editCampaign(c.id), hidden: !EDITABLE.includes(c.status) },
    { label: "Duplicate", icon: Copy, onSelect: () => duplicate(c.id, c.name) },
    { label: "Analytics", icon: BarChart3, onSelect: () => nav.openCampaign(c.id, "analytics") },
    "divider",
    { label: "Pause", icon: Pause, onSelect: () => setPending({ kind: "pause", ...c }), hidden: c.status !== "ACTIVE", disabled: !isApprover },
    { label: "Resume", icon: Play, onSelect: () => setPending({ kind: "resume", ...c }), hidden: c.status !== "PAUSED", disabled: !isApprover },
    {
      label: "Cancel campaign",
      icon: XCircle,
      danger: true,
      onSelect: () => setPending({ kind: "cancel", ...c }),
      hidden: c.status === "COMPLETED" || c.status === "CANCELLED" || c.status === "DRAFT",
      disabled: !isApprover,
    },
    { label: "Delete", icon: Trash2, danger: true, onSelect: () => setPending({ kind: "delete", ...c }), hidden: !DELETABLE.includes(c.status), disabled: !isApprover },
  ];

  const close = () => setPending(null);
  const run = (note: string) => {
    if (!pending) return;
    const { kind, id, name } = pending;
    if (kind === "delete") {
      del.mutate(id, {
        onSuccess: () => {
          toast.success(`Deleted "${name}"`);
          close();
          nav.home("campaigns");
        },
        onError: (e) => toast.error("Couldn't delete", { description: errMsg(e) }),
      });
      return;
    }
    command.mutate(
      { id, command: kind, body: kind === "cancel" ? { reason: note } : undefined },
      {
        onSuccess: () => {
          toast.success(kind === "pause" ? "Campaign paused" : kind === "resume" ? "Campaign resumed" : "Campaign cancelled");
          close();
        },
        onError: (e) => toast.error(`Couldn't ${kind} the campaign`, { description: errMsg(e) }),
      }
    );
  };

  let dialog: ReactNode = null;
  if (pending) {
    const busy = command.isPending || del.isPending;
    const copy = {
      pause: { title: `Pause "${pending.name}"?`, body: "Queued messages stay queued and nothing more is sent until you resume.", label: "Pause campaign", tone: "primary" as const },
      resume: { title: `Resume "${pending.name}"?`, body: "Sending continues from where it stopped.", label: "Resume campaign", tone: "ok" as const },
      cancel: {
        title: `Cancel "${pending.name}"?`,
        body: "Unsent messages are withdrawn and the campaign can't be restarted. Duplicate it if you want to run it again.",
        label: "Cancel campaign",
        tone: "danger" as const,
      },
      delete: {
        title: `Delete "${pending.name}"?`,
        body: "The campaign, its AI evaluations, content and campaign activity are removed permanently. The organisation audit log keeps a record of the deletion.",
        label: "Delete permanently",
        tone: "danger" as const,
      },
    }[pending.kind];
    dialog = (
      <ConfirmDialog
        title={copy.title}
        body={copy.body}
        confirmLabel={copy.label}
        tone={copy.tone}
        busy={busy}
        note={pending.kind === "cancel" ? "Reason (optional)" : undefined}
        onConfirm={run}
        onCancel={close}
      />
    );
  }

  return { menuFor, dialog, duplicate, busy: command.isPending || del.isPending };
}
