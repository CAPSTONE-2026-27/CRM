# Contract generation

How a won proposal becomes a signed agreement, and the API the SAP Build Process
Automation bot drives it with.

```
Sales executive moves the deal to Proposal / Negotiation / Closed Won
  -> SAP BPA bot: POST /api/contracts/generate  { "dealId": 31 }
       -> validate the opportunity
       -> fetch account, contact, owner, line items
       -> resolve the template
       -> docx4j renders the DOCX
       -> LibreOffice headless converts it to PDF
       -> both stored, contract row written
  <- { contractId, dealId, status, pdfUrl, docxUrl }
  -> SAP BPA bot: POST /api/contracts/send-for-signature
       -> Documenso creates the document and emails the signer
  <- { contractId, status, signUrl, documensoId }
  -> customer signs or declines
  -> Documenso: POST /api/contracts/sign-callback
       -> contract status, deal contract status, audit entry
       -> customer onboarding opens, on a signature only
```

There is no separate "contract" step in the pipeline board: `deals.stage` is
untouched by any of this. Contract progress lives on `deals.contract_status`,
which is a second axis — a deal can be in Negotiation with a contract already out
for signature.

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
organization — **except** `POST /sign-callback`, which Documenso calls with no
CRM identity and which is authenticated by a shared secret instead.

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

**201 Created** on a new contract, **200 OK** when an existing live contract was
returned instead. Same body either way:

```json
{
  "contractId": "42",
  "contractNumber": "CTR-000042",
  "dealId": "31",
  "opportunityId": "OPP-000031",
  "contractType": "STANDARD_SALES_AGREEMENT",
  "status": "GENERATED",
  "pdfUrl": "/api/contracts/42/document.pdf",
  "docxUrl": "/api/contracts/42/document.docx"
}
```

`pdfUrl` is `null` when PDF conversion is turned off, so a caller can tell "not
produced" from "here it is" without probing the URL.

| Status | When |
|---|---|
| 400 | No billing address, no contact with an email, no owner, zero deal value, end date not after start, unknown contract type |
| 404 | No such deal in this organization |
| 409 | Deal is in an ineligible stage, or its contract is already signed and `regenerate` was asked for |
| 500 | DOCX generation, PDF conversion or storage failed — the reason is recorded on the contract row, not returned |

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

`GENERATED` -> `SENT` -> `VIEWED` -> `SIGNED` | `REJECTED`, plus `FAILED`
(generation broke) and `SUPERSEDED` (replaced by a regeneration). `SIGNED`,
`REJECTED` and `SUPERSEDED` are terminal — no webhook moves a contract out of
them.

### `POST /send-for-signature`

```json
{ "contractId": 42, "recipientName": "Legal Desk", "recipientEmail": "legal@..." }
```

Recipient defaults to the contact the contract was generated for.

```json
{ "contractId": "42", "status": "SENT",
  "signUrl": "https://app.documenso.com/sign/<token>", "documensoId": "881" }
```

| Status | When |
|---|---|
| 404 | No such contract |
| 409 | Contract has no generated PDF, or is not in a sendable state |
| 502 | Documenso refused or could not be reached |
| 503 | Documenso is not configured on this server |

**The signature block.** Documenso refuses to send a document whose signer has
nothing to sign — *"Signers must have at least one signature field"* — so the
client places one between uploading the PDF and sending it. It places three
fields, not one: SIGNATURE with NAME and DATE stacked underneath, which is what
makes it read as a signature block rather than a stray widget mid-contract.

They go on the **last page**, resolved by counting pages in the generated PDF
([`PdfPageCount`](../backend/src/main/java/com/techcrm/crm/contract/document/PdfPageCount.java))
rather than assumed: the templates run to two pages today, but a long address or
a multi-line schedule makes it three, and a signature field stranded on page one
of a three-page contract is exactly the wrong outcome. If the page count cannot
be read the field falls back to page 1 — an awkwardly placed box beats a failed
send. Positions are percentages of the page and all of it is configurable under
`documenso.signature-field`.

Calling it twice returns the existing signing details rather than creating a
second Documenso document — two links to one agreement, only one of which
reports back, is not a recoverable state.

### `POST /sign-callback`

Called by Documenso. **Unauthenticated** in the Spring Security sense; the shared
secret in `X-Documenso-Secret` is its access control, compared in constant time.
When `documenso.webhook.secret` is unset the endpoint refuses every delivery
(503) rather than trusting one — a public URL that can mark any contract signed
would be worse than a broken integration.

| Event | Effect |
|---|---|
| `DOCUMENT_SENT` | `SENT` |
| `DOCUMENT_OPENED` | `VIEWED` |
| `DOCUMENT_SIGNED`, `DOCUMENT_COMPLETED` | `SIGNED`, **and customer onboarding opens** |
| `DOCUMENT_REJECTED`, `DOCUMENT_CANCELLED` | `REJECTED`, with the decline reason |
| anything else | recorded, nothing changes |

```json
{ "result": "processed", "contractId": "42", "status": "SIGNED" }
```

**Idempotency.** Documenso retries anything it did not get a 2xx for. Each
delivery is keyed by `(contract, event type, SHA-256 of the raw body)` in
`contract_signature_events`; a redelivery is answered `"result": "duplicate"` and
does nothing. The whole delivery is one transaction, so the "seen it" marker
commits with the actions it guards — recorded-but-not-applied would make the next
retry look like a duplicate and lose the signature.

`DOCUMENT_SIGNED` and `DOCUMENT_COMPLETED` both mean signed because the CRM
creates exactly one recipient per document. That needs revisiting if
countersigning is ever added.

### `GET /{contractId}/document.pdf` · `GET /{contractId}/document.docx`

Served through the API, not from a static path, so the same token and
organization scoping that guards everything else guards the documents.
`Content-Disposition: attachment; filename="CTR-000042.pdf"`, `Cache-Control:
no-store`. **404** when the document was never produced or has gone missing from
the store.

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

documenso:
  base-url: ${DOCUMENSO_BASE_URL:}
  api-key: ${DOCUMENSO_API_KEY:}
  api-key-prefix: ""         # "Bearer" behind a proxy that wants an auth scheme
  send-email: true
  webhook:
    secret: ${DOCUMENSO_WEBHOOK_SECRET:}
    secret-header: X-Documenso-Secret
  signature-field:
    page: 0                  # 0 = last page; a positive number pins one
    x: 8
    y: 62                    # percentages of the page
    width: 34
    height: 10
    include-name-and-date: true
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
DOCX only, `pdfUrl` comes back `null`, and send-for-signature refuses (the
customer signs the PDF). The startup log says so explicitly.

### Documenso

The project had no Documenso integration before this, so there was no version to
match; the client is written against Documenso's public **API v1**:

```
POST /api/v1/documents            -> { documentId, uploadUrl, recipients[] }
PUT  <uploadUrl>                     the PDF bytes
POST /api/v1/documents/{id}/fields   SIGNATURE, then NAME and DATE
POST /api/v1/documents/{id}/send
```

The fields step is not optional: without it `/send` returns 400 and the document
stays in DRAFT for ever.

Two things learned the hard way, both now covered by tests. The presigned
`uploadUrl` must be passed to `RestClient` as a pre-parsed `URI` — the `String`
overload treats it as a URI template and re-encodes the `%2F` in
`X-Amz-Credential` into `%252F`, which S3 rejects as a malformed credential. And
every Documenso error carries the provider's own response body into the
exception, because a bare "400 BAD_REQUEST" sent us hunting the API key when
Documenso had plainly said what was wrong.

The upload URL is presigned and absolute — on Documenso Cloud it points at their
object store — so it is called with no Authorization header. Sending the API key
to whatever host that URL names would leak it.

The two things installations actually differ on are settings, not assumptions:
`api-key-prefix` (v1 takes the raw key; a fronting proxy may want `Bearer`) and
`webhook.secret-header`.

Set `api-key-prefix` to the scheme name only — the separating space is added by
the client. Spring's property binder trims trailing whitespace, so a configured
`"Bearer "` arrives as `"Bearer"` and would otherwise produce the header
`Bearersk_live_...` and a 401 with no clue as to why.

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
| `contract_signature_events` | Every accepted webhook delivery, and the uniqueness that makes redelivery a no-op |

Plus two columns on `deals`: `contract_status` and `contract_signed_at`.

No customer, deal or account data is duplicated — the contract references them
and copies only what has to be frozen at signature time (the commercial terms,
`opportunity_id` for readability, and which contact the document was addressed
to).

---

## Testing

`ContractGenerationBot.postman_collection.json` in this directory covers every
endpoint end to end.

The unit tests need neither a database, nor LibreOffice, nor Documenso:

```
./mvnw test -Dtest='com.techcrm.crm.contract.**'
```

The DOCX tests run against the real bundled templates rather than fixtures,
because the thing worth proving is that a genuine `.docx` round-trips through
docx4j with its placeholders filled and its formatting intact.

The LibreOffice happy path is not covered — it needs a real LibreOffice, and this
project has no integration test infrastructure to hang that on. The failure
paths (missing binary, disabled, timeout) are.
