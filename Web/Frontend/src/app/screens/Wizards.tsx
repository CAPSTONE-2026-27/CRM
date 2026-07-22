import { useState } from "react";
import { toast } from "sonner";
import { colors, type BadgeVariant } from "../tokens";
import {
  Card,
  Stepper,
  Field,
  AIInsightBox,
  RPAInsightBox,
  ToggleRow,
  Switch,
  OptionCard,
  Badge,
  Button,
  Stack,
  ProgressBar,
  Avatar,
} from "../components/crm/ui";
import { WorkflowBuilder } from "../components/crm/WorkflowBuilder";
import {
  useCreateLead,
  useCreateLeadFromEmail,
  useCreateUser,
  useUsers,
  useCreateAccount,
  useAccounts,
  useCreateDeal,
  useCreateRpaBot,
  useDeployRpaBot,
  useRpaBots,
  useCreateWorkflow,
  useActivateWorkflow,
  useCreateCampaign,
} from "../lib/queries";
import { PERMISSION_CATALOG, ROLE_DEFAULT_PERMISSIONS } from "../components/crm/Sidebar";
import { scoreVariant } from "./MainScreens";
import type { Lead } from "../lib/types";
import {
  User,
  Headphones,
  ShieldCheck,
  FileText,
  Mail,
  MessageSquare,
  Bot,
  Cloud,
  Monitor,
  Box,
  Rocket,
  Check,
  AlertTriangle,
  Zap,
  GitFork,
  Webhook,
  Sparkles,
  Shuffle,
  Clock,
  TrendingUp,
  UserCheck,
  Megaphone,
} from "lucide-react";

export type WizardId = "F01" | "F02" | "F03" | "F04" | "F05" | "F06" | "F07" | "F08";

export const wizardMeta: Record<WizardId, { title: string; badge: string }> = {
  F01: { title: "RPA bot creation", badge: "Module 7" },
  F02: { title: "User creation", badge: "Module 1" },
  F03: { title: "Lead creation", badge: "Module 2" },
  F04: { title: "Account creation", badge: "Module 4" },
  F05: { title: "New workflow", badge: "Module 9" },
  F06: { title: "Deploy bot", badge: "Module 7" },
  F07: { title: "New deal", badge: "Module 3" },
  F08: { title: "New campaign", badge: "Module 11" },
};

/* ---- layout helpers ---- */
function FieldGrid({ children }: { children: React.ReactNode }) {
  return <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>{children}</div>;
}
function SubLabel({ children }: { children: React.ReactNode }) {
  return (
    <div style={{ fontSize: 11, fontWeight: 600, color: colors.textSecondary, textTransform: "uppercase", letterSpacing: 0.4, margin: "4px 0 2px" }}>
      {children}
    </div>
  );
}

/* ============ F01 Create RPA Bot ============ */
const F01_STEPS = ["Select trigger", "Choose platform", "Define actions", "Set credentials", "Review & save"];

const F01_TRIGGERS = [
  { icon: Webhook, label: "Webhook", sub: "From workflow engine" },
  { icon: Clock, label: "Scheduled", sub: "Cron / interval" },
  { icon: User, label: "Manual", sub: "On-demand run" },
];
const F01_PLATFORMS = [
  { icon: Bot, label: "UiPath", sub: "Orchestrator" },
  { icon: Cloud, label: "Automation Anywhere", sub: "Control Room" },
  { icon: Box, label: "Blue Prism", sub: "Digital Workforce" },
];

const F01_PLATFORM_VALUES = ["UIPATH", "AUTOMATION_ANYWHERE", "BLUE_PRISM"] as const;

function F01({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [trigger, setTrigger] = useState(0);
  const [platform, setPlatform] = useState(0);
  const [botName, setBotName] = useState("");
  const createBot = useCreateRpaBot();

  const primaryLabel = step < 4 ? `Next: ${F01_STEPS[step + 1]}` : "Create bot";
  const primaryIcon = step === 4 ? Bot : undefined;

  const handlePrimary = async () => {
    if (step === 0 && !botName.trim()) {
      toast.error("Bot name is required");
      return;
    }
    if (step < 4) {
      setStep((s) => s + 1);
      return;
    }
    try {
      await createBot.mutateAsync({
        name: botName,
        platform: F01_PLATFORM_VALUES[platform],
        botType: "UNATTENDED",
        triggerSource: F01_TRIGGERS[trigger].label,
        credentialVaultRef: "portal_creds_01",
      });
      toast.success("Bot created successfully", {
        description: `'${botName}' is ready — deploy it from the RPA control room.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to create bot", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this bot anytime from the RPA control room.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F01_STEPS} current={step} />

      {step === 0 && <F01Trigger trigger={trigger} setTrigger={setTrigger} botName={botName} setBotName={setBotName} />}
      {step === 1 && <F01Platform platform={platform} setPlatform={setPlatform} />}
      {step === 2 && <F01Actions />}
      {step === 3 && <F01Credentials />}
      {step === 4 && <F01Review botName={botName} trigger={trigger} platform={platform} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F01 step 1: Select trigger ---- */
function F01Trigger({
  trigger,
  setTrigger,
  botName,
  setBotName,
}: {
  trigger: number;
  setTrigger: (i: number) => void;
  botName: string;
  setBotName: (v: string) => void;
}) {
  return (
    <Card title="Select trigger">
      <Stack>
        <RPAInsightBox text="Step 1 of 5 — Choose what starts this bot. You can add more triggers later." />
        <FieldGrid>
          <Field label="Bot name" value={botName} onChange={setBotName} placeholder="e.g. Lead Portal Scraper Bot" />
          <Field label="Bot type" type="select" options={["Unattended", "Attended"]} />
        </FieldGrid>
        <div>
          <SubLabel>Trigger source</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F01_TRIGGERS.map((t, i) => (
              <OptionCard key={t.label} icon={t.icon} label={t.label} sub={t.sub} selected={trigger === i} onClick={() => setTrigger(i)} />
            ))}
          </div>
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F01 step 2: Choose platform ---- */
function F01Platform({ platform, setPlatform }: { platform: number; setPlatform: (i: number) => void }) {
  return (
    <Card title="Choose platform">
      <Stack>
        <RPAInsightBox text="Step 2 of 5 — Select the RPA platform and runtime this bot will execute on." />
        <div>
          <SubLabel>RPA platform</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F01_PLATFORMS.map((p, i) => (
              <OptionCard key={p.label} icon={p.icon} label={p.label} sub={p.sub} selected={platform === i} onClick={() => setPlatform(i)} />
            ))}
          </div>
        </div>
        <FieldGrid>
          <Field label="Orchestrator URL" placeholder="https://orchestrator.company.com" />
          <Field label="Runtime version" type="select" options={["Latest", "2024.10 LTS", "2023.4 LTS"]} />
          <Field label="Machine pool" type="select" options={["Auto-scaled pool", "Dedicated VM", "Container pool"]} />
          <Field label="License type" type="select" options={["Unattended", "Attended", "Testing"]} />
        </FieldGrid>
      </Stack>
    </Card>
  );
}

/* ---- F01 step 3: Define actions ---- */
function F01Actions() {
  const [toggles, setToggles] = useState([true, true, false]);
  return (
    <Card title="Define bot actions">
      <Stack>
        <RPAInsightBox text="Step 3 of 5 — Drag steps onto the canvas and reorder them. Actions run top to bottom." />
        <WorkflowBuilder
          initialNodes={[
            { type: "trigger", title: "Navigate", label: "Open lead portal" },
            { type: "trigger", title: "Extract", label: "Scrape lead table" },
            { type: "condition", title: "Validate", label: "Check against ERP" },
            { type: "action", title: "Create", label: "Post lead to CRM API" },
            { type: "log", title: "Log", label: "Write execution log" },
          ]}
        />
        <div>
          <SubLabel>Execution options</SubLabel>
          {["Retry on failure (max 3 attempts)", "Send notification on exception", "Run in sandbox mode first"].map((label, i) => (
            <ToggleRow key={label} label={label} on={toggles[i]} onChange={() => setToggles((t) => t.map((v, j) => (j === i ? !v : v)))} />
          ))}
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F01 step 4: Set credentials ---- */
function F01Credentials() {
  const [toggles, setToggles] = useState([true, false]);
  return (
    <Card title="Set credentials">
      <Stack>
        <RPAInsightBox text="Step 4 of 5 — Link the credential vault this bot uses. Secrets are never stored in plain text." />
        <FieldGrid>
          <Field label="Credential vault" type="select" options={["portal_creds_01", "erp_service_account", "Create new vault entry"]} />
          <Field label="Provider" type="select" options={["CyberArk", "HashiCorp Vault", "Azure Key Vault"]} />
          <Field label="Portal username" value="svc_lead_bot" />
          <Field label="Portal password" type="text" value="••••••••••" />
          <Field label="ERP API key" type="text" value="••••••••••••••••" />
          <Field label="Rotation policy" type="select" options={["Every 30 days", "Every 90 days", "Manual"]} />
        </FieldGrid>
        <div>
          <SubLabel>Security</SubLabel>
          {["Encrypt secrets at rest (AES-256)", "Require approval before each run"].map((label, i) => (
            <ToggleRow key={label} label={label} on={toggles[i]} onChange={() => setToggles((t) => t.map((v, j) => (j === i ? !v : v)))} />
          ))}
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F01 step 5: Review & save ---- */
function F01Review({ botName, trigger, platform }: { botName: string; trigger: number; platform: number }) {
  const summary = [
    { label: "Bot name", value: botName || "—" },
    { label: "Trigger", value: F01_TRIGGERS[trigger].label },
    { label: "Platform", value: F01_PLATFORMS[platform].label },
    { label: "Actions", value: "5 steps" },
    { label: "Credential vault", value: "portal_creds_01" },
    { label: "Retry policy", value: "Max 3 attempts" },
  ];
  return (
    <Card title="Review & save">
      <Stack>
        <RPAInsightBox text="Step 5 of 5 — Review the bot configuration before saving it to the registry." />
        <div>
          <SubLabel>Summary</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {summary.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
      </Stack>
    </Card>
  );
}

/* ============ F02 Create User ============ */
const F02_STEPS = ["User details", "Role & access", "Identity provider", "Review"];

const F02_ROLES = [
  { icon: User, label: "Sales rep", sub: "Leads, pipeline, accounts" },
  { icon: Headphones, label: "Support agent", sub: "Cases & tickets" },
  { icon: Megaphone, label: "Marketing", sub: "Marketing & analytics" },
  { icon: TrendingUp, label: "Manager", sub: "Team oversight" },
  { icon: ShieldCheck, label: "Admin", sub: "Full system access" },
];
const F02_PROVIDERS = [
  { icon: Cloud, label: "Azure AD", sub: "SSO + SCIM" },
  { icon: ShieldCheck, label: "Okta", sub: "SSO + MFA" },
  { icon: User, label: "Local (JWT)", sub: "Password-based" },
];

const F02_ROLE_VALUES = ["SALES_REP", "SUPPORT_AGENT", "MARKETING", "MANAGER", "ADMIN"] as const;
const F02_PROVIDER_VALUES = ["AZURE_AD", "OKTA", "LOCAL"] as const;

type F02Form = {
  fullName: string;
  email: string;
  jobTitle: string;
  phone: string;
  department: string;
  password: string;
};

function genTempPassword(): string {
  return crypto.randomUUID().slice(0, 10);
}

function F02({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [role, setRole] = useState(0);
  const [idp, setIdp] = useState(0);
  const [mfa, setMfa] = useState(true);
  // Seeded from the role's defaults; the admin can toggle individual screens.
  const [permissions, setPermissions] = useState<string[]>(ROLE_DEFAULT_PERMISSIONS[F02_ROLE_VALUES[0]]);
  const [form, setForm] = useState<F02Form>({
    fullName: "",
    email: "",
    jobTitle: "",
    phone: "",
    department: "Sales",
    password: genTempPassword(),
  });
  const set = <K extends keyof F02Form>(key: K) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createUser = useCreateUser();

  // Picking a role re-seeds the permission toggles to that role's defaults.
  const selectRole = (i: number) => {
    setRole(i);
    setPermissions(ROLE_DEFAULT_PERMISSIONS[F02_ROLE_VALUES[i]]);
  };
  const togglePermission = (key: string) =>
    setPermissions((p) => (p.includes(key) ? p.filter((k) => k !== key) : [...p, key]));

  const primaryLabel = step < 3 ? `Next: ${F02_STEPS[step + 1]}` : "Create user";
  const primaryIcon = step === 3 ? Check : undefined;

  const handlePrimary = async () => {
    if (step === 0 && (!form.fullName.trim() || !form.email.trim())) {
      toast.error("Full name and work email are required");
      return;
    }
    if (step < 3) {
      setStep((s) => s + 1);
      return;
    }
    try {
      await createUser.mutateAsync({
        fullName: form.fullName,
        email: form.email,
        password: form.password,
        jobTitle: form.jobTitle || undefined,
        phone: form.phone || undefined,
        department: form.department,
        role: F02_ROLE_VALUES[role],
        permissions,
        identityProvider: F02_PROVIDER_VALUES[idp],
        mfaEnabled: mfa,
      });
      toast.success("User created successfully", {
        description: `${form.fullName} (${F02_ROLES[role].label}) can now log in with email "${form.email}" and password "${form.password}". Share this with them — they should change it after first login.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to create user", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this user anytime from Security & audit.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F02_STEPS} current={step} />

      {step === 0 && <F02Details form={form} set={set} />}
      {step === 1 && <F02Role role={role} setRole={selectRole} permissions={permissions} togglePermission={togglePermission} />}
      {step === 2 && <F02Identity idp={idp} setIdp={setIdp} mfa={mfa} setMfa={setMfa} />}
      {step === 3 && <F02Review form={form} role={role} idp={idp} mfa={mfa} permissions={permissions} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F02 step 1: User details ---- */
function F02Details({ form, set }: { form: F02Form; set: <K extends keyof F02Form>(key: K) => (v: string) => void }) {
  return (
    <Card title="User details">
      <Stack>
        <AIInsightBox text="AI will suggest a role and default permissions in the next step based on the department you choose." />
        <FieldGrid>
          <Field label="Full name" value={form.fullName} onChange={set("fullName")} placeholder="e.g. Alex Morgan" />
          <Field label="Work email" value={form.email} onChange={set("email")} placeholder="alex.morgan@example.com" />
          <Field label="Job title" value={form.jobTitle} onChange={set("jobTitle")} placeholder="e.g. Support Specialist" />
          <Field label="Phone" value={form.phone} onChange={set("phone")} placeholder="+1 (555) 000-0000" />
          <Field label="Department" type="select" value={form.department} onChange={set("department")} options={["Sales", "Support", "Marketing", "IT / Admin"]} />
          <Field label="Temporary password" value={form.password} onChange={set("password")} />
        </FieldGrid>
        <div style={{ fontSize: 11, color: colors.textTertiary }}>
          Share the temporary password with the new user directly — there's no invite-email flow yet, so this is the only way they'll know it.
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F02 step 2: Role & access ---- */
function F02Role({
  role,
  setRole,
  permissions,
  togglePermission,
}: {
  role: number;
  setRole: (i: number) => void;
  permissions: string[];
  togglePermission: (key: string) => void;
}) {
  const isAdmin = F02_ROLE_VALUES[role] === "ADMIN";
  return (
    <Card title="Role & access">
      <Stack>
        <RPAInsightBox text="Pick a role to seed sensible defaults, then toggle exactly which screens this user can access." />
        <div>
          <SubLabel>Role</SubLabel>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap" }}>
            {F02_ROLES.map((r, i) => (
              <OptionCard key={r.label} icon={r.icon} label={r.label} sub={r.sub} selected={role === i} onClick={() => setRole(i)} />
            ))}
          </div>
        </div>
        <div>
          <SubLabel>Screen access</SubLabel>
          {isAdmin ? (
            <div style={{ fontSize: 12, color: colors.textSecondary, padding: "8px 0" }}>
              Admins always have full access to every screen — individual toggles don't apply.
            </div>
          ) : (
            PERMISSION_CATALOG.map((p) => (
              <ToggleRow
                key={p.key}
                label={p.label}
                on={permissions.includes(p.key)}
                onChange={() => togglePermission(p.key)}
              />
            ))
          )}
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F02 step 3: Identity provider ---- */
function F02Identity({ idp, setIdp, mfa, setMfa }: { idp: number; setIdp: (i: number) => void; mfa: boolean; setMfa: (v: boolean) => void }) {
  const [extra, setExtra] = useState([true, false]);
  return (
    <Card title="Identity provider">
      <Stack>
        <RPAInsightBox text="Choose how this user authenticates. SSO providers enforce your organization's security policies automatically." />
        <div>
          <SubLabel>Provider</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F02_PROVIDERS.map((p, i) => (
              <OptionCard key={p.label} icon={p.icon} label={p.label} sub={p.sub} selected={idp === i} onClick={() => setIdp(i)} />
            ))}
          </div>
        </div>
        <div>
          <SubLabel>Security</SubLabel>
          <ToggleRow label="Enforce multi-factor authentication (MFA)" on={mfa} onChange={() => setMfa(!mfa)} />
          <ToggleRow label="Single sign-on (SSO)" on={extra[0]} onChange={() => setExtra((t) => [!t[0], t[1]])} />
          <ToggleRow label="LDAP / Active Directory sync" on={extra[1]} onChange={() => setExtra((t) => [t[0], !t[1]])} />
        </div>
        <FieldGrid>
          <Field label="Session timeout" type="select" options={["30 minutes", "1 hour", "4 hours"]} />
          <Field label="Password policy" type="select" options={["Strong (12+ chars)", "Standard (8+ chars)", "SSO-managed"]} />
        </FieldGrid>
      </Stack>
    </Card>
  );
}

/* ---- F02 step 4: Review ---- */
function F02Review({ form, role, idp, mfa, permissions }: { form: F02Form; role: number; idp: number; mfa: boolean; permissions: string[] }) {
  const isAdmin = F02_ROLE_VALUES[role] === "ADMIN";
  const accessSummary = isAdmin
    ? "Full access (Admin)"
    : PERMISSION_CATALOG.filter((p) => permissions.includes(p.key)).map((p) => p.label).join(", ") || "No screens — dashboard only";
  const summary = [
    { label: "Full name", value: form.fullName || "—" },
    { label: "Work email", value: form.email || "—" },
    { label: "Department", value: form.department },
    { label: "Role", value: F02_ROLES[role].label },
    { label: "Screen access", value: accessSummary },
    { label: "Identity provider", value: F02_PROVIDERS[idp].label },
    { label: "MFA", value: mfa ? "Enforced" : "Not enforced" },
    { label: "Temporary password", value: form.password },
  ];
  return (
    <Card title="Review">
      <Stack>
        <AIInsightBox text="Review the account before creating it. Share the temporary password with the user — they can change it after first login." />
        <div>
          <SubLabel>Summary</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {summary.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
      </Stack>
    </Card>
  );
}

/* ============ F03 Create Lead ============ */
const F03_STEPS = ["Capture method", "Lead details", "Result"];

const F03_CAPTURE_METHODS = ["WEB_FORM", "EMAIL_PARSING", "RPA_BOT_IMPORT"] as const;
// Matches the backend's @DecimalMax on LeadRequest.estimatedDealValue — the
// DB column is NUMERIC(15,2), which overflows at 10^13.
const MAX_DEAL_VALUE = 9_999_999_999_999.99;

type F03Form = {
  fullName: string;
  company: string;
  email: string;
  phone: string;
  product: string;
  estimatedDealValue: string;
  sourceChannel: string;
  notes: string;
};

type EmailPasteForm = {
  from: string;
  subject: string;
  body: string;
};

function F03({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [capture, setCapture] = useState(0);
  const isEmailCapture = capture === 1;
  const captureOptions = [
    { icon: FileText, label: "Web form" },
    { icon: Mail, label: "Email parsing" },
    { icon: Bot, label: "RPA bot import" },
  ];
  const [form, setForm] = useState<F03Form>({
    fullName: "",
    company: "",
    email: "",
    phone: "",
    product: "Enterprise Plan",
    estimatedDealValue: "",
    sourceChannel: "Web form",
    notes: "",
  });
  const [emailForm, setEmailForm] = useState<EmailPasteForm>({ from: "", subject: "", body: "" });
  const setEmailField = <K extends keyof EmailPasteForm>(key: K) => (v: string) => setEmailForm((f) => ({ ...f, [key]: v }));
  const set = <K extends keyof F03Form>(key: K) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createLead = useCreateLead();
  const createLeadFromEmail = useCreateLeadFromEmail();
  const isPending = isEmailCapture ? createLeadFromEmail.isPending : createLead.isPending;
  const [createdLead, setCreatedLead] = useState<Lead | null>(null);
  const [missingFields, setMissingFields] = useState<string[]>([]);

  const primaryLabel =
    step === 0 ? "Next: Lead details" : step === 1 ? "Create lead" : isPending ? "Scoring…" : "Done";
  const primaryIcon = step === 2 && !isPending ? Check : undefined;

  const submitLead = async () => {
    setStep(2);
    try {
      let created: Lead;
      if (isEmailCapture) {
        const result = await createLeadFromEmail.mutateAsync({
          from: emailForm.from || undefined,
          subject: emailForm.subject || undefined,
          body: emailForm.body,
        });
        created = result.lead;
        setMissingFields(result.missingFields);
      } else {
        const dealValue = parseFloat(form.estimatedDealValue.replace(/[^0-9.]/g, ""));
        created = await createLead.mutateAsync({
          fullName: form.fullName,
          company: form.company,
          email: form.email || undefined,
          phone: form.phone || undefined,
          product: form.product,
          estimatedDealValue: Number.isFinite(dealValue) ? dealValue : undefined,
          sourceChannel: form.sourceChannel,
          captureMethod: F03_CAPTURE_METHODS[capture],
          notes: form.notes || undefined,
        });
        setMissingFields([]);
      }
      setCreatedLead(created);
      toast.success("Lead created successfully", {
        description: `${created.fullName} · ${created.company || "—"} has been scored and assigned.`,
      });
    } catch (err) {
      toast.error("Failed to create lead", { description: err instanceof Error ? err.message : undefined });
      setStep(1);
    }
  };

  const handlePrimary = () => {
    if (step === 0) {
      setStep(1);
      return;
    }
    if (step === 1) {
      if (isEmailCapture) {
        if (!emailForm.body.trim()) {
          toast.error("Paste the email content before continuing");
          return;
        }
        submitLead();
        return;
      }
      if (!form.fullName.trim() || !form.company.trim()) {
        toast.error("Full name and company are required");
        return;
      }
      if (form.email.trim() && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.email.trim())) {
        toast.error("Enter a valid email address, or leave it blank");
        return;
      }
      const dealValue = parseFloat(form.estimatedDealValue.replace(/[^0-9.]/g, ""));
      if (Number.isFinite(dealValue) && dealValue > MAX_DEAL_VALUE) {
        toast.error("Estimated deal value is too large", { description: "Enter a value under 10 trillion." });
        return;
      }
      submitLead();
      return;
    }
    if (!isPending) onCancel();
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this lead anytime from Lead management.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F03_STEPS} current={step} />

      {step === 0 && (
        <Card title="Capture method">
          <Stack>
            <AIInsightBox text="Choose how this lead is entering the CRM. AI scoring and routing run automatically once details are saved." />
            <div>
              <SubLabel>How is this lead captured?</SubLabel>
              <div style={{ display: "flex", gap: 10 }}>
                {captureOptions.map((o, i) => (
                  <OptionCard key={o.label} icon={o.icon} label={o.label} selected={capture === i} onClick={() => setCapture(i)} />
                ))}
              </div>
            </div>
          </Stack>
        </Card>
      )}

      {step === 1 && isEmailCapture && (
        <Card title="Paste email">
          <Stack>
            <AIInsightBox text="Paste the email as-is. The sender, subject, and body are parsed into a lead, then scored and assigned automatically — no manual fields to fill in." />
            <FieldGrid>
              <Field label="From (name or email)" value={emailForm.from} onChange={setEmailField("from")} placeholder="Priya Sharma <priya@medtech.com>" />
              <Field label="Subject" value={emailForm.subject} onChange={setEmailField("subject")} placeholder="Interested in your CRM Suite" />
            </FieldGrid>
            <div>
              <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>Email body</label>
              <textarea
                value={emailForm.body}
                onChange={(e) => setEmailField("body")(e.target.value)}
                placeholder="Paste the email content here — e.g. 'Hi, we're evaluating CRM options for our 200-person sales team, budget is approved for Q3, can we schedule a demo this week?'"
                style={{ width: "100%", border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: "8px 10px", fontSize: 12, minHeight: 160, resize: "vertical", outline: "none", fontFamily: "inherit" }}
              />
            </div>
          </Stack>
        </Card>
      )}

      {step === 1 && !isEmailCapture && (
        <Card title="Lead details">
          <Stack>
            <AIInsightBox text="AI will auto-score this lead and suggest qualification once saved." />
            <FieldGrid>
              <Field label="Full name" value={form.fullName} onChange={set("fullName")} placeholder="e.g. David Kim" />
              <Field label="Company" value={form.company} onChange={set("company")} placeholder="e.g. Initech" />
              <Field label="Email" value={form.email} onChange={set("email")} placeholder="david.kim@initech.com" />
              <Field label="Phone" value={form.phone} onChange={set("phone")} placeholder="+1 (555) 012-3456" />
              <Field label="Product" type="select" value={form.product} onChange={set("product")} options={["Enterprise Plan", "Pro Plan", "Growth Plan", "Starter Plan"]} />
              <Field label="Estimated deal value" value={form.estimatedDealValue} onChange={set("estimatedDealValue")} placeholder="31000" />
              <Field label="Source channel" type="select" value={form.sourceChannel} onChange={set("sourceChannel")} options={["Web form", "Referral", "Cold outreach", "Event"]} />
            </FieldGrid>
            <div>
              <label style={{ fontSize: 11, fontWeight: 500, color: colors.textSecondary, display: "block", marginBottom: 5 }}>Notes</label>
              <textarea
                value={form.notes}
                onChange={(e) => set("notes")(e.target.value)}
                placeholder="Interested in the Enterprise Plan, mentioned Q3 budget approval pending."
                style={{ width: "100%", border: `0.5px solid ${colors.border}`, borderRadius: 6, padding: "8px 10px", fontSize: 12, minHeight: 64, resize: "vertical", outline: "none", fontFamily: "inherit" }}
              />
            </div>
          </Stack>
        </Card>
      )}

      {step === 2 && <F03Result pending={isPending} lead={createdLead} missingFields={missingFields} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step === 1 ? () => setStep(0) : undefined}
        onNext={handlePrimary}
        onSaveDraft={step < 2 ? handleSaveDraft : undefined}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F03 step 3: real result (post-submit, not a pre-submit preview —
   scoring/assignment only exist once the lead is actually saved, so
   showing them before submit would mean either faking them again or
   paying for a second, throwaway model call) ---- */
export const MISSING_FIELD_LABELS: Record<string, string> = {
  company: "Company",
  email: "Email",
  phone: "Phone",
  sourceChannel: "Source channel",
};

function F03Result({ pending, lead, missingFields }: { pending: boolean; lead: Lead | null; missingFields: string[] }) {
  const { data: users } = useUsers();
  const assignee = lead?.assignedToId ? users?.find((u) => u.id === lead.assignedToId) : undefined;

  if (pending || !lead) {
    return (
      <Card title="Scoring & assignment">
        <Stack>
          <AIInsightBox text="Running the fine-tuned model to score this lead and picking the best-fit owner. This calls a real model, not a preview — it can take up to a minute." />
          <div style={{ padding: "40px 0", textAlign: "center", fontSize: 12, color: colors.textSecondary }}>
            Scoring in progress — please don't close this window…
          </div>
        </Stack>
      </Card>
    );
  }

  return (
    <Card title="Lead created">
      <Stack>
        {lead.aiScore != null ? (
          <AiScorePreview
            score={String(lead.aiScore)}
            label={lead.aiScoreLabel ?? "Scored"}
            reason={lead.aiScoreReason ?? ""}
            badge={{ label: lead.aiScoreLabel ?? "Scored", variant: scoreVariant(lead.aiScore) }}
          />
        ) : (
          <div style={{ fontSize: 12, color: colors.textSecondary, padding: "8px 0" }}>
            AI scoring is temporarily unavailable — the lead was saved without a score.
          </div>
        )}
        <div>
          <SubLabel>Assigned to</SubLabel>
          <div style={{ fontSize: 13, color: colors.textPrimary, fontWeight: 500 }}>
            {assignee ? assignee.fullName : "Unassigned — no sales reps in your organization yet"}
          </div>
        </div>
        {missingFields.length > 0 && (
          <div style={{ background: "#FFF8E6", border: "0.5px solid #E8C468", borderRadius: 6, padding: 10, fontSize: 12, color: "#7A5B00" }}>
            Not captured: {missingFields.map((f) => MISSING_FIELD_LABELS[f] ?? f).join(", ")}. You can add these from the lead's detail view.
          </div>
        )}
      </Stack>
    </Card>
  );
}

/* ---- F03 navigation footer (Back / Save draft / Next) ---- */
function F03Footer({
  onCancel,
  onBack,
  onNext,
  onSaveDraft,
  primaryLabel,
  primaryIcon,
}: {
  onCancel: () => void;
  onBack?: () => void;
  onNext?: () => void;
  onSaveDraft?: () => void;
  primaryLabel: string;
  primaryIcon?: React.ComponentType<{ size?: number; color?: string }>;
}) {
  return (
    <div style={{ display: "flex", justifyContent: "space-between", marginTop: 4 }}>
      <div style={{ display: "flex", gap: 8 }}>
        <Button label="Cancel" onClick={onCancel} />
        {onBack && <Button label="Back" onClick={onBack} />}
      </div>
      <div style={{ display: "flex", gap: 8 }}>
        <Button label="Save draft" onClick={onSaveDraft} />
        <Button label={primaryLabel} icon={primaryIcon} variant="primary" onClick={onNext} />
      </div>
    </div>
  );
}

/* ============ F04 Create Account ============ */
const F04_STEPS = ["Company info", "Contacts", "Review"];

type F04Form = {
  name: string;
  industry: string;
  annualRevenue: string;
  employeeCount: string;
  billingAddress: string;
};

function F04({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<F04Form>({
    name: "",
    industry: "IT Services",
    annualRevenue: "",
    employeeCount: "1-50",
    billingAddress: "",
  });
  const set = <K extends keyof F04Form>(key: K) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createAccount = useCreateAccount();

  const primaryLabel = step < 2 ? `Next: ${F04_STEPS[step + 1]}` : "Create account";
  const primaryIcon = step === 2 ? Check : undefined;

  const handlePrimary = async () => {
    if (step === 0 && !form.name.trim()) {
      toast.error("Account name is required");
      return;
    }
    if (step < 2) {
      setStep((s) => s + 1);
      return;
    }
    try {
      const revenue = parseFloat(form.annualRevenue.replace(/[^0-9.]/g, ""));
      await createAccount.mutateAsync({
        name: form.name,
        industry: form.industry,
        annualRevenue: Number.isFinite(revenue) ? revenue : undefined,
        employeeCount: form.employeeCount,
        billingAddress: form.billingAddress || undefined,
        emailIntegrationEnabled: true,
        docRepoSyncEnabled: true,
      });
      toast.success("Account created successfully", {
        description: `${form.name} has been added.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to create account", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this account anytime from Accounts & contacts.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F04_STEPS} current={step} />

      {step === 0 && (
        <Card title="Company info">
          <Stack>
            <FieldGrid>
              <Field label="Account name" value={form.name} onChange={set("name")} placeholder="e.g. Acme Corp" />
              <Field label="Industry" type="select" value={form.industry} onChange={set("industry")} options={["IT Services", "Manufacturing", "Finance", "Healthcare"]} />
              <Field label="Annual revenue" value={form.annualRevenue} onChange={set("annualRevenue")} placeholder="0" />
              <Field label="Employee count" type="select" value={form.employeeCount} onChange={set("employeeCount")} options={["1-50", "51-200", "201-1000", "1000+"]} />
            </FieldGrid>
            <Field label="Billing address" value={form.billingAddress} onChange={set("billingAddress")} placeholder="Street, city, state, PIN" />
          </Stack>
        </Card>
      )}

      {step === 1 && <F04Contacts />}
      {step === 2 && <F04Review name={form.name} industry={form.industry} employeeCount={form.employeeCount} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F04 step 2: Contacts ---- */
function F04Contacts() {
  const contacts = [
    { initials: "JM", name: "James Miller", role: "VP of Engineering", email: "james.miller@acme.com", primary: true, email_on: true, sms_on: true },
    { initials: "EW", name: "Emma Wilson", role: "Procurement Lead", email: "emma.wilson@acme.com", primary: false, email_on: true, sms_on: false },
  ];
  const [prefs, setPrefs] = useState(contacts.map((c) => ({ email: c.email_on, sms: c.sms_on })));
  return (
    <Card title="Contacts">
      <Stack>
        <AIInsightBox text="AI enriched these contacts from the company domain and matched their roles to your buying-committee template." />
        <div>
          <SubLabel>Contacts</SubLabel>
          <Stack gap={8}>
            {contacts.map((c, i) => (
              <div key={c.email} style={{ padding: "10px 12px", border: `0.5px solid ${colors.border}`, borderRadius: 6 }}>
                <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                  <Avatar initials={c.initials} color={colors.primary} size={36} />
                  <div style={{ flex: 1 }}>
                    <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                      <span style={{ fontSize: 13, fontWeight: 600, color: colors.textPrimary }}>{c.name}</span>
                      {c.primary && <Badge label="Primary" variant="green" />}
                    </div>
                    <div style={{ fontSize: 12, color: colors.textSecondary }}>{c.role} · {c.email}</div>
                  </div>
                </div>
                <div style={{ display: "flex", gap: 20, marginTop: 10, paddingTop: 10, borderTop: `0.5px solid ${colors.border}` }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 8, flex: 1 }}>
                    <Mail size={14} color={colors.textSecondary} />
                    <span style={{ fontSize: 12, color: colors.textPrimary, flex: 1 }}>Email notifications</span>
                    <Switch on={prefs[i].email} onChange={() => setPrefs((p) => p.map((v, j) => (j === i ? { ...v, email: !v.email } : v)))} />
                  </div>
                  <div style={{ display: "flex", alignItems: "center", gap: 8, flex: 1 }}>
                    <MessageSquare size={14} color={colors.textSecondary} />
                    <span style={{ fontSize: 12, color: colors.textPrimary, flex: 1 }}>SMS notifications</span>
                    <Switch on={prefs[i].sms} onChange={() => setPrefs((p) => p.map((v, j) => (j === i ? { ...v, sms: !v.sms } : v)))} />
                  </div>
                </div>
              </div>
            ))}
          </Stack>
        </div>
        <div>
          <SubLabel>Add a contact</SubLabel>
          <FieldGrid>
            <Field label="Full name" placeholder="e.g. Alex Rivera" />
            <Field label="Job title" placeholder="e.g. IT Director" />
            <Field label="Email" placeholder="name@acme.com" />
            <Field label="Phone" placeholder="+1 (555) 000-0000" />
            <Field label="Role" type="select" options={["Decision maker", "Champion", "Influencer", "Gatekeeper"]} />
            <Field label="Set as primary" type="select" options={["No", "Yes"]} />
            <Field label="Email notifications" type="select" options={["Enabled", "Disabled"]} />
            <Field label="SMS notifications" type="select" options={["Enabled", "Disabled"]} />
          </FieldGrid>
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F04 step 3: Review ---- */
function F04Review({ name, industry, employeeCount }: { name: string; industry: string; employeeCount: string }) {
  const summary = [
    { label: "Account name", value: name || "—" },
    { label: "Industry", value: industry },
    { label: "Employee count", value: employeeCount },
    { label: "Primary contact", value: "James Miller" },
    { label: "Contacts", value: "2 added" },
    { label: "Notifications", value: "Email · SMS" },
  ];
  return (
    <Card title="Review">
      <Stack>
        <AIInsightBox text="Everything looks good. Review the account details below before creating it." />
        <div>
          <SubLabel>Summary</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {summary.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
      </Stack>
    </Card>
  );
}

/* ============ F05 New Workflow ============ */
const F05_STEPS = ["Name & trigger", "Build flow", "Test", "Activate"];

type F05Form = { name: string; triggerEvent: string; scope: string; runMode: string };

function F05({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState<F05Form>({
    name: "",
    triggerEvent: "Lead score > 80",
    scope: "All records",
    runMode: "Real-time",
  });
  const set = <K extends keyof F05Form>(key: K) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createWorkflow = useCreateWorkflow();
  const activateWorkflow = useActivateWorkflow();

  const primaryLabel = step < 3 ? `Next: ${F05_STEPS[step + 1]}` : "Activate workflow";
  const primaryIcon = step === 3 ? Zap : undefined;

  const handlePrimary = async () => {
    if (step === 0 && !form.name.trim()) {
      toast.error("Workflow name is required");
      return;
    }
    if (step < 3) {
      setStep((s) => s + 1);
      return;
    }
    try {
      // Nodes match the canvas shown in the "Build flow" step above — the
      // visual builder doesn't yet expose its live state back to the wizard.
      const workflow = await createWorkflow.mutateAsync({
        name: form.name,
        triggerEvent: form.triggerEvent,
        scope: form.scope,
        runMode: form.runMode,
        nodes: [
          { type: "TRIGGER", title: "Trigger", label: "Score > 80", order: 0 },
          { type: "CONDITION", title: "Condition", label: "Region = APAC?", order: 1 },
          { type: "AI", title: "AI action", label: "Draft intro email", order: 2 },
          { type: "ACTION", title: "Notify", label: "Alert sales lead", order: 3 },
        ],
      });
      await activateWorkflow.mutateAsync(workflow.id);
      toast.success("Workflow activated", {
        description: `'${form.name}' is now live and listening for triggers.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to activate workflow", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this workflow anytime from the Workflow engine.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F05_STEPS} current={step} />

      {step === 0 && (
        <Card title="Name & trigger">
          <Stack>
            <AIInsightBox text="Give the workflow a name and choose the event that starts it. AI will suggest matching building blocks in the next step." />
            <FieldGrid>
              <Field label="Workflow name" value={form.name} onChange={set("name")} placeholder="e.g. High-value lead escalation" />
              <Field label="Trigger event" type="select" value={form.triggerEvent} onChange={set("triggerEvent")} options={["Lead score > 80", "Case SLA breach", "Deal stage change", "Scheduled (daily)"]} />
              <Field label="Scope" type="select" value={form.scope} onChange={set("scope")} options={["All records", "My team", "Specific segment"]} />
              <Field label="Run mode" type="select" value={form.runMode} onChange={set("runMode")} options={["Real-time", "Batched (hourly)", "Scheduled"]} />
            </FieldGrid>
          </Stack>
        </Card>
      )}

      {step === 1 && (
        <Card title="Build flow">
          <Stack>
            <AIInsightBox text="Drag building blocks onto the canvas, then drag them left or right to reorder. Remove a step with the × on each node." />
            <WorkflowBuilder
              initialNodes={[
                { type: "trigger", title: "Trigger", label: "Score > 80" },
                { type: "condition", title: "Condition", label: "Region = APAC?" },
                { type: "ai", title: "AI action", label: "Draft intro email" },
                { type: "action", title: "Notify", label: "Alert sales lead" },
              ]}
            />
          </Stack>
        </Card>
      )}

      {step === 2 && <F05Test />}
      {step === 3 && <F05Activate name={form.name} triggerEvent={form.triggerEvent} runMode={form.runMode} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F05 step 3: Test ---- */
function F05Test() {
  const steps = [
    { status: "pass", label: "Trigger fired — sample lead 'David Kim' (score 84)" },
    { status: "pass", label: "Condition evaluated — Region = APAC → true" },
    { status: "pass", label: "AI action — intro email drafted (142 words)" },
    { status: "warning", label: "Notify — sales lead has no Slack channel linked" },
  ];
  return (
    <Card title="Test workflow">
      <Stack>
        <AIInsightBox text="A dry run was executed against a sample record. No live data was changed." />
        <FieldGrid>
          <Field label="Test record" type="select" options={["Sample: David Kim (score 84)", "Sample: Emma Wilson (score 91)", "Pick a live lead"]} />
          <Field label="Mode" type="select" options={["Dry run (no side effects)", "Live test"]} />
        </FieldGrid>
        <div>
          <SubLabel>Execution trace</SubLabel>
          <Stack gap={6}>
            {steps.map((s) => (
              <div key={s.label} style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px 10px", border: `0.5px solid ${colors.border}`, borderRadius: 6, fontSize: 12 }}>
                {s.status === "pass" ? <Check size={15} color={colors.success} /> : <AlertTriangle size={15} color="#BA7517" />}
                <span style={{ color: colors.textPrimary }}>{s.label}</span>
              </div>
            ))}
          </Stack>
        </div>
        <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", background: colors.successLight, border: `0.5px solid ${colors.success}`, borderRadius: 6, padding: "10px 12px" }}>
          <span style={{ fontSize: 12, fontWeight: 600, color: colors.success }}>3 of 4 steps passed</span>
          <Badge label="Ready to activate" variant="green" />
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F05 step 4: Activate ---- */
function F05Activate({ name, triggerEvent, runMode }: { name: string; triggerEvent: string; runMode: string }) {
  const [toggles, setToggles] = useState([true, true, false]);
  const summary = [
    { label: "Workflow name", value: name || "—" },
    { label: "Trigger", value: triggerEvent },
    { label: "Steps", value: "4 (Trigger → Condition → AI → Notify)" },
    { label: "Run mode", value: runMode },
    { label: "Last test", value: "3 of 4 passed" },
  ];
  return (
    <Card title="Activate">
      <Stack>
        <AIInsightBox text="Review the workflow before it goes live. Once active, it runs automatically whenever the trigger fires." />
        <div>
          <SubLabel>Summary</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {summary.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
        <div>
          <SubLabel>Activation options</SubLabel>
          {["BPMN 2.0 compliant export", "Log every execution step", "Notify me on first run"].map((label, i) => (
            <ToggleRow key={label} label={label} on={toggles[i]} onChange={() => setToggles((t) => t.map((v, j) => (j === i ? !v : v)))} />
          ))}
        </div>
      </Stack>
    </Card>
  );
}

/* ============ F06 Deploy Bot ============ */
const F06_STEPS = ["Select bot", "Choose environment", "Configure schedule", "Confirm deploy"];

const F06_BOTS = [
  { icon: Bot, label: "Lead Portal Scraper", sub: "UiPath · v1.4", platform: "UiPath" },
  { icon: Mail, label: "Email Ticket Bot", sub: "UiPath · v2.1", platform: "UiPath" },
  { icon: FileText, label: "Proposal Generator", sub: "Automation Anywhere", platform: "Automation Anywhere" },
];
const F06_INFRA = [
  { icon: Cloud, label: "Virtual machine", sub: "Auto-scaled" },
  { icon: Monitor, label: "Physical machine", sub: "Dedicated" },
  { icon: Box, label: "Container", sub: "Docker pool" },
];
const F06_MODES = [
  { icon: Webhook, label: "Webhook", sub: "Event-driven" },
  { icon: Clock, label: "Scheduled", sub: "Recurring" },
  { icon: User, label: "Manual", sub: "On-demand" },
];

function F06({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [bot, setBot] = useState(0);
  const [infra, setInfra] = useState(0);
  const [mode, setMode] = useState(0);
  const { data: registeredBots } = useRpaBots();
  const deployBot = useDeployRpaBot();

  const primaryLabel = step < 3 ? `Next: ${F06_STEPS[step + 1]}` : "Deploy to production";
  const primaryIcon = step === 3 ? Rocket : undefined;

  const handlePrimary = async () => {
    if (step < 3) {
      setStep((s) => s + 1);
      return;
    }
    // The demo bot list here is illustrative; deploys target the first bot
    // actually in the registry (from F01 or the built-in seeded bots).
    const target = registeredBots?.[0];
    if (!target) {
      toast.error("No bots registered yet", { description: "Create a bot first from the RPA control room." });
      return;
    }
    try {
      await deployBot.mutateAsync(target.id);
      toast.success("Bot deployed to production", {
        description: `'${target.name}' is live on a ${F06_INFRA[infra].label.toLowerCase()} with a ${F06_MODES[mode].label.toLowerCase()} run mode.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to deploy bot", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this deployment anytime from the RPA control room.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F06_STEPS} current={step} />

      {step === 0 && <F06SelectBot bot={bot} setBot={setBot} />}
      {step === 1 && <F06Environment infra={infra} setInfra={setInfra} />}
      {step === 2 && <F06Schedule mode={mode} setMode={setMode} />}
      {step === 3 && <F06Confirm bot={bot} infra={infra} mode={mode} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F06 step 1: Select bot ---- */
function F06SelectBot({ bot, setBot }: { bot: number; setBot: (i: number) => void }) {
  return (
    <Card title="Select bot">
      <Stack>
        <RPAInsightBox text="Step 1 of 4 — Choose which bot from the registry you want to deploy." />
        <div>
          <SubLabel>Bot registry</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F06_BOTS.map((b, i) => (
              <OptionCard key={b.label} icon={b.icon} label={b.label} sub={b.sub} selected={bot === i} onClick={() => setBot(i)} />
            ))}
          </div>
        </div>
        <FieldGrid>
          <Field label="Version to deploy" type="select" options={["v1.4 (latest)", "v1.3", "v1.2"]} />
          <Field label="Bot type" type="select" options={["Unattended", "Attended"]} />
        </FieldGrid>
      </Stack>
    </Card>
  );
}

/* ---- F06 step 2: Choose environment ---- */
function F06Environment({ infra, setInfra }: { infra: number; setInfra: (i: number) => void }) {
  return (
    <Card title="Choose environment">
      <Stack>
        <RPAInsightBox text="Step 2 of 4 — Select the target environment and infrastructure the bot will run on." />
        <FieldGrid>
          <Field label="Environment" type="select" options={["Production", "Staging", "Development"]} />
          <Field label="Region" type="select" options={["US-East", "US-West", "EU-Central", "APAC"]} />
        </FieldGrid>
        <div>
          <SubLabel>Infrastructure</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F06_INFRA.map((o, i) => (
              <OptionCard key={o.label} icon={o.icon} label={o.label} sub={o.sub} selected={infra === i} onClick={() => setInfra(i)} />
            ))}
          </div>
        </div>
        <FieldGrid>
          <Field label="Concurrent runners" type="select" options={["1", "2", "4", "Auto-scale"]} />
          <Field label="Timeout per run" type="select" options={["5 minutes", "15 minutes", "1 hour"]} />
        </FieldGrid>
      </Stack>
    </Card>
  );
}

/* ---- F06 step 3: Configure schedule ---- */
function F06Schedule({ mode, setMode }: { mode: number; setMode: (i: number) => void }) {
  const [toggles, setToggles] = useState([true, false]);
  return (
    <Card title="Configure schedule">
      <Stack>
        <RPAInsightBox text="Step 3 of 4 — Decide when and how often the bot runs once deployed." />
        <div>
          <SubLabel>Run mode</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F06_MODES.map((m, i) => (
              <OptionCard key={m.label} icon={m.icon} label={m.label} sub={m.sub} selected={mode === i} onClick={() => setMode(i)} />
            ))}
          </div>
        </div>
        <FieldGrid>
          <Field label="Frequency" type="select" options={["Every 2 hours", "Hourly", "Daily at 09:00", "Custom cron"]} />
          <Field label="Cron expression" value="0 */2 * * *" />
          <Field label="Start date" value="15/07/2026" />
          <Field label="Time zone" type="select" options={["UTC", "US/Eastern", "Europe/London", "Asia/Singapore"]} />
        </FieldGrid>
        <div>
          <SubLabel>Options</SubLabel>
          {["Skip run if previous still active", "Pause on 3 consecutive failures"].map((label, i) => (
            <ToggleRow key={label} label={label} on={toggles[i]} onChange={() => setToggles((t) => t.map((v, j) => (j === i ? !v : v)))} />
          ))}
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F06 step 4: Confirm deploy ---- */
function F06Confirm({ bot, infra, mode }: { bot: number; infra: number; mode: number }) {
  const reviewRows = [
    { label: "Bot name", value: F06_BOTS[bot].label },
    { label: "Platform", value: F06_BOTS[bot].platform },
    { label: "Environment", value: "Production · US-East" },
    { label: "Infrastructure", value: `${F06_INFRA[infra].label} (${F06_INFRA[infra].sub.toLowerCase()})` },
    { label: "Run mode", value: `${F06_MODES[mode].label} — ${F06_MODES[mode].sub.toLowerCase()}` },
    { label: "Bot type", value: "Unattended" },
    { label: "Credential vault", value: "Linked — portal_creds_01" },
  ];
  const checks = [
    { status: "pass", label: "Credential vault access verified" },
    { status: "pass", label: "Sandbox test passed (14/14 leads processed)" },
    { status: "warning", label: "ERP endpoint rate limit not yet confirmed" },
  ];
  return (
    <Card title="Confirm deploy">
      <Stack>
        <RPAInsightBox text="Final step — review configuration before deploying 'Lead Portal Scraper Bot' to production." />
        <div>
          <SubLabel>Configuration</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {reviewRows.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
        <div>
          <SubLabel>Pre-deploy checks</SubLabel>
          <Stack gap={6}>
            {checks.map((c) => (
              <div key={c.label} style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px 10px", border: `0.5px solid ${colors.border}`, borderRadius: 6, fontSize: 12 }}>
                {c.status === "pass" ? <Check size={15} color={colors.success} /> : <AlertTriangle size={15} color="#BA7517" />}
                <span style={{ color: colors.textPrimary }}>{c.label}</span>
              </div>
            ))}
          </Stack>
        </div>
      </Stack>
    </Card>
  );
}

/* ============ F07 New Deal ============ */
const F07_STEPS = ["Deal info", "Pipeline stage", "AI forecast", "Save"];

const F07_STAGES = [
  { icon: Sparkles, label: "Prospecting", sub: "10% · early interest", pct: 10 },
  { icon: UserCheck, label: "Qualification", sub: "30% · budget confirmed", pct: 30 },
  { icon: FileText, label: "Proposal", sub: "60% · quote sent", pct: 60 },
  { icon: GitFork, label: "Negotiation", sub: "80% · terms in review", pct: 80 },
];

const F07_STAGE_VALUES = ["PROSPECTING", "QUALIFICATION", "PROPOSAL", "NEGOTIATION"] as const;

type F07Form = { name: string; accountName: string; value: string; currency: string };

function F07({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [stage, setStage] = useState(2);
  const { data: accounts } = useAccounts();
  const accountNames = (accounts ?? []).map((a) => a.name);
  const [form, setForm] = useState<F07Form>({ name: "", accountName: "", value: "", currency: "USD" });
  const set = <K extends keyof F07Form>(key: K) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createDeal = useCreateDeal();

  const primaryLabel = step < 3 ? `Next: ${F07_STEPS[step + 1]}` : "Create deal";
  const primaryIcon = step === 3 ? Check : undefined;

  const handlePrimary = async () => {
    if (step === 0 && !form.name.trim()) {
      toast.error("Deal name is required");
      return;
    }
    if (step < 3) {
      setStep((s) => s + 1);
      return;
    }
    const account = accounts?.find((a) => a.name === (form.accountName || accountNames[0]));
    if (!account) {
      toast.error("Select an account", {
        description: "Add an account first from Accounts & contacts, then link this deal to it.",
      });
      return;
    }
    try {
      const value = parseFloat(form.value.replace(/[^0-9.]/g, ""));
      await createDeal.mutateAsync({
        name: form.name,
        accountId: account.id,
        value: Number.isFinite(value) ? value : 0,
        currency: form.currency,
        stage: F07_STAGE_VALUES[stage],
        probability: F07_STAGES[stage].pct,
        autoGenerateProposal: true,
        pushToErpOnClose: true,
      });
      toast.success("Deal created successfully", {
        description: `${form.name} added to the ${F07_STAGES[stage].label} stage.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to create deal", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this deal anytime from Sales pipeline.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F07_STEPS} current={step} />

      {step === 0 && (
        <Card title="Deal info">
          <Stack>
            <FieldGrid>
              <Field label="Deal name" value={form.name} onChange={set("name")} placeholder="e.g. Acme Corp — Enterprise Plan" />
              {accountNames.length > 0 ? (
                <Field label="Account" type="select" value={form.accountName || accountNames[0]} onChange={set("accountName")} options={accountNames} />
              ) : (
                <Field label="Account" value="No accounts yet — add one first" onChange={() => {}} />
              )}
              <Field label="Deal value" value={form.value} onChange={set("value")} placeholder="42000" />
              <Field label="Currency" type="select" value={form.currency} onChange={set("currency")} options={["USD", "EUR", "GBP"]} />
            </FieldGrid>
            <AIInsightBox text="Proposal document and follow-up email will be auto-drafted once this deal reaches the 'Proposal' stage." />
          </Stack>
        </Card>
      )}

      {step === 1 && <F07Stage stage={stage} setStage={setStage} />}
      {step === 2 && <F07Forecast />}
      {step === 3 && <F07Save name={form.name} stage={stage} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F07 step 2: Pipeline stage ---- */
function F07Stage({ stage, setStage }: { stage: number; setStage: (i: number) => void }) {
  const checklist = [
    { done: true, label: "Discovery call completed" },
    { done: true, label: "Budget & authority confirmed" },
    { done: false, label: "Proposal sent for review" },
    { done: false, label: "Contract terms agreed" },
  ];
  const pct = F07_STAGES[stage].pct;
  return (
    <Card title="Pipeline stage">
      <Stack>
        <AIInsightBox text="AI suggests placing this deal at the 'Proposal' stage based on the quote already shared and the account's engagement level." />
        <div>
          <SubLabel>Select stage</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F07_STAGES.map((s, i) => (
              <OptionCard key={s.label} icon={s.icon} label={s.label} sub={s.sub} selected={stage === i} onClick={() => setStage(i)} />
            ))}
          </div>
        </div>
        <ProgressBar label="Stage progress" value={`${pct}%`} pct={pct} color={colors.primary} />
        <FieldGrid>
          <Field label="Probability" value={`${pct}%`} />
          <Field label="Expected close date" value="30/09/2026" />
        </FieldGrid>
        <div>
          <SubLabel>Stage checklist</SubLabel>
          <Stack gap={6}>
            {checklist.map((c) => (
              <div key={c.label} style={{ display: "flex", alignItems: "center", gap: 8, padding: "8px 10px", border: `0.5px solid ${colors.border}`, borderRadius: 6, fontSize: 12 }}>
                {c.done ? <Check size={15} color={colors.success} /> : <Clock size={15} color={colors.textTertiary} />}
                <span style={{ color: c.done ? colors.textPrimary : colors.textSecondary }}>{c.label}</span>
              </div>
            ))}
          </Stack>
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F07 step 3: AI forecast ---- */
function F07Forecast() {
  const factors = [
    { label: "Deal size fit", detail: "Matches typical won-deal range", pct: 88, color: colors.success },
    { label: "Engagement frequency", detail: "Weekly touchpoints", pct: 84, color: colors.success },
    { label: "Account history", detail: "Existing customer · expansion", pct: 90, color: colors.success },
    { label: "Competitor presence", detail: "1 competitor in evaluation", pct: 58, color: "#BA7517" },
    { label: "Timeline risk", detail: "Budget approval pending", pct: 65, color: "#BA7517" },
  ];
  const rows = [
    { label: "Weighted forecast value", value: "$25,200" },
    { label: "Best case", value: "$42,000" },
    { label: "Predicted close date", value: "Sep 2026" },
    { label: "Forecast category", value: "Commit" },
  ];
  return (
    <Card title="AI forecast">
      <Stack>
        <AIInsightBox text="TechCRM AI forecasts this deal against 18 months of closed-deal patterns for similar accounts and deal sizes." />
        <AiScorePreview
          score="87%"
          label="Predicted close probability"
          reason="Based on similar deal size, engagement frequency, and account history"
          badge={{ label: "Likely to win", variant: "green" }}
        />
        <div>
          <SubLabel>Forecast breakdown</SubLabel>
          {factors.map((f) => (
            <ProgressBar key={f.label} label={`${f.label} — ${f.detail}`} value={`${f.pct}`} pct={f.pct} color={f.color} />
          ))}
        </div>
        <div>
          <SubLabel>Revenue forecast</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {rows.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F07 step 4: Save ---- */
function F07Save({ name, stage }: { name: string; stage: number }) {
  const [toggles, setToggles] = useState([true, true, false]);
  const summary = [
    { label: "Deal name", value: name || "—" },
    { label: "Pipeline stage", value: `${F07_STAGES[stage].label} (${F07_STAGES[stage].pct}%)` },
    { label: "Close probability", value: "87% · Commit" },
  ];
  return (
    <Card title="Save deal">
      <Stack>
        <AIInsightBox text="Review the deal before saving. Selected automations run immediately once the deal is created." />
        <div>
          <SubLabel>Summary</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {summary.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
        <div>
          <SubLabel>Automation</SubLabel>
          {["Auto-generate proposal via RPA bot", "Notify deal owner on save", "Push to ERP on close-won"].map((label, i) => (
            <ToggleRow key={label} label={label} on={toggles[i]} onChange={() => setToggles((t) => t.map((v, j) => (j === i ? !v : v)))} />
          ))}
        </div>
      </Stack>
    </Card>
  );
}

/* ============ F08 New Campaign ============ */
const F08_STEPS = ["Campaign details", "Audience", "Build journey", "Review & launch"];

const F08_CHANNELS = [
  { icon: Mail, label: "Email", sub: "Broadcast + nurture" },
  { icon: Webhook, label: "SMS + Email", sub: "Multi-touch" },
  { icon: Sparkles, label: "Multi-channel", sub: "Email · SMS · Social" },
];
const F08_SEGMENTS = [
  { icon: Sparkles, label: "AI segment", sub: "Auto-built from intent" },
  { icon: User, label: "Saved list", sub: "Enterprise ICP" },
  { icon: UserCheck, label: "Import", sub: "CSV / CRM filter" },
];

const F08_CHANNEL_VALUES = ["EMAIL", "SMS_EMAIL", "MULTI_CHANNEL"] as const;

type F08Form = { name: string; goal: string; budget: string };

function F08({ onCancel }: { onCancel: () => void }) {
  const [step, setStep] = useState(0);
  const [channel, setChannel] = useState(0);
  const [segment, setSegment] = useState(0);
  const [form, setForm] = useState<F08Form>({ name: "", goal: "Product launch", budget: "" });
  const set = <K extends keyof F08Form>(key: K) => (v: string) => setForm((f) => ({ ...f, [key]: v }));
  const createCampaign = useCreateCampaign();

  const primaryLabel = step < 3 ? `Next: ${F08_STEPS[step + 1]}` : "Launch campaign";
  const primaryIcon = step === 3 ? Zap : undefined;

  const handlePrimary = async () => {
    if (step === 0 && !form.name.trim()) {
      toast.error("Campaign name is required");
      return;
    }
    if (step < 3) {
      setStep((s) => s + 1);
      return;
    }
    try {
      const budget = parseFloat(form.budget.replace(/[^0-9.]/g, ""));
      await createCampaign.mutateAsync({
        name: form.name,
        channel: F08_CHANNEL_VALUES[channel],
        goal: form.goal,
        budget: Number.isFinite(budget) ? budget : undefined,
        segment: F08_SEGMENTS[segment].label,
        estimatedReach: 12480,
        status: "ACTIVE",
      });
      toast.success("Campaign launched", {
        description: `'${form.name}' is live via ${F08_CHANNELS[channel].label}.`,
      });
      onCancel();
    } catch (err) {
      toast.error("Failed to launch campaign", { description: err instanceof Error ? err.message : undefined });
    }
  };

  const handleSaveDraft = () => {
    toast.success("Draft saved", {
      description: "You can resume this campaign anytime from Marketing automation.",
    });
  };

  return (
    <Stack>
      <Stepper steps={F08_STEPS} current={step} />

      {step === 0 && <F08Details channel={channel} setChannel={setChannel} form={form} set={set} />}
      {step === 1 && <F08Audience segment={segment} setSegment={setSegment} />}
      {step === 2 && <F08Journey />}
      {step === 3 && <F08Review name={form.name} channel={channel} segment={segment} />}

      <F03Footer
        onCancel={onCancel}
        onBack={step > 0 ? () => setStep((s) => s - 1) : undefined}
        onNext={handlePrimary}
        onSaveDraft={handleSaveDraft}
        primaryLabel={primaryLabel}
        primaryIcon={primaryIcon}
      />
    </Stack>
  );
}

/* ---- F08 step 1: Campaign details ---- */
function F08Details({
  channel,
  setChannel,
  form,
  set,
}: {
  channel: number;
  setChannel: (i: number) => void;
  form: F08Form;
  set: <K extends keyof F08Form>(key: K) => (v: string) => void;
}) {
  return (
    <Card title="Campaign details">
      <Stack>
        <AIInsightBox text="AI will recommend the best send time and subject-line variants based on your audience once details are set." />
        <div>
          <SubLabel>Channel</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F08_CHANNELS.map((c, i) => (
              <OptionCard key={c.label} icon={c.icon} label={c.label} sub={c.sub} selected={channel === i} onClick={() => setChannel(i)} />
            ))}
          </div>
        </div>
        <FieldGrid>
          <Field label="Campaign name" value={form.name} onChange={set("name")} placeholder="e.g. Q3 product launch" />
          <Field label="Goal" type="select" value={form.goal} onChange={set("goal")} options={["Lead generation", "Product launch", "Re-engagement", "Event promotion"]} />
          <Field label="Budget" value={form.budget} onChange={set("budget")} placeholder="8000" />
        </FieldGrid>
      </Stack>
    </Card>
  );
}

/* ---- F08 step 2: Audience ---- */
function F08Audience({ segment, setSegment }: { segment: number; setSegment: (i: number) => void }) {
  const [toggles, setToggles] = useState([true, true, false]);
  return (
    <Card title="Audience">
      <Stack>
        <AIInsightBox text="AI built a segment of 12,480 contacts with high fit and recent engagement. Estimated open rate: 41%." />
        <div>
          <SubLabel>Audience source</SubLabel>
          <div style={{ display: "flex", gap: 10 }}>
            {F08_SEGMENTS.map((s, i) => (
              <OptionCard key={s.label} icon={s.icon} label={s.label} sub={s.sub} selected={segment === i} onClick={() => setSegment(i)} />
            ))}
          </div>
        </div>
        <FieldGrid>
          <Field label="Segment" type="select" options={["Enterprise ICP", "Mid-market", "Dormant leads", "Existing accounts"]} />
          <Field label="Region" type="select" options={["All", "North America", "EMEA", "APAC"]} />
          <Field label="Estimated reach" value="12,480 contacts" />
          <Field label="Lifecycle stage" type="select" options={["All", "Lead", "MQL", "Opportunity"]} />
        </FieldGrid>
        <div>
          <SubLabel>Suppression & compliance</SubLabel>
          {["Exclude unsubscribed contacts", "Exclude active opportunities", "Honor per-contact send frequency cap"].map((label, i) => (
            <ToggleRow key={label} label={label} on={toggles[i]} onChange={() => setToggles((t) => t.map((v, j) => (j === i ? !v : v)))} />
          ))}
        </div>
      </Stack>
    </Card>
  );
}

/* ---- F08 step 3: Build journey ---- */
function F08Journey() {
  return (
    <Card title="Build journey">
      <Stack>
        <AIInsightBox text="Drag steps onto the canvas to design the campaign journey, then reorder them. Remove a step with the × on each node." />
        <WorkflowBuilder
          initialNodes={[
            { type: "trigger", title: "Trigger", label: "Campaign start" },
            { type: "action", title: "Action", label: "Send launch email" },
            { type: "condition", title: "Condition", label: "Opened in 48h?" },
            { type: "ai", title: "AI action", label: "Personalize follow-up" },
            { type: "log", title: "Notify", label: "Alert on reply" },
          ]}
        />
      </Stack>
    </Card>
  );
}

/* ---- F08 step 4: Review & launch ---- */
function F08Review({ name, channel, segment }: { name: string; channel: number; segment: number }) {
  const summary = [
    { label: "Campaign name", value: name || "—" },
    { label: "Channel", value: F08_CHANNELS[channel].label },
    { label: "Audience", value: `${F08_SEGMENTS[segment].label} · 12,480` },
    { label: "Journey steps", value: "5" },
  ];
  return (
    <Card title="Review & launch">
      <Stack>
        <AIInsightBox text="Everything is ready. Review the campaign before launching — sends begin on the start date." />
        <div>
          <SubLabel>Summary</SubLabel>
          <div style={{ border: `0.5px solid ${colors.border}`, borderRadius: 6, overflow: "hidden" }}>
            {summary.map((r, i) => (
              <div key={r.label} style={{ display: "flex", justifyContent: "space-between", padding: "9px 12px", fontSize: 12, background: i % 2 ? colors.bgSecondary : "#FFFFFF" }}>
                <span style={{ color: colors.textSecondary }}>{r.label}</span>
                <span style={{ color: colors.textPrimary, fontWeight: 500 }}>{r.value}</span>
              </div>
            ))}
          </div>
        </div>
      </Stack>
    </Card>
  );
}

/* ---- AI score preview ---- */
function AiScorePreview({
  score,
  label,
  reason,
  badge,
}: {
  score: string;
  label: string;
  reason: string;
  badge?: { label: string; variant: BadgeVariant };
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 16, background: colors.aiLight, border: "0.5px solid #AFA9EC", borderRadius: 8, padding: 14 }}>
      <div style={{ width: 56, height: 56, borderRadius: "50%", background: "#FFFFFF", border: `2px solid ${colors.aiPurple}`, display: "flex", alignItems: "center", justifyContent: "center", fontSize: 18, fontWeight: 600, color: colors.aiPurple, flexShrink: 0 }}>
        {score}
      </div>
      <div style={{ flex: 1 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 3 }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: "#3C3489" }}>{label}</span>
          {badge && <Badge label={badge.label} variant={badge.variant} />}
        </div>
        <div style={{ fontSize: 12, color: "#3C3489" }}>{reason}</div>
      </div>
    </div>
  );
}

export function Wizard({ id, onCancel }: { id: WizardId; onCancel: () => void }) {
  switch (id) {
    case "F01":
      return <F01 onCancel={onCancel} />;
    case "F02":
      return <F02 onCancel={onCancel} />;
    case "F03":
      return <F03 onCancel={onCancel} />;
    case "F04":
      return <F04 onCancel={onCancel} />;
    case "F05":
      return <F05 onCancel={onCancel} />;
    case "F06":
      return <F06 onCancel={onCancel} />;
    case "F07":
      return <F07 onCancel={onCancel} />;
    case "F08":
      return <F08 onCancel={onCancel} />;
  }
}
