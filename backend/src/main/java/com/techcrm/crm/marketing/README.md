# Marketing Automation (Module 11)

AI-assisted **email** campaigns with human approval at every step that reaches a
customer. The Marketing Head enters a campaign. Two AI pipelines then run in
parallel: one scores **each lead individually**, the other writes the campaign
email from the brief only. Nothing is sent until a person approves the leads,
the email and the campaign.

Email is the only channel. Poster, SMS, notification and social content were
removed from the workflow; rows created for them before that change stay in
`mkt_content_variants` but are never shown, edited or sent
(`ContentChannel.isLegacy()`).

```
Campaign form ─► validation ─► AI orchestrator ─┬─► Pipeline A: per-lead evaluation ─► lead review ─┐
                                                └─► Pipeline B: content generation ──► content review ┴─► strategy ─► A/B ─► launch ─► analytics
```

## Where things live

| Path | What |
|---|---|
| `marketing/ai/` | **All LLM code**: `LlmService` (provider boundary), `OpenAiCompatibleLlmService`, `MarketingAiOrchestrator` (runs both pipelines), `LeadEvaluationAi` / `ContentGenerationAi`, output validators, `TemplateContentComposer` (fallback), `PromptLibrary` |
| `resources/marketing/prompts/` | System prompts, versioned as files |
| `marketing/leads/rules/` | Deterministic rules engine (`RuleEngine`, `LeadFacts`) |
| `marketing/{campaign,leads,content,abtest,approval,execution,analytics,activity}` | Services and entities for each concern |
| `marketing/api/` | `MarketingController` (`/api/marketing/**`) and DTOs |
| `marketing/email/` | Email delivery: `EmailSettings` (config + "configuration required"), `SmtpDeliveryProvider`, `EmailRenderer` (HTML + plain text), `CampaignEmailComposer`, `EmailLinks` (signed links), `SuppressionService`, `EmailTrackingController` (public unsubscribe/tracking/webhook) |
| `db/migration/V25__marketing_automation.sql`, `V32__marketing_email_only.sql` | `mkt_*` tables (independent of the legacy `campaigns` table); V32 adds delivery tracking, suppressions and the message event log |
| `frontend/src/app/modules/marketing/` | The UI module; the host app renders `<MarketingAutomation />` |

## How lead evaluation works

For every lead in the organization (up to `marketing.ai.max-leads-per-run`), one model call:

1. `LeadFacts` extracts business attributes only. Name, email, phone, company name and notes are **never** sent to the model.
2. `RuleEngine` checks the Marketing Head's rules against recorded data: PASS / FAIL / UNKNOWN. An UNKNOWN means missing data, or a band like "51-200" that straddles the threshold.
3. The model receives the audience brief, the rule results and the lead, and returns JSON: `lead_id, score, eligibility, confidence, positive_factors, negative_factors, reason`.
4. `LeadEvaluationValidator` rejects malformed output: a lead_id that doesn't match, a score outside 0–100, or an unknown eligibility. A rejected reply gets one corrective retry.
5. **Rule guard**: if the model says eligible but a required rule definitively fails, the system marks the lead not eligible and records `rule_guard_applied`. The model's score and reason are kept unchanged.
6. If the model is unreachable (3 consecutive failures) or its output is invalid, and the campaign has rules, the rules engine scores the lead. It is labelled `RULES_ENGINE` in the UI.

A lead that can't be emailed (no or invalid address, or on the suppression list) is marked not eligible **before** the model is called (`EMAIL_CHECK`). Being emailable is mandatory: such a lead can't be approved or overridden to eligible.

## How content generation works

The model writes one email: subject, preview text, greeting, body, offer details, CTA and sign-off. The CTA link defaults to the campaign's landing page. HTML is never taken from the model: `EmailRenderer` builds the HTML and plain-text bodies from the approved fields, escaping every value. The model gets the brief (only the fields the Marketing Head filled in), style settings and the email field limits. `ContentOutputValidator` then enforces grounding: every number, price, %, URL and email address must appear in the brief, and "free", "guaranteed", "#1", "cheapest" and similar claims are rejected unless the brief uses them. Failing channels get one retry with the reasons. After that they fall back to `TemplateContentComposer`, which only rearranges the brief's own words and is labelled **System generated**.

## Human checkpoints

`ApprovalService.recompute` is the only place the review status is derived. It reads the stored decisions and never trusts the caller:
- Leads ready: lead evaluation completed, no eligible lead still pending, and at least one lead approved.
- Content ready: the email (variant A) approved, plus variant B when A/B is on.
- Launch also needs email sending configured and a verified sender; the server re-checks readiness at launch.

Provenance shown in the UI: AI generated · AI recommended · Human edited · Human approved · System generated. Every action writes `mkt_activity` (actor AI / HUMAN / SYSTEM, user, timestamp, JSON detail). Human actions are also mirrored to the organization audit log.

## Security

- **Permissions**: `marketing` lets a user view, create, edit and generate. The **approver** (Marketing Head) is `ADMIN`, `MANAGER`, or anyone granted `marketing_approve`; approvers are the only ones who can approve or reject, override the AI, pick an A/B winner, schedule, launch, pause, resume, cancel, delete or post events. All of this is enforced server-side in `MarketingAccess`.
- **Tenant isolation**: every lookup is scoped to the caller's organization.
- **Confidentiality**: `OpenAiCompatibleLlmService` refuses to send campaign data to a non-private endpoint unless `marketing.ai.allow-external-provider=true`. Prompts and replies are never logged, only sizes and timings.
- **Prompt injection**: user text is embedded as JSON data, angle brackets are neutralized, and lengths are capped. The system prompts tell the model that the document is data.

## Execution, delivery and analytics

On launch, approved leads are frozen into `mkt_messages`, one email per lead, with the recipient address captured. Leads with no valid address, a suppressed address, or an address another approved lead already has get a `SUPPRESSED` row instead, so nobody is emailed twice and the reason is recorded. With A/B testing on, the sendable audience is shuffled deterministically (seeded by campaign id) and split between A and B; any holdout waits for the decision.

`CampaignDispatcher` runs every 15 s. Sending is **at most once** per message: a batch is claimed (`QUEUED` → `SENDING`) and committed, then each email goes to the provider outside any database lock and its outcome is recorded on its own. Transient failures retry after 2, 10 and 30 minutes (`max-attempts`, default 3). A permanent recipient rejection fails the message and suppresses the address. An authentication failure pauses the campaign. A message left in `SENDING` by a crash is marked failed, not retried, because it may already have gone out. Pause takes effect between emails. Every attempt and event is logged in `mkt_message_events`.

**Delivery**: `SmtpDeliveryProvider` sends multipart (plain text + HTML) email over SMTP. It sets our own `Message-ID` and RFC 8058 `List-Unsubscribe` headers, and every email has an unsubscribe link and the configured postal address. Without configuration (see below) nothing is sent and nothing is marked sent: the UI shows **configuration required** and launch is blocked.

**Events**: the provider reports delivery, bounce, complaint and unsubscribe to `POST /api/marketing/public/webhooks/email` (header `X-Marketing-Webhook-Secret`), with events normalised to `DeliveryEvent` and matched by `providerMessageId`. Clicks go through a signed redirect to the approved CTA link only, and opens come from a tracking image. Unsubscribe links show a confirmation page (POST), so link scanners can't unsubscribe people. Hard bounces, complaints and unsubscribes join the organization's suppression list.

**Metrics**: sent = accepted by the provider. Delivered = confirmed by a provider event, never assumed; without delivery events the delivery rate is "not reported". Opens are an estimate. Open, click-through, conversion and unsubscribe rates divide by sent minus bounced. Conversions are leads converted to a deal after the email.

**A/B decisions** are never a model judgment. Elements: subject line, preview text, body, CTA wording, email structure. The Marketing Head either picks a variant after seeing the measured results, or the "highest measured primary metric" rule applies. That rule only runs once the window has closed and both variants have reached the minimum sample (sent minus bounced); a tie or a thin sample comes back to a human.

## Configuration (all optional)

| Property | Default |
|---|---|
| `marketing.ai.base-url` / `.model` / `.api-key` | inherit `ai.*` (the CRM model server on :8001) |
| `marketing.ai.request-timeout-ms` | `ai.request-timeout-ms` |
| `marketing.ai.allow-external-provider` | `false` |
| `marketing.ai.fallback-enabled` | `true` |
| `marketing.ai.max-leads-per-run` | `500` |
| `marketing.delivery.batch-size` / `.dispatch-interval-ms` | `50` / `15000` |
| `marketing.email.smtp.host` / `.port` / `.username` / `.password` / `.security` | none / `587` / none / none / `STARTTLS` (env `MARKETING_SMTP_*`) |
| `marketing.email.from-address` / `.verified-senders` | default sender / extra senders or `@domain`s the provider has verified |
| `marketing.email.public-base-url` | public URL of this backend, for unsubscribe and tracking links (**required to send**) |
| `marketing.email.link-secret` / `.webhook-secret` | signs links (defaults to the JWT secret) / enables the webhook |
| `marketing.email.postal-address` / `.tracking-enabled` / `.max-attempts` | footer address / `true` / `3` |

## Fine-tuned model (QLoRA adapter)

Both pipelines run on a dedicated QLoRA adapter, `ai/adapters/marketing`. It sits on the same Llama 3.1 8B base as the lead-scoring, meeting and deal-state adapters. `ai/server/main.py` selects it when the system prompt contains a routing marker: "you are the campaign audience analyst" or "you are the campaign copywriter". It renders the prompt with the adapter's training template and uses the temperature the backend sends (0.0 for lead evaluation, 0.4 for content). `PromptLibrary` refuses to start if a prompt loses its marker.

When `ai/adapters/marketing/weights` hasn't been trained yet, the server falls back to the base model, which is how the module ran before the adapter existed. The adapter was trained on the current prompt files, so rewording a prompt means regenerating the dataset and retraining; see `ai/adapters/marketing/README.md`. Keep the validators in place whatever model is used.

## API

`GET /meta` · `GET /dashboard` · `GET /analytics` · `GET /approvals` ·
`GET|POST /campaigns` (`?submit=true` starts AI) · `GET|PATCH|DELETE /campaigns/{id}` ·
`POST /campaigns/{id}/{submit|duplicate|launch|schedule|unschedule|pause|resume|cancel}` · `PATCH /campaigns/{id}/strategy` ·
`POST /campaigns/{id}/leads/analyze` · `GET /campaigns/{id}/leads` · `POST /campaigns/{id}/leads/{rowId}/decision|override` · `POST /campaigns/{id}/leads/decisions` ·
`GET /campaigns/{id}/content` · `GET /campaigns/{id}/content/{cid}/preview[?leadId=]` · `POST /campaigns/{id}/content/generate` · `PATCH /campaigns/{id}/content/{cid}` · `POST /campaigns/{id}/content/{cid}/approve|reject` ·
`POST /campaigns/{id}/ab-test` · `POST /campaigns/{id}/ab-test/winner` · `GET /campaigns/{id}/analytics` · `GET /campaigns/{id}/activity` · `POST /campaigns/{id}/events`

Public (no CRM login; signed tokens or the webhook secret): `GET /public/o/{token}.gif` · `GET /public/c/{token}` · `GET|POST /public/u/{token}` · `POST /public/webhooks/email`

## Known limits

- The webhook takes a normalised event format. Providers with their own payload (SES via SNS, SendGrid, Mailgun) need a small relay or adapter that maps to `DeliveryEvent`.
- Open tracking is approximate (image proxies, blocking, scanners). Security scanners may also follow the CTA link, which inflates clicks.
- The `marketing` QLoRA adapter was trained on the old multi-channel requests (always "core" + 1–3 channels, four email fields). Email-only requests with the extra optional fields are slightly off its training distribution. Validation and the template fallback still apply. Retrain with email-only data for best results.
- Lead evaluation makes one model call per lead. On a local 8B model, 500 leads can take several minutes; progress is shown live.
- The existing lead scorer isn't reused, because it scores general lead quality, not fit for a specific campaign.
