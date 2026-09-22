# Proposal generation

The Proposal Generation Bot: deal → DOCX → PDF.

```
Deal reaches stage PROPOSAL
        │
        ▼
SAP Build Process Automation
        │
        ▼  POST /api/proposals/generate
Spring Boot ── validate ── assemble CRM data ── pick template
        │
        ├── docx4j ──────────► DOCX
        ├── LibreOffice ─────► PDF
        └── DocumentStorageService ──► disk
        │
        ▼
     DRAFT   ── downloadable as PDF and DOCX
```

## What this reuses

Almost everything. The proposal module owns its own data and its own rules; the
machinery underneath is the contract module's, used directly:

| Reused, unchanged | From |
|---|---|
| `DocxGenerationService` | `contract.document` — placeholder substitution across split runs, pricing-row cloning |
| `PdfConversionService` | `contract.document` — headless LibreOffice, per-conversion profile |
| `DocumentStorageService` | `contract.document` — proposals share `contract.storage.root` |
| `ContractDocumentException` | `contract.document` — the document-layer failure type |
| JWT security, `ApiExceptionHandler`, `AuditLogService` | the CRM |

Nothing about those services is contract-shaped: they take bytes and a document
number.

### What is deliberately *not* reused

**Customer onboarding.** A proposal never activates the customer — an accepted
price is the sales executive's cue to draft a contract, not the CRM's cue to
onboard a customer who has agreed to nothing yet.

**The deal's stage.** Nothing here moves the deal to NEGOTIATION. Advancing the
pipeline is a decision the sales executive makes.

### `dealId` is `deals.id`

The same identifier the contract bot uses. It is **not** `opportunityId`
(`OPP-000031`), which is a display reference derived from the id, and it is not
an account id.

### Pricing, and why there are no discounts or taxes

This CRM has no product entity. A proposal's pricing is resolved from the
originating lead (`leads.product`, `leads.product_quantity`) or, when the deal
came from no lead, a single line for the deal's own value — then **frozen** into
`proposal_line_items`. Re-deriving it later would let an edit to that lead change
what a customer was quoted.

There is likewise **no discount and no tax anywhere in the schema**, so the
quotation prints neither. A zero-valued Discount row on a customer-facing
document invites the question of why it is there, and a fabricated tax rate on a
priced document is worse.

The unit price is derived by division and the line total recomputed from that
rounded figure, so the printed columns multiply out. On an awkward quantity that
can leave the total a few paise under the deal value; a quotation whose own
arithmetic does not add up is the worse failure in front of a customer.

## Endpoints

All under `/api/proposals`. All require the CRM's JWT.

| Method | Path | Purpose |
|---|---|---|
| POST | `/generate` | Deal → DOCX → PDF → `DRAFT` |
| GET | `/` | Every proposal in the caller's org |
| GET | `/{id}` | The whole record |
| GET | `/{id}/status` | Small payload — what SAP BPA polls |
| GET | `/{id}/document.pdf` | Download the PDF |
| GET | `/{id}/document.docx` | Download the DOCX |

### `POST /generate`

```json
{ "dealId": 31 }
```

Optional: `proposalType`, `contactId`, `validUntil`, `paymentTerms`,
`deliveryTimeline`, `regenerate`.

```json
{
  "proposalId": "12",
  "proposalNumber": "PRP-000012",
  "dealId": "31",
  "opportunityId": "OPP-000031",
  "proposalType": "STANDARD_SALES_PROPOSAL",
  "status": "DRAFT",
  "pdfUrl": "/api/proposals/12/document.pdf",
  "docxUrl": "/api/proposals/12/document.docx"
}
```

**201** when a proposal was created, **200** when a retry was absorbed. The body
is identical either way.

Takes roughly 25–30 seconds. Give the SAP BPA action a timeout of at least 60s.

| Status | Cause |
|---|---|
| 400 | no billing address, no emailable contact, no deal owner, zero deal value, `validUntil` in the past |
| 404 | unknown deal, or a deal in another organization |
| 409 | deal not in an eligible stage, or a proposal already with the customer |
| 500 | render or conversion failed — recorded against the proposal, detail never returned |

**Idempotency.** A retry returns the deal's existing live proposal rather than
producing a second one — two different prices in a customer's inbox is worse than
a failed retry. Enforced by the check *and* by
`uq_proposals_live_per_deal`, so two simultaneous retries cannot both win.

`SENT_FOR_SIGNATURE`, `VIEWED`, `SIGNED` and `REJECTED` were set by a former
e-signature integration and nothing sets them now. A `SIGNED` proposal still
cannot be regenerated; one left `SENT_FOR_SIGNATURE` or `VIEWED` can be, with
`regenerate: true`, since its decision can no longer arrive.

## Templates

Three, in `src/main/resources/proposal-templates/`, chosen by
`ProposalTemplateService`:

```
explicit proposalType in the request  -> that one
no product on the opportunity         -> PROFESSIONAL_SERVICES_PROPOSAL
value >= implementation-value-threshold -> SOFTWARE_IMPLEMENTATION_PROPOSAL
otherwise                             -> STANDARD_SALES_PROPOSAL
```

The product test comes first: a large services engagement is still a services
engagement, and a unit-price schedule for it would leave the quantity column
meaningless.

They are ordinary `.docx` files. Whoever owns the proposal wording edits them in
Word — no Java, no rebuild. Point `proposal.templates.location` at a directory to
use your own.

### Placeholders

Scalars: `proposalNumber`, `proposalDate`, `proposalTypeName`, `opportunityId`,
`dealName`, `serviceName`, `productName`, `companyName`, `companyAddress`,
`industry`, `customerName`, `customerTitle`, `customerEmail`, `customerPhone`,
`providerName`, `salesExecutive`, `salesExecutiveEmail`, `salesExecutivePhone`,
`currency`, `totalAmount`, `totalAmountWithCurrency`, `validUntil`,
`validityDays`, `paymentTerms`, `deliveryTimeline`.

Pricing row — the table row containing these is cloned once per line:
`item.lineNumber`, `item.description`, `item.quantity`, `item.unitPrice`,
`item.lineTotal`.

Every key is backed by a field that exists. There is no `{{discount}}` and no
`{{taxAmount}}`, for the reason above.

## Configuration

Only what differs from contracts. Storage and LibreOffice are configured once,
under `contract`, and shared.

```yaml
proposal:
  eligible-stages: PROPOSAL
  default-validity-days: 30
  default-payment-terms: "50% on acceptance of this proposal, 50% on delivery. Invoices are payable within 30 days."
  default-delivery-timeline: "Within 30 days of proposal acceptance."
  implementation-value-threshold: 2500000
  templates:
    location: ${PROPOSAL_TEMPLATE_LOCATION:classpath:proposal-templates/}
```

### `eligible-stages`

`PROPOSAL` alone by default — **narrower than the contract module's list on
purpose.** This bot fires when a deal *reaches* the Proposal stage. "Proposal
Accepted" is a later business outcome and is what the contract bot keys off.
Quoting a deal already in NEGOTIATION would put a fresh price in front of a
customer who is arguing about the last one.

Every value must be a real `DealStages` value; a typo fails loudly and names it.

## Database

`V22__proposals.sql`.

| Table | Holds |
|---|---|
| `proposals` | one row per proposal; commercial terms frozen at generation |
| `proposal_line_items` | the pricing, snapshotted |
| `proposal_signature_events` | unused since the e-signature integration was removed; kept rather than dropped by a migration |

`deals` gains `proposal_status` and `proposal_signed_at` — separate from the
contract columns, because a deal legitimately has both at once.

Key indexes:

- `uq_proposals_live_per_deal` — partial unique on `deal_id` where status is
  live. `GENERATING` is inside it, so two retries cannot both get through the
  render window.

Separate tables from `contracts` rather than one table with a kind column: the
partial index that enforces "one live document per deal" would have had to span
that column too, and every column only one kind uses would have become nullable.

## Testing

None of the tests need a database or LibreOffice to run —
except `ProposalDocumentTest.convertsToAPdfWithLibreOffice`, which is skipped
where LibreOffice is not installed.

`ProposalDocumentTest` is the one that matters most: it loads the real templates
from the jar, renders them with the real docx4j service, and asserts no `{{ }}`
survives and the pricing table grew a row per line. A template with a misspelled
placeholder passes every mocked test and fails there.
