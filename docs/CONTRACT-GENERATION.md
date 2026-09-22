# Contract generation

How a won proposal becomes a contract in the customer's inbox, and the API the
SAP Build Process Automation bot drives it with.

```
Sales executive moves the deal to Proposal / Negotiation / Closed Won
  -> SAP BPA bot: POST /api/contracts/generate  { "dealId": 31 }
       -> validate the opportunity
       -> fetch account, contact, owner, line items
       -> resolve the template
       -> docx4j renders the DOCX
       -> LibreOffice headless converts it to PDF
       -> both stored, contract row written
  <- 202 { contractId, status: "DRAFTING", ... }
  -> SAP BPA bot: GET /api/contracts/{contractId}/status   until GENERATED
  -> SAP BPA bot: POST /api/contracts/{contractId}/send-email
       -> the backend emails the customer via Mailjet, PDF attached
       -> contract and deal contract status become SENT, audit entry
  <- { status: "SENT", sentTo, mailjetMessageId, attachmentBytes }
  -> the customer replies by email to accept or reject
```

There is no separate "contract" step in the pipeline board: `deals.stage` is
untouched by any of this. Contract progress lives on `deals.contract_status`,
which is a second axis — a deal can be in Negotiation with a contract already
with the customer. Customer onboarding opens when the sales executive moves the
deal to Closed Won.

---

## What this reuses

Nothing about the CRM's core model changed.

| Concern | Existing thing used |
|---|---|
| Opportunity | `deals` — `deals.id` is the `dealId` the bot sends |
| Customer | `accounts`, via `deals.account_id` |
| Signer | `contacts`, primary first (`ContactRepository.findForAccount`) |
| Sales executive | `users`, via `deals.owner_id` |
| Products | `leads.product` / `leads.product_quantity`, via `deals.lead_id` |
| Authentication | the existing JWT filter and `AuthenticatedUser` principal |
| Errors | `ResponseStatusException` -> `ApiExceptionHandler` -> `{"error": "..."}` |
| Audit | `AuditLogService.record(...)` |
| Customer activation | `CustomerOnboardingService.initiate(...)` |

### `dealId` is `deals.id`

Not `opportunityId`. The CRM has both and they are not interchangeable:

| Field | Example | What it is |
|---|---|---|
| `deals.id` | `31` | The primary key. **This is `dealId`.** |
| `deals.opportunity_id` | `OPP-000031` | A display reference derived from the id |
| `deals.account_id` | `9` | The customer — a different table entirely |

`GET /api/deals` returns the primary key as `id` (serialised as a string, like
every id in this API); `POST /leads/{id}/convert` returns it as `dealId`. Either
can be posted straight back — `{"dealId": 31}` and `{"dealId": "31"}` are both
accepted.

### Line items, and why there is no product catalogue

This CRM has no product entity. The only product data anywhere is on the lead an
opportunity was converted from. So the contract schedule is resolved at
generation time and then **frozen** into `contract_line_items`:

| Case | Result | `source` |
|---|---|---|
| Deal came from a lead that names a product | one line: product, quantity, `deal.value / quantity`, line total | `LEAD_PRODUCT` |
| No lead, or the lead names no product | one line: the deal's own name, quantity 1, `deal.value` | `DEAL_VALUE` |

The unit price is rounded and the line total recomputed from it, so the printed
columns multiply out. On an awkward quantity that can leave the contract a few
paise under the deal value; a document whose own columns do not sum is worse.

Frozen rather than re-derived, because otherwise a later edit to the originating
lead would silently rewrite a contract the customer has already signed.

---

## Endpoints

All under `/api/contracts`. Every one requires the usual
`Authorization: Bearer <access token>` and is scoped to the caller's
organization.

### `POST /generate`

```json
{
  "dealId": 31,
  "contractType": "STANDARD_SALES_AGREEMENT",
  "contactId": 12,
  "startDate": "2026-09-01",
  "endDate": "2027-09-01",
  "paymentTerms": "Net 45",
  "deliveryTimeline": "Ships within 10 working days",
  "regenerate": false
}
```

Only `dealId` is required; everything else overrides a default.

**Generation is asynchronous.** A cold render — docx4j plus a LibreOffice
subprocess — takes longer than the **30 seconds SAP Build Process Automation
waits before abandoning a call**, so the endpoint reserves the contract and
returns immediately:

```
POST /generate
   ↓  ~1 second
202 Accepted   { "contractId": "42", "status": "DRAFTING", "pdfUrl": null, ... }
   ↓
   │   background: DOCX → PDF → store → status = GENERATED
   ↓
GET /{contractId}/status   → poll until GENERATED or FAILED
```

| Code | Meaning |
|---|---|
| **202** | Accepted. `DRAFTING`, document URLs null. Poll the status endpoint. **The default.** |
| **201** | Created and finished inline. Only when `contract.async-generation=false`. |
| **200** | A live contract already existed; a retry was absorbed. |

The body is identical in all three, so a caller that ignores the status code
still gets a usable answer:

```json
{
  "contractId": "42",
  "contractNumber": "CTR-000042",
  "dealId": "31",
  "opportunityId": "OPP-000031",
  "contractType": "STANDARD_SALES_AGREEMENT",
  "status": "DRAFTING",
  "pdfUrl": null,
  "docxUrl": null
}
```

`pdfUrl` is `null` while a render is in flight, and also when PDF conversion is
turned off — so a caller can tell "not produced" from "here it is" without
probing the URL.

**Validation stays synchronous.** Only the slow half moved. An unknown deal is
still a 404 on this call, a wrong stage still a 409, missing customer data still
a 400. Nothing gets a 202 that was never going to produce a contract.

**A render failure lands on the contract, not the response.** Asynchronously
there is no request left to fail, so the row goes to `FAILED` with the reason in
`failureReason`, which `GET /{contractId}` returns. Synchronously it is a 500 on
the call that caused it.

| Status | When |
|---|---|
| 400 | No billing address, no contact with an email, no owner, zero deal value, end date not after start, unknown contract type |
| 404 | No such deal in this organization |
| 409 | Deal is in an ineligible stage, or its contract is already signed and `regenerate` was asked for |
| 500 | DOCX generation, PDF conversion or storage failed — the reason is recorded on the contract row, not returned. **Synchronous mode only**; asynchronously the failure lands on the contract's status |

**Idempotency.** A retry returns the deal's existing live contract rather than
producing a second one. "Live" means `GENERATED`, `SENT`, `VIEWED` or `SIGNED`,
and is enforced by the `uq_contracts_live_per_deal` partial index as well as by
the check — so two simultaneous retries cannot both win. `FAILED`, `REJECTED`
and `SUPERSEDED` release the slot, so a rep can fix the data and try again.

### `GET /{contractId}`

The whole record: customer and contact details, sales executive, template used,
status, line items, document URLs, and signing information when there is any.

### `GET /{contractId}/status`

The small shape, for polling.

```json
{
  "contractId": "42", "dealId": "31", "status": "SENT",
  "sentAt": "...", "signedAt": null, "rejectedAt": null, "updatedAt": "..."
}
```

`DRAFTING` -> `GENERATED` -> `SENT` (emailed), plus `FAILED` (generation broke)
and `SUPERSEDED` (replaced by a regeneration). `VIEWED`, `SIGNED` and `REJECTED`
were set by a former e-signature integration; nothing sets them now, but
contracts from that time still carry them, with `signedAt` / `rejectedAt`.

### `GET /{contractId}/document.pdf` · `GET /{contractId}/document.docx`

Served through the API, not from a static path, so the same token and
organization scoping that guards everything else guards the documents.
`Content-Disposition: attachment; filename="CTR-000042.pdf"`, `Cache-Control:
no-store`. **404** when the document was never produced or has gone missing from
the store.

### `POST /{contractId}/send-email`

Emails the customer the contract PDF **attached**, asking them to review it and
reply. Call it once the contract is `GENERATED`. On success the contract and the
deal's `contract_status` become `SENT` and `sentAt` is recorded.

```json
{ "recipientEmail": "optional@override.com", "recipientName": "Optional", "resend": false }
```

The body is optional. With no body the email goes to the contract's contact.

```json
{ "contractId": "5", "contractNumber": "CTR-000005", "status": "SENT",
  "sentTo": "meera@company.com", "mailjetMessageId": "1ab23cd4-...",
  "attachmentBytes": 82637, "sentAt": "2026-09-17T11:30:00Z" }
```

| Status | When |
|---|---|
| 200 | `SENT`, or `ALREADY_SENT` — a retry found it already emailed, and nothing was sent twice |
| 400 | No recipient address on the contract and none given |
| 404 | No such contract in the caller's organization |
| 409 | No PDF, or the contract is not `GENERATED`, `SENT` or `VIEWED` — still `DRAFTING`, or `FAILED`, `SUPERSEDED`, `SIGNED`, `REJECTED` |
| 502 | Mailjet refused the message — its reason is in the audit log as `CONTRACT_EMAIL_FAILED` |
| 503 | Mailjet is not configured on this server |

**Why the backend sends it, not SAP.** SAP Build Process Automation cuts text
values to 1,024 characters. A contract PDF is about 110,000 characters of base64,
so a PDF handed to the workflow arrives truncated and will not open — while
Mailjet still reports success. The workflow decides *when* to email; the PDF goes
straight from the backend, which holds it, to Mailjet. The workflow only ever
sees the small result above.

**Idempotent.** SAP retries a step whose response it did not receive, and a
timeout after Mailjet accepted the message would otherwise email the customer
twice. Each send is recorded as a `CONTRACT_EMAILED` audit entry, and a later
call returns that earlier result as `ALREADY_SENT`. Pass `"resend": true` to
email again on purpose. Two calls arriving at the same instant can both send;
the realistic case — a retry after a timeout — is covered.

**The wording is editable at any time.** Subject, HTML body and plain-text body
are the three files in [`backend/email-templates/`](../backend/email-templates),
read on every send — change them while the backend is running and the next
email uses the new text. Placeholders and a test-send recipe are in that
folder's [README](../backend/email-templates/README.md). Values are HTML-escaped
in the HTML body only.

**Test sends.** Demo contacts use `@example.com`, which never receives mail — pass
`recipientEmail` with a real address, and `resend: true` to send again after
editing a template.

An OpenAPI description of this endpoint, for an SAP BPA action project, is in
[`contract-send-email-openapi.json`](contract-send-email-openapi.json).

---

## Templates

Three, because three are what the CRM holds enough data to fill. They are
ordinary `.docx` files in
[`backend/src/main/resources/contract-templates/`](../backend/src/main/resources/contract-templates)
— edit them in Word, no rebuild of anything but the jar.

| Template | For |
|---|---|
| `standard-sales-agreement.docx` | A one-off supply of a named product at a unit price |
| `enterprise-subscription-agreement.docx` | A term licence, for high-value product deals |
| `professional-services-agreement.docx` | A services engagement, where nothing discrete ships |

Selection, in `ContractTemplateService.resolve`:

```
explicit contractType in the request       -> that one
no product line on the opportunity         -> PROFESSIONAL_SERVICES_AGREEMENT
total >= contract.enterprise-value-threshold -> ENTERPRISE_SUBSCRIPTION_AGREEMENT
otherwise                                  -> STANDARD_SALES_AGREEMENT
```

The product test comes first because it is about *what* is being sold and the
value test only about *how much*: a large services engagement is still services,
and a unit-price schedule for it would leave the quantity column meaningless.

### Placeholders

Every one is backed by a field that exists. There is no `{{governingLaw}}`,
because nothing in the schema could fill it and a placeholder that always renders
empty is worse than no clause.

| Placeholder | Source |
|---|---|
| `{{contractId}}`, `{{contractNumber}}` | `contracts.contract_number` |
| `{{contractDate}}` | today |
| `{{contractType}}`, `{{contractTypeName}}` | the resolved template |
| `{{opportunityId}}` | `deals.opportunity_id` |
| `{{dealName}}`, `{{serviceName}}` | `deals.name` |
| `{{productName}}` | first line item's description |
| `{{companyName}}`, `{{companyAddress}}`, `{{industry}}` | `accounts` |
| `{{customerName}}`, `{{customerTitle}}`, `{{customerEmail}}`, `{{customerPhone}}` | the signing `contact` |
| `{{providerName}}` | `organizations.name` |
| `{{salesExecutive}}`, `{{salesExecutiveEmail}}`, `{{salesExecutivePhone}}` | the deal's owner in `users` |
| `{{currency}}`, `{{totalAmount}}`, `{{totalAmountWithCurrency}}` | frozen on the contract |
| `{{contractStartDate}}`, `{{contractEndDate}}`, `{{termMonths}}` | request, or the configured default term |
| `{{paymentTerms}}`, `{{deliveryTimeline}}` | request, then the lead's purchase timeline, then config |

Inside the schedule table, one marker row carries `{{item.lineNumber}}`,
`{{item.description}}`, `{{item.quantity}}`, `{{item.unitPrice}}` and
`{{item.lineTotal}}`. It is cloned once per line item and the original removed,
so the table keeps its borders and column widths.

Two things the renderer handles that a string replace would not:

- **Word splits placeholders across runs.** An author types
  `{{customerName}}` and Word may store it as three runs. Substitution therefore
  runs against a paragraph's whole concatenated text, writing the value into the
  run holding the placeholder's first character and emptying only the runs it
  bled into — every untouched run keeps its own formatting.
- **Anything still unresolved is blanked** before the document is saved, so
  literal braces can never reach a customer.

---

## Configuration

Defaults in `application.yml`; secrets belong in `application-local.yml`
(gitignored) or environment variables.

```yaml
contract:
  eligible-stages: PROPOSAL,NEGOTIATION,CLOSED_WON
  default-term-months: 12
  default-payment-terms: "..."
  default-delivery-timeline: "..."
  enterprise-value-threshold: 2500000
  templates:
    location: classpath:contract-templates/
    overrides: {}            # per ContractType, to point at your own file
  storage:
    root: ./var/contracts    # DB stores paths relative to this
  libreoffice:
    enabled: true
    path: soffice            # resolved on PATH; full path on Windows
    timeout-seconds: 120

mailjet:
  api-key: ${MAILJET_API_KEY:}
  secret-key: ${MAILJET_SECRET_KEY:}
  from-email: ${MAILJET_FROM_EMAIL:}
  from-name: ${MAILJET_FROM_NAME:}
  templates:
    location: file:./email-templates/
```

### `eligible-stages`

The workflow this implements is triggered by **"Proposal Accepted"**, which is
not a stage this CRM has. [`DealStages`](../backend/src/main/java/com/techcrm/crm/deal/DealStages.java)
is a fixed eight-value vocabulary shared with the frontend pipeline board, and
adding a ninth would re-bucket every existing deal. These three stages mean the
same thing here, and the list is configuration so a team that works its pipeline
differently needs no code change. Every value must be a real deal stage;
a typo is reported at startup and again on the first generate attempt.

### LibreOffice

Not bundled — install it and make sure `contract.libreoffice.path` finds it.

| OS | Typical path |
|---|---|
| Linux | `soffice` (on PATH) |
| macOS | `/Applications/LibreOffice.app/Contents/MacOS/soffice` |
| Windows | `C:/Program Files/LibreOffice/program/soffice.exe` |

Each conversion runs with its own user profile directory; without that,
concurrent `soffice` invocations contend for the single default profile and the
second exits having converted nothing.

Set `enabled: false` where there is no LibreOffice. Contracts then generate as
DOCX only, `pdfUrl` comes back `null`, and send-email refuses (the customer is
sent the PDF). The startup log says so explicitly.

### Mailjet

```yaml
mailjet:
  api-key: ...        # Mailjet -> Account Settings -> API Key Management
  secret-key: ...
  from-email: ...     # must be a sender verified in Mailjet
  from-name: ...
  templates:
    location: file:./email-templates/
```

Real values go in `application-local.yml` or `MAILJET_*` environment variables —
`application.yml` holds placeholders only. Blank keys or sender make send-email
answer 503.

The client calls `POST /v3.1/send` with Basic auth. The PDF is encoded with the
standard base64 encoder — the MIME encoder's line breaks corrupt attachments — and
Mailjet's per-message `Status` decides success, since it answers 200 per request.
A rejection keeps Mailjet's own wording ("Sender not validated") for the audit
log. `templates.location` is relative to the directory the backend is started
from.

---

## Database

Flyway [`V19__contracts.sql`](../backend/src/main/resources/db/migration/V19__contracts.sql).
V19 rather than V18 because the shared development database already has a V18
(`lead_score_fluctuation`, applied 2026-08-08) whose script is not in this
repository.

| Table | Holds |
|---|---|
| `contracts` | One row per generated contract, referencing `deals`, `accounts`, `contacts`, `users` |
| `contract_line_items` | The frozen schedule |
| `contract_signature_events` | Unused since the e-signature integration was removed; kept, with the `documenso_*` and `sign_url` columns on `contracts`, rather than dropped by a migration |

Plus two columns on `deals`: `contract_status` and `contract_signed_at`.

No customer, deal or account data is duplicated — the contract references them
and copies only what has to be frozen at generation time (the commercial terms,
`opportunity_id` for readability, and which contact the document was addressed
to).

---

## Testing

`ContractGenerationBot.postman_collection.json` in this directory covers every
endpoint end to end.

The unit tests need neither a database, nor LibreOffice, nor Mailjet:

```
./mvnw test -Dtest='com.techcrm.crm.contract.**'
```

The DOCX tests run against the real bundled templates rather than fixtures,
because the thing worth proving is that a genuine `.docx` round-trips through
docx4j with its placeholders filled and its formatting intact.

The LibreOffice happy path is not covered — it needs a real LibreOffice, and this
project has no integration test infrastructure to hang that on. The failure
paths (missing binary, disabled, timeout) are.
