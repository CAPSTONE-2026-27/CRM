import { useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { ArrowLeft, ArrowRight, Check, Plus, Save, ShieldCheck, Sparkles, Trash2 } from "lucide-react";
import type { CampaignDetail, CampaignRequest, Criterion, FieldMeta } from "../api/types";
import { useCampaign, useCampaignCommand, useCreateCampaign, useMarketingMeta, useUpdateCampaign } from "../api/hooks";
import { Btn, Callout, ChipGroup, ErrorState, Field, Panel, Segmented, Skeleton, StatusBadge, TokenInput } from "../components/ui";
import type { Nav } from "../components/campaignActions";
import { campaignTypeLabel } from "../theme";

const STEPS = [
  { id: "basics", label: "Basic information" },
  { id: "product", label: "Product & offer" },
  { id: "audience", label: "Target audience" },
  { id: "content", label: "Email configuration" },
  { id: "rules", label: "Eligibility rules" },
] as const;
type StepId = (typeof STEPS)[number]["id"];

const OPERATOR_LABEL: Record<string, string> = {
  GT: "greater than",
  GTE: "at least",
  LT: "less than",
  LTE: "at most",
  EQ: "equals",
  NEQ: "is not",
  IN: "is one of",
  NOT_IN: "is not one of",
  CONTAINS: "contains",
};

const TONES = ["Professional", "Friendly", "Consultative", "Confident", "Formal", "Warm", "Urgent but respectful"];
const STYLES = ["Concise", "Storytelling", "Benefit-led", "Data-led", "Conversational"];
const LANGUAGES = ["English", "Hindi", "Marathi", "Tamil", "Telugu", "Bengali", "Gujarati", "Kannada"];
const CONTENT_TYPES = ["Announcement", "Offer", "Invitation", "Reminder", "Newsletter", "Follow-up", "Case study"];
const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
const URL_RE = /^https?:\/\/\S+$/i;

type Form = {
  name: string;
  campaignType: string;
  objective: string;
  description: string;
  startDate: string;
  endDate: string;
  productName: string;
  productDescription: string;
  mainBenefits: string;
  offer: string;
  discount: string;
  pricingInfo: string;
  sellingPoints: string;
  callToAction: string;
  landingPageUrl: string;
  industries: string[];
  companySize: string;
  locations: string[];
  customerTypes: string[];
  leadSources: string[];
  productInterest: string;
  minDealValue: string;
  maxDealValue: string;
  purchaseTimeline: string;
  customerSegment: string;
  customerStatus: "ANY" | "EXISTING_CUSTOMERS" | "NEW_LEADS";
  otherConditions: string;
  contentTypes: string[];
  tone: string;
  language: string;
  writingStyle: string;
  cta: string;
  additionalInstructions: string;
  senderName: string;
  senderEmail: string;
  replyToEmail: string;
  personalization: boolean;
  criteria: Criterion[];
  ruleMatch: "ALL" | "ANY";
};

const emptyForm = (): Form => ({
  name: "",
  campaignType: "PRODUCT_LAUNCH",
  objective: "",
  description: "",
  startDate: "",
  endDate: "",
  productName: "",
  productDescription: "",
  mainBenefits: "",
  offer: "",
  discount: "",
  pricingInfo: "",
  sellingPoints: "",
  callToAction: "",
  landingPageUrl: "",
  industries: [],
  companySize: "",
  locations: [],
  customerTypes: [],
  leadSources: [],
  productInterest: "",
  minDealValue: "",
  maxDealValue: "",
  purchaseTimeline: "",
  customerSegment: "",
  customerStatus: "ANY",
  otherConditions: "",
  contentTypes: [],
  tone: "Professional",
  language: "English",
  writingStyle: "Concise",
  cta: "",
  additionalInstructions: "",
  senderName: "",
  senderEmail: "",
  replyToEmail: "",
  personalization: true,
  criteria: [],
  ruleMatch: "ALL",
});

const s = (v: string | null | undefined) => v ?? "";

function fromDetail(d: CampaignDetail): Form {
  return {
    name: d.name,
    campaignType: d.campaignType,
    objective: s(d.objective),
    description: s(d.description),
    startDate: s(d.startDate),
    endDate: s(d.endDate),
    productName: s(d.productName),
    productDescription: s(d.productDescription),
    mainBenefits: s(d.mainBenefits),
    offer: s(d.offer),
    discount: s(d.discount),
    pricingInfo: s(d.pricingInfo),
    sellingPoints: s(d.sellingPoints),
    callToAction: s(d.callToAction),
    landingPageUrl: s(d.landingPageUrl),
    industries: d.audience.industries ?? [],
    companySize: s(d.audience.companySize),
    locations: d.audience.locations ?? [],
    customerTypes: d.audience.customerTypes ?? [],
    leadSources: d.audience.leadSources ?? [],
    productInterest: s(d.audience.productInterest),
    minDealValue: d.audience.minDealValue == null ? "" : String(d.audience.minDealValue),
    maxDealValue: d.audience.maxDealValue == null ? "" : String(d.audience.maxDealValue),
    purchaseTimeline: s(d.audience.purchaseTimeline),
    customerSegment: s(d.audience.customerSegment),
    customerStatus: d.audience.customerStatus ?? "ANY",
    otherConditions: s(d.audience.otherConditions),
    contentTypes: d.contentConfig.contentTypes ?? [],
    tone: s(d.contentConfig.tone) || "Professional",
    language: s(d.contentConfig.language) || "English",
    writingStyle: s(d.contentConfig.writingStyle),
    cta: s(d.contentConfig.cta),
    additionalInstructions: s(d.contentConfig.additionalInstructions),
    senderName: s(d.contentConfig.senderName),
    senderEmail: s(d.contentConfig.senderEmail),
    replyToEmail: s(d.contentConfig.replyToEmail),
    personalization: d.contentConfig.personalization ?? true,
    criteria: d.criteria,
    ruleMatch: d.ruleMatch,
  };
}

const n = (v: string) => (v.trim() === "" ? null : v.trim());
const num = (v: string) => (v.trim() === "" ? null : Number(v.replace(/[,₹\s]/g, "")));

function toRequest(f: Form): CampaignRequest {
  return {
    name: f.name.trim(),
    campaignType: f.campaignType,
    objective: n(f.objective),
    description: n(f.description),
    startDate: n(f.startDate),
    endDate: n(f.endDate),
    productName: n(f.productName),
    productDescription: n(f.productDescription),
    mainBenefits: n(f.mainBenefits),
    offer: n(f.offer),
    discount: n(f.discount),
    pricingInfo: n(f.pricingInfo),
    sellingPoints: n(f.sellingPoints),
    callToAction: n(f.callToAction),
    landingPageUrl: n(f.landingPageUrl),
    audience: {
      industries: f.industries,
      companySize: n(f.companySize),
      locations: f.locations,
      customerTypes: f.customerTypes,
      leadSources: f.leadSources,
      productInterest: n(f.productInterest),
      minDealValue: num(f.minDealValue),
      maxDealValue: num(f.maxDealValue),
      purchaseTimeline: n(f.purchaseTimeline),
      customerSegment: n(f.customerSegment),
      customerStatus: f.customerStatus,
      otherConditions: n(f.otherConditions),
    },
    contentConfig: {
      channels: ["EMAIL"],
      contentTypes: f.contentTypes,
      tone: n(f.tone),
      language: n(f.language),
      writingStyle: n(f.writingStyle),
      cta: n(f.cta),
      additionalInstructions: n(f.additionalInstructions),
      senderName: n(f.senderName),
      senderEmail: n(f.senderEmail),
      replyToEmail: n(f.replyToEmail),
      personalization: f.personalization,
    },
    criteria: f.criteria.map((c) => ({ ...c, value: c.value.trim() })),
    ruleMatch: f.ruleMatch,
  };
}

type Errors = Partial<Record<keyof Form | `rule${number}`, string>>;

/** Mirrors the server checks so problems surface in the form, not as a toast after submitting. */
function validate(f: Form, forSubmit: boolean, fields: FieldMeta[]): Errors {
  const e: Errors = {};
  if (!f.name.trim()) e.name = "Give the campaign a name";
  if (f.startDate && f.endDate && f.endDate < f.startDate) e.endDate = "End date must be on or after the start date";
  if (f.minDealValue && isNaN(num(f.minDealValue)!)) e.minDealValue = "Enter a number";
  if (f.maxDealValue && isNaN(num(f.maxDealValue)!)) e.maxDealValue = "Enter a number";
  if (!e.minDealValue && !e.maxDealValue && f.minDealValue && f.maxDealValue && num(f.maxDealValue)! < num(f.minDealValue)!)
    e.maxDealValue = "Maximum is below the minimum";
  if (f.landingPageUrl.trim() && !URL_RE.test(f.landingPageUrl.trim())) e.landingPageUrl = "Enter a full link starting with https://";
  if (f.senderEmail.trim() && !EMAIL_RE.test(f.senderEmail.trim())) e.senderEmail = "Enter a valid email address";
  if (f.replyToEmail.trim() && !EMAIL_RE.test(f.replyToEmail.trim())) e.replyToEmail = "Enter a valid email address";
  f.criteria.forEach((c, i) => {
    const meta = fields.find((m) => m.key === c.field);
    if (!c.value.trim()) e[`rule${i}`] = "Enter a value";
    else if (meta?.kind === "NUMBER" && isNaN(Number(c.value.replace(/[,₹\s]/g, "")))) e[`rule${i}`] = "Enter a number";
  });
  if (forSubmit) {
    if (!f.objective.trim()) e.objective = "Required before AI processing";
    if (!f.productName.trim() && !f.description.trim()) e.productName = "Add a product name or a campaign description";
    if (!f.startDate) e.startDate = "Required before AI processing";
    if (!f.endDate) e.endDate = e.endDate ?? "Required before AI processing";
    else if (f.endDate < new Date().toISOString().slice(0, 10)) e.endDate = "End date is in the past";
  }
  return e;
}

const STEP_FIELDS: Record<StepId, string[]> = {
  basics: ["name", "campaignType", "objective", "description", "startDate", "endDate"],
  product: ["productName", "productDescription", "mainBenefits", "offer", "discount", "pricingInfo", "sellingPoints", "callToAction", "landingPageUrl"],
  audience: ["minDealValue", "maxDealValue"],
  content: ["senderName", "senderEmail", "replyToEmail"],
  rules: [],
};
const stepHasError = (step: StepId, errors: Errors) =>
  Object.keys(errors).some((k) => STEP_FIELDS[step].includes(k) || (step === "rules" && k.startsWith("rule")));

export function CampaignForm({ nav, editId }: { nav: Nav; editId?: string }) {
  const meta = useMarketingMeta();
  const existing = useCampaign(editId ?? null);
  const create = useCreateCampaign();
  const update = useUpdateCampaign();
  const command = useCampaignCommand();
  const [form, setForm] = useState<Form>(emptyForm);
  const [loaded, setLoaded] = useState(!editId);
  const [step, setStep] = useState<StepId>("basics");
  const [touchedSubmit, setTouchedSubmit] = useState<null | boolean>(null);

  useEffect(() => {
    if (editId && existing.data && !loaded) {
      setForm(fromDetail(existing.data));
      setLoaded(true);
    }
  }, [editId, existing.data, loaded]);

  const ruleFields = meta.data?.ruleFields ?? [];
  const errors = useMemo(
    () => (touchedSubmit === null ? {} : validate(form, touchedSubmit, ruleFields)),
    [form, touchedSubmit, ruleFields]
  );
  const set = <K extends keyof Form>(key: K) => (value: Form[K]) => setForm((f) => ({ ...f, [key]: value }));
  const input = (key: keyof Form) => ({
    id: `mk-${key}`,
    value: form[key] as string,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
      setForm((f) => ({ ...f, [key]: e.target.value })),
    "aria-invalid": errors[key] ? true : undefined,
  });

  if (editId && (existing.isLoading || !loaded)) return <Skeleton rows={10} height={20} />;
  if (editId && existing.error) return <ErrorState error={existing.error} onRetry={() => existing.refetch()} />;

  const detail = existing.data;
  const isDraft = !detail || detail.status === "DRAFT";
  const locked = detail && !["DRAFT", "PENDING_LEAD_APPROVAL", "PENDING_CONTENT_APPROVAL", "READY_TO_LAUNCH"].includes(detail.status);
  const busy = create.isPending || update.isPending || command.isPending;
  const stepIndex = STEPS.findIndex((x) => x.id === step);

  const save = (submit: boolean) => {
    const errs = validate(form, submit, ruleFields);
    setTouchedSubmit(submit);
    if (Object.keys(errs).length) {
      const first = STEPS.find((st) => stepHasError(st.id, errs));
      if (first) setStep(first.id);
      toast.error("Check the highlighted fields", { description: Object.values(errs)[0] });
      return;
    }
    const body = toRequest(form);
    const onError = (e: unknown) => toast.error(submit ? "Couldn't start AI processing" : "Couldn't save", { description: e instanceof Error ? e.message : undefined });
    if (!editId) {
      create.mutate(
        { body, submit },
        {
          onSuccess: (c) => {
            toast.success(submit ? "Campaign created — AI processing started" : "Draft saved", {
              description: submit ? "Leads are being scored and content drafted in parallel." : "You can finish it later from the Campaigns tab.",
            });
            nav.openCampaign(c.id);
          },
          onError,
        }
      );
      return;
    }
    update.mutate(
      { id: editId, body },
      {
        onSuccess: () => {
          if (submit && isDraft) {
            command.mutate(
              { id: editId, command: "submit" },
              {
                onSuccess: () => {
                  toast.success("AI processing started");
                  nav.openCampaign(editId);
                },
                onError,
              }
            );
          } else {
            toast.success("Changes saved", {
              description: isDraft ? undefined : "AI results reflect the previous inputs. Re-run lead evaluation or regenerate content if the change matters.",
            });
            nav.openCampaign(editId);
          }
        },
        onError,
      }
    );
  };

  return (
    <div>
      <div className="mk-header">
        <div>
          <div className="mk-eyebrow">
            <button type="button" className="mk-link" style={{ fontSize: 11, color: "inherit", fontWeight: 600 }} onClick={() => (editId ? nav.openCampaign(editId) : nav.home())}>
              <ArrowLeft size={12} aria-hidden style={{ verticalAlign: -2 }} /> {editId ? "Back to campaign" : "Marketing Automation"}
            </button>
          </div>
          <h2 className="mk-title">{editId ? `Edit ${detail?.name}` : "Create campaign"}</h2>
          <p className="mk-subtitle">
            Everything here is entered by you. The AI uses only these inputs: it scores each lead against your audience and rules, and writes
            the campaign email from your product and offer details. No email is sent until you approve the leads, the email and the campaign.
          </p>
        </div>
      </div>

      {locked && <Callout tone="warn" title="This campaign can no longer be edited">Duplicate it to reuse the inputs.</Callout>}

      <div className="mk-form-layout" style={{ marginTop: locked ? 16 : 0 }}>
        <nav className="mk-steps" aria-label="Form sections">
          {STEPS.map((st, i) => {
            const bad = stepHasError(st.id, errors);
            return (
              <button
                key={st.id}
                type="button"
                className={`mk-step ${bad ? "mk-step--error" : i < stepIndex ? "mk-step--done" : ""}`}
                aria-current={st.id === step ? "step" : undefined}
                onClick={() => setStep(st.id)}
              >
                <span className="mk-step__num">{bad ? "!" : i < stepIndex ? <Check size={12} aria-hidden /> : String.fromCharCode(65 + i)}</span>
                {st.label}
              </button>
            );
          })}
          <div className="mk-callout mk-callout--info" style={{ marginTop: 12, fontSize: 11.5, whiteSpace: "normal" }}>
            <ShieldCheck size={14} aria-hidden />
            <span>Campaign details are sent only to the CRM's {meta.data?.ai.selfHosted ? "self-hosted" : "configured"} AI model, and never with lead names or contact details.</span>
          </div>
        </nav>

        <Panel>
          {step === "basics" && (
            <div className="mk-fields">
              <Field label="Campaign name" required error={errors.name} htmlFor="mk-name" wide>
                <input className="mk-input" maxLength={160} placeholder="e.g. Q4 FlowSuite launch for manufacturers" {...input("name")} />
              </Field>
              <Field label="Campaign type" required htmlFor="mk-campaignType">
                <select className="mk-select" {...input("campaignType")}>
                  {(meta.data?.campaignTypes ?? ["PRODUCT_LAUNCH"]).map((t) => (
                    <option key={t} value={t}>{campaignTypeLabel(t)}</option>
                  ))}
                </select>
              </Field>
              <Field label="Campaign status" help="Set by the workflow: drafts move to AI processing, then to your review.">
                <div style={{ height: 32, display: "flex", alignItems: "center" }}>
                  <StatusBadge status={detail?.status ?? "DRAFT"} />
                </div>
              </Field>
              <Field label="Campaign objective" required error={errors.objective} htmlFor="mk-objective" wide help="What success looks like. Guides both lead scoring and content.">
                <input className="mk-input" maxLength={300} placeholder="e.g. Book product demos with mid-size manufacturers" {...input("objective")} />
              </Field>
              <Field label="Campaign description" htmlFor="mk-description" wide>
                <textarea className="mk-textarea" rows={4} maxLength={5000} {...input("description")} />
              </Field>
              <Field label="Start date" required error={errors.startDate} htmlFor="mk-startDate">
                <input type="date" className="mk-input" {...input("startDate")} />
              </Field>
              <Field label="End date" required error={errors.endDate} htmlFor="mk-endDate">
                <input type="date" className="mk-input" min={form.startDate || undefined} {...input("endDate")} />
              </Field>
            </div>
          )}

          {step === "product" && (
            <div className="mk-stack">
              <Callout tone="ai" title="The AI won't add facts">
                Content is checked automatically: any price, discount, figure, link or claim that isn't written here is rejected. Leave a field
                empty rather than approximate it.
              </Callout>
              <div className="mk-fields">
                <Field label="Product name" error={errors.productName} htmlFor="mk-productName">
                  <input className="mk-input" maxLength={200} {...input("productName")} />
                </Field>
                <Field label="Call-to-action" htmlFor="mk-callToAction" help="e.g. Book a demo">
                  <input className="mk-input" maxLength={200} {...input("callToAction")} />
                </Field>
                <Field label="Landing page URL" error={errors.landingPageUrl} htmlFor="mk-landingPageUrl" wide help="Where the email's call-to-action button links">
                  <input className="mk-input" type="url" maxLength={500} placeholder="https://" {...input("landingPageUrl")} />
                </Field>
                <Field label="Product description" htmlFor="mk-productDescription" wide>
                  <textarea className="mk-textarea" rows={3} maxLength={5000} {...input("productDescription")} />
                </Field>
                <Field label="Key benefits" htmlFor="mk-mainBenefits" wide help="One per line">
                  <textarea className="mk-textarea" rows={3} maxLength={3000} {...input("mainBenefits")} />
                </Field>
                <Field label="Key selling points" htmlFor="mk-sellingPoints" wide help="One per line">
                  <textarea className="mk-textarea" rows={3} maxLength={3000} {...input("sellingPoints")} />
                </Field>
                <Field label="Offer or promotion" htmlFor="mk-offer">
                  <input className="mk-input" maxLength={2000} placeholder="e.g. Early access for the first 50 customers" {...input("offer")} />
                </Field>
                <Field label="Discount" htmlFor="mk-discount">
                  <input className="mk-input" maxLength={120} placeholder="e.g. 15% off annual plans" {...input("discount")} />
                </Field>
                <Field label="Pricing information" htmlFor="mk-pricingInfo" wide help="Only if the email may state it">
                  <textarea className="mk-textarea" rows={2} maxLength={2000} {...input("pricingInfo")} />
                </Field>
              </div>
            </div>
          )}

          {step === "audience" && (
            <div className="mk-stack">
              <p className="mk-sub" style={{ margin: 0 }}>
                Describe who the campaign is for. The AI weighs this when scoring each lead. Anything that must hold for every lead belongs in
                <strong> Eligibility rules</strong>.
              </p>
              <div className="mk-fields">
                <Field label="Industries" htmlFor="mk-industries" help="Press Enter after each">
                  <TokenInput id="mk-industries" value={form.industries} onChange={set("industries")} placeholder="e.g. Manufacturing" />
                </Field>
                <Field label="Company size" htmlFor="mk-companySize" help="e.g. 100–1,000 employees">
                  <input className="mk-input" maxLength={60} {...input("companySize")} />
                </Field>
                <Field label="Locations" htmlFor="mk-locations">
                  <TokenInput id="mk-locations" value={form.locations} onChange={set("locations")} placeholder="e.g. Pune" />
                </Field>
                <Field label="Lead sources" htmlFor="mk-leadSources">
                  <TokenInput id="mk-leadSources" value={form.leadSources} onChange={set("leadSources")} placeholder="e.g. Website" />
                </Field>
                <Field label="Customer type" wide>
                  <ChipGroup
                    label="Customer type"
                    value={form.customerTypes}
                    onChange={set("customerTypes")}
                    options={(meta.data?.customerTypes ?? []).map((t) => ({ value: t, label: t }))}
                  />
                </Field>
                <Field label="Existing customer or new lead">
                  <Segmented
                    label="Existing customer or new lead"
                    value={form.customerStatus}
                    onChange={set("customerStatus")}
                    options={[
                      { value: "ANY", label: "Either" },
                      { value: "EXISTING_CUSTOMERS", label: "Existing customers" },
                      { value: "NEW_LEADS", label: "New leads" },
                    ]}
                  />
                </Field>
                <Field label="Product interest" htmlFor="mk-productInterest">
                  <input className="mk-input" maxLength={200} {...input("productInterest")} />
                </Field>
                <Field label="Estimated deal value from (₹)" error={errors.minDealValue} htmlFor="mk-minDealValue">
                  <input className="mk-input" inputMode="numeric" {...input("minDealValue")} />
                </Field>
                <Field label="Estimated deal value up to (₹)" error={errors.maxDealValue} htmlFor="mk-maxDealValue">
                  <input className="mk-input" inputMode="numeric" {...input("maxDealValue")} />
                </Field>
                <Field label="Purchase timeline" htmlFor="mk-purchaseTimeline">
                  <select className="mk-select" {...input("purchaseTimeline")}>
                    <option value="">Any</option>
                    {(meta.data?.purchaseTimelines ?? []).map((t) => (
                      <option key={t} value={t}>{t}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Customer segment" htmlFor="mk-customerSegment">
                  <input className="mk-input" maxLength={200} placeholder="e.g. Mid-market operations leaders" {...input("customerSegment")} />
                </Field>
                <Field label="Other conditions" htmlFor="mk-otherConditions" wide>
                  <textarea className="mk-textarea" rows={2} maxLength={1000} {...input("otherConditions")} />
                </Field>
              </div>
            </div>
          )}

          {step === "content" && (
            <div className="mk-fields">
              {meta.data && !meta.data.email.configured && (
                <div style={{ gridColumn: "1 / -1" }}>
                  <Callout tone="warn" title="Email sending isn't configured yet">
                    You can prepare and approve this campaign, but it can't be launched until an administrator sets up the email provider.
                  </Callout>
                </div>
              )}
              <Field label="Sender name" error={errors.senderName} htmlFor="mk-senderName" help="Shown as the From name, e.g. Asha at Acme">
                <input className="mk-input" maxLength={120} {...input("senderName")} />
              </Field>
              <Field
                label="Verified sender email"
                error={errors.senderEmail}
                htmlFor="mk-senderEmail"
                help={
                  meta.data?.email.verifiedSenders.length
                    ? `Must be verified with the email provider: ${meta.data.email.verifiedSenders.join(", ")}`
                    : "Must be an address verified with your email provider"
                }
              >
                <input className="mk-input" type="email" maxLength={254} placeholder={meta.data?.email.verifiedSenders[0] ?? ""} {...input("senderEmail")} />
              </Field>
              <Field label="Reply-to email" error={errors.replyToEmail} htmlFor="mk-replyToEmail" help="Where replies go (optional)">
                <input className="mk-input" type="email" maxLength={254} {...input("replyToEmail")} />
              </Field>
              <Field label="Email personalization" help="Merge fields such as {{first_name}} and {{company_name}}, filled per lead">
                <Segmented
                  label="Email personalization"
                  value={form.personalization ? "on" : "off"}
                  onChange={(v) => setForm((f) => ({ ...f, personalization: v === "on" }))}
                  options={[{ value: "on", label: "Personalize" }, { value: "off", label: "Same for everyone" }]}
                />
              </Field>
              <Field label="Email type" wide>
                <ChipGroup label="Content type" value={form.contentTypes} onChange={set("contentTypes")} options={CONTENT_TYPES.map((t) => ({ value: t, label: t }))} />
              </Field>
              <Field label="Tone" htmlFor="mk-tone">
                <select className="mk-select" {...input("tone")}>
                  {TONES.map((t) => <option key={t}>{t}</option>)}
                </select>
              </Field>
              <Field label="Language" htmlFor="mk-language">
                <select className="mk-select" {...input("language")}>
                  {LANGUAGES.map((t) => <option key={t}>{t}</option>)}
                </select>
              </Field>
              <Field label="Writing style" htmlFor="mk-writingStyle">
                <select className="mk-select" {...input("writingStyle")}>
                  <option value="">No preference</option>
                  {STYLES.map((t) => <option key={t}>{t}</option>)}
                </select>
              </Field>
              <Field label="CTA wording" htmlFor="mk-cta" help="Overrides the product call-to-action in the email">
                <input className="mk-input" maxLength={120} {...input("cta")} />
              </Field>
              <Field label="Additional instructions" htmlFor="mk-additionalInstructions" wide help="Style guidance for the AI. It can shape wording but never adds facts.">
                <textarea className="mk-textarea" rows={3} maxLength={1500} {...input("additionalInstructions")} />
              </Field>
            </div>
          )}

          {step === "rules" && (
            <RulesEditor
              fields={ruleFields}
              criteria={form.criteria}
              match={form.ruleMatch}
              errors={errors}
              onChange={set("criteria")}
              onMatch={set("ruleMatch")}
            />
          )}

          <div className="mk-formbar">
            <Btn icon={ArrowLeft} disabled={stepIndex === 0} onClick={() => setStep(STEPS[stepIndex - 1].id)}>Back</Btn>
            {stepIndex < STEPS.length - 1 && (
              <Btn onClick={() => setStep(STEPS[stepIndex + 1].id)}>
                Next: {STEPS[stepIndex + 1].label} <ArrowRight size={14} aria-hidden />
              </Btn>
            )}
            <span className="mk-spacer" />
            <Btn variant="ghost" onClick={() => (editId ? nav.openCampaign(editId) : nav.home())}>Cancel</Btn>
            {isDraft ? (
              <>
                <Btn icon={Save} disabled={busy || !!locked} busy={busy && touchedSubmit === false} onClick={() => save(false)}>Save draft</Btn>
                <Btn variant="primary" icon={Sparkles} disabled={busy} busy={busy && touchedSubmit === true} onClick={() => save(true)}>
                  {editId ? "Save & start AI processing" : "Create campaign"}
                </Btn>
              </>
            ) : (
              <Btn variant="primary" icon={Save} disabled={busy || !!locked} busy={busy} onClick={() => save(false)}>Save changes</Btn>
            )}
          </div>
        </Panel>
      </div>
    </div>
  );
}

/* ---------------- Section E: rule builder ---------------- */
function RulesEditor({
  fields,
  criteria,
  match,
  errors,
  onChange,
  onMatch,
}: {
  fields: FieldMeta[];
  criteria: Criterion[];
  match: "ALL" | "ANY";
  errors: Errors;
  onChange: (c: Criterion[]) => void;
  onMatch: (m: "ALL" | "ANY") => void;
}) {
  const add = () => {
    const f = fields[0];
    if (!f) return;
    onChange([...criteria, { field: f.key, operator: f.operators[0], value: "" }]);
  };
  const patch = (i: number, p: Partial<Criterion>) => onChange(criteria.map((c, j) => (j === i ? { ...c, ...p } : c)));
  return (
    <div className="mk-stack">
      <div>
        <p className="mk-sub" style={{ margin: "0 0 10px" }}>
          Hard requirements. The CRM checks each rule against every lead's recorded data and gives the results to the AI. A lead that
          definitively fails a required rule is never recommended, whatever the AI's score.
        </p>
        <div className="mk-row">
          <span className="mk-label">A lead is eligible when it meets</span>
          <Segmented label="Rule matching" value={match} onChange={onMatch} options={[{ value: "ALL", label: "all rules" }, { value: "ANY", label: "any rule" }]} />
        </div>
      </div>
      {criteria.length === 0 ? (
        <div className="mk-callout mk-callout--info">No rules yet. Without rules the AI decides eligibility from the audience description alone, and there is no rules-based fallback if the AI model is unavailable.</div>
      ) : (
        <div className="mk-stack" style={{ gap: 10 }}>
          {criteria.map((c, i) => {
            const meta = fields.find((f) => f.key === c.field);
            const err = errors[`rule${i}`];
            return (
              <div key={i}>
                <div className="mk-rule">
                  <span className="mk-rule__join">{i === 0 ? "IF" : match === "ALL" ? "AND" : "OR"}</span>
                  <select
                    className="mk-select"
                    aria-label={`Rule ${i + 1} field`}
                    value={c.field}
                    onChange={(e) => {
                      const f = fields.find((x) => x.key === e.target.value);
                      patch(i, { field: e.target.value, operator: f?.operators.includes(c.operator) ? c.operator : f?.operators[0] ?? "EQ", value: "" });
                    }}
                  >
                    {fields.map((f) => <option key={f.key} value={f.key}>{f.label}</option>)}
                  </select>
                  <select className="mk-select" aria-label={`Rule ${i + 1} operator`} value={c.operator} onChange={(e) => patch(i, { operator: e.target.value })}>
                    {(meta?.operators ?? []).map((o) => <option key={o} value={o}>{OPERATOR_LABEL[o] ?? o}</option>)}
                  </select>
                  {meta && meta.options.length > 0 && !["IN", "NOT_IN", "CONTAINS"].includes(c.operator) ? (
                    <select className="mk-select" aria-label={`Rule ${i + 1} value`} value={c.value} onChange={(e) => patch(i, { value: e.target.value })} aria-invalid={!!err}>
                      <option value="">Choose…</option>
                      {meta.options.map((o) => <option key={o}>{o}</option>)}
                    </select>
                  ) : (
                    <input
                      className="mk-input"
                      aria-label={`Rule ${i + 1} value`}
                      value={c.value}
                      inputMode={meta?.kind === "NUMBER" ? "numeric" : undefined}
                      placeholder={meta?.kind === "NUMBER" ? "Number" : ["IN", "NOT_IN"].includes(c.operator) ? "Comma-separated" : "Value"}
                      onChange={(e) => patch(i, { value: e.target.value })}
                      aria-invalid={!!err}
                    />
                  )}
                  <Btn variant="ghost" className="mk-icon-btn" aria-label={`Remove rule ${i + 1}`} onClick={() => onChange(criteria.filter((_, j) => j !== i))}>
                    <Trash2 size={14} aria-hidden />
                  </Btn>
                </div>
                {(err || meta?.hint) && (
                  <div className={err ? "mk-error" : "mk-help"} style={{ paddingLeft: 48, marginTop: 3 }}>{err ?? meta?.hint}</div>
                )}
              </div>
            );
          })}
        </div>
      )}
      <div>
        <Btn icon={Plus} onClick={add} disabled={criteria.length >= 20 || fields.length === 0}>Add rule</Btn>
      </div>
    </div>
  );
}
