# Frontend guide

Every file in `frontend/src/`.

**Stack:** React 18, TypeScript, Vite, TanStack Query, `sonner` (toasts),
`react-dnd` (Kanban drag-and-drop), `lucide-react` (icons).

---

## Two things to know before reading any code

**1. Styling is inline, not Tailwind.** Despite Tailwind being installed for the
bundled shadcn components, every hand-written screen and component styles with
inline `style={{ ... }}` objects reading from `app/tokens.ts`. Follow that —
mixing approaches is how a codebase ends up with two design systems.

**2. There is no type checking.** TypeScript is not a dependency; Vite
transpiles with esbuild, which strips types without checking them. `npm run
build` catches syntax and import errors only. A type error will reach runtime.
Be correspondingly careful with types, especially when a field can be `null`.

---

## File tree

```
frontend/src/
├── main.tsx                     React root
├── app/
│   ├── App.tsx                  shell, routing, auth gate
│   ├── tokens.ts                design tokens
│   ├── lib/
│   │   ├── apiClient.ts         HTTP + SSE transport
│   │   ├── auth.tsx             session context
│   │   ├── queries.ts           every server-state hook
│   │   ├── types.ts             every API type
│   │   └── dealScoring.ts       XGBoost input vocabulary
│   ├── screens/                 4 files, one per area
│   └── components/
│       ├── crm/                 15 hand-written components
│       ├── ui/                  48 bundled shadcn primitives
│       └── figma/               image helper
```

---

## Entry and shell

### `main.tsx`
Mounts React and wraps the app in `QueryClientProvider` and `AuthProvider`.

### `app/App.tsx` (156 lines)
The shell and the router. There is **no react-router** — navigation is
`useState` over a `ScreenId` union.

Its job, in order:

1. `status === "loading"` → show `BrandLoader` (the animated logo).
2. `unauthenticated` → show `AuthScreen`, nothing else.
3. `user.mustChangePassword` → force the password change screen.
4. Otherwise → `Sidebar` + `Topbar` + the active screen.

Also holds `screenMeta` (titles and module badges) and wraps each screen in an
`ErrorBoundary`, scoped per screen so one crash is contained and navigating away
clears it.

### `app/tokens.ts` (69 lines)
Colours, badge variants, flow-node colours, status dots, font scale. **Every
colour in the app should come from here.** `colors.primary` is the blue,
`colors.aiPurple` marks AI-generated content, `colors.danger`/`success`/`warning`
carry the obvious meanings.

---

## `app/lib/` — the data layer

### `apiClient.ts` (167 lines)
The only place `fetch` is called. Everything else goes through it.

| Export | Purpose |
|---|---|
| `api.get/post/patch/delete` | JSON requests |
| `downloadFile()` | Authenticated file download (CSV export) |
| `streamCopilotChat()` | SSE streaming for the Deal coach |
| `API_BASE_URL`, `SERVER_ORIGIN` | Base URLs |

Three details that matter:

- **Automatic refresh on 401.** A 401 triggers one `/auth/refresh` attempt, then
  retries the original request. If that fails, the unauthorized handler fires
  and the app returns to the login screen. The `retried` flag prevents a loop.
- **`SERVER_ORIGIN` vs `API_BASE_URL`.** REST lives under `/api`; Spring mounts
  the OAuth handshake at the **server root**. `SERVER_ORIGIN` is `API_BASE_URL`
  with `/api` stripped, and is what the SSO buttons use.
- **Downloads cannot use `<a href>`.** Neither an anchor nor `window.open` sends
  the Bearer token, so `downloadFile` fetches the body and hands the browser a
  temporary blob URL.

> The SSE parser matches `event:` **without** requiring a following space. The
> space is optional in the spec and Spring's `SseEmitter` omits it — requiring it
> made the Deal coach silently emit nothing.

### `auth.tsx` (173 lines)
`AuthProvider` + `useAuth()`. Holds the current user and `status`
(`loading` / `authenticated` / `unauthenticated`), and exposes `login`,
`signup`, `logout`, `refreshUser`, `changePassword`.

**The access token lives in memory only** — never `localStorage`, so an XSS
cannot exfiltrate it. A page reload therefore has no token and silently
refreshes on mount.

**The session hint.** The refresh cookie is httpOnly, so JavaScript cannot see
whether a session exists. A `techcrm.hasSession` flag in `localStorage` avoids
firing a guaranteed-401 refresh on every visit by a logged-out user. It is only
a hint — the cookie remains the authority. `hasProbableSession()` also returns
true when the URL carries `?signed_in=1`, which is how an OAuth callback says
"a session was just created that you cannot see, go and pick it up."

### `types.ts` (441 lines)
Every API shape. **Mirrors the backend DTOs** — adding a field the API does not
return gives no type error and reads back as `undefined` at runtime, so keep
them in step by hand.

Also exports the label maps the UI renders from: `CONTACT_STATUS_LABELS`,
`DEAL_STAGE_LABELS`, `DEAL_FLOW_STEPS`, `CONVERTIBLE_CONTACT_STATUSES`.

### `queries.ts` (410 lines)
Every server-state hook, built on TanStack Query. **No component fetches
directly.**

The pattern: a `useQuery` for reads keyed by resource, a `useMutation` for
writes that invalidates the affected keys in `onSuccess`.

| Key | Invalidated by |
|---|---|
| `["leads"]` | Any lead write. Also refreshes `["leads","stats"]`, which is nested under it. |
| `["deals"]` | Deal writes, and meeting submissions (which change the denormalised score) |
| `["deal-workspace", id]` | Meeting output, review, stage change |
| `["deal-onboarding", id]` | Stage change to Closed Won |

Two hooks worth reading closely:

- **`useLeads`** polls every 8s **only while the current page contains an
  unscored lead** (CSV imports score asynchronously). A settled page stops
  refetching, so it never fights with mid-typing search.
- **`useSubmitMeetingOutput`** is the pipeline trigger. It takes several
  seconds; the form shows progress rather than a bare spinner.

### `dealScoring.ts` (149 lines)
The input vocabulary for the **manual** 17-field deal scoring form. Mirrors the
trained bundle exactly — the service scores in strict mode, so a value it was not
trained on is rejected with a 400 rather than silently treated as the median.

Exports the option lists, `emptyDealScoringInput()` (sensible mid-range defaults,
so a rep adjusts rather than fills 17 blanks), `toDealScoringPayload()`, and
`scoreBandVariant()`.

> Distinct from `dealflow`'s automatic path, where the LLM extracts these values
> from a meeting write-up. The backend's `DealParameters.java` is the
> authoritative copy; `GET /api/deal-flow/parameters` serves it if you would
> rather not duplicate the lists.

---

## `app/screens/` — the four screen files

### `MainScreens.tsx` (1783 lines)
Every main screen, exported individually: `Dashboard`, `Leads`, `Pipeline`,
`Accounts`, `Cases`, `Workflow`, `RPA`, `Copilot`, `Marketing`, `Analytics`,
`Security`.

Local helpers at the top — `Table`, `TableRow`, `Cell`, `SearchInput`,
`FilterSelect`, `EmptyState`, `LoadingState`, `formatCurrency` — are shared by
all of them. Use these rather than inventing new table markup.

Two screens carry the workflow:

- **`Leads`** — search (300 ms debounce), status filter, server pagination,
  CSV import/export, bulk delete, and columns for AI score, status,
  **qualification** and **contact status**. Opens `LeadDetailModal` (which hosts
  `LeadFlowPanel`), `EditLeadModal`, and `LeadOutputModal`.
- **`Pipeline`** — the Kanban board plus an **Opportunities table** below it.
  The board is for *moving* deals; the table is for *working* them, and each row
  opens the `DealWorkspace`. Cards show the model's deal score in preference to
  the manually entered probability.

`Copilot` is the Deal coach chat, consuming `streamCopilotChat`.

### `Wizards.tsx` (2047 lines)
Eight multi-step creation wizards, `F01`–`F08`: RPA bot, user, lead, account,
workflow, bot deployment, deal, campaign. Each is a stepper with its own local
state, submitting through a `queries.ts` mutation at the end. `wizardMeta` holds
titles and badges.

### `Auth.tsx` (449 lines)
Sign-in and sign-up, in a Zoho-style split layout with an animated brand panel.
Also exports `ForceChangePasswordScreen`.

The SSO buttons navigate to `${SERVER_ORIGIN}/oauth2/authorization/{id}`.
`OAUTH_REGISTRATION_ID` maps `microsoft → azure`, because that is the
registration id server-side.

Animations respect `prefers-reduced-motion`.

### `Profile.tsx` (196 lines)
The current user's own profile and password change.

---

## `app/components/crm/` — hand-written components

### Shared

| File | Purpose |
|---|---|
| `ui.tsx` (674) | The component library: `Badge`, `Button`, `Card`, `MetricCard`, `MetricGrid`, `AIInsightBox`, `Avatar`, `Switch`, `Field`, `Stepper`, `ProgressBar`, `Stack`, `Row`, `FlowNode`, `FlowCanvas`. **Look here before writing a new component.** `Field` handles text/select/number/date/time and is controlled only when given `onChange`. |
| `Sidebar.tsx` (197) | Navigation, plus `PERMISSION_CATALOG`, `ROLE_DEFAULT_PERMISSIONS` and `screenAllowed()`. Screens with no permission key (dashboard, copilot) are always visible. |
| `Topbar.tsx` (141) | Title, module badge, user menu, sign-out. |
| `BrandLogo.tsx` (115) | The mark — three bars rising like a chart. `BrandLoader` staggers them so it reads as a loading indicator. |
| `ErrorBoundary.tsx` (79) | Catches render crashes so one bad field cannot blank the whole app. |

### Lead workflow

| File | Purpose |
|---|---|
| `LeadFlowPanel.tsx` (352) | **Flow steps 3–6** as one panel: qualification verdict, assignment, contact status, conversion. Shown as a connected sequence with real gates — an unqualified lead cannot be assigned, and a lead the customer has not agreed to meet cannot convert. Splitting these across screens would hide *why* the next button is disabled. |
| `LeadOutputModal.tsx` (333) | The Lead Output module: record a meeting against a lead, generate an AI summary and re-score, review and edit it, then save. Two-phase — nothing persists until you save. |

### Deal workflow

| File | Purpose |
|---|---|
| `DealWorkspace.tsx` (559) | The container. Progress tracker across the top, live score card on the right, and a tabbed working area: Meeting output / AI analysis / Manager review / History. Also hosts the close-deal panel and, once won, onboarding. |
| `MeetingOutputForm.tsx` (293) | **Step 4.** The 16-field structured write-up. Only date, time and summary are required. Blank optional fields are **dropped, not sent as `""`** — an empty string reads to the model as "asked and answered with nothing". |
| `ParameterViewer.tsx` (176) | **Steps 6–7.** Each extracted parameter with its value, confidence bar and explanation, plus a collapsible view of the engineered features and the exact labels sent to XGBoost. Flags defaulted parameters. |
| `DealScoreCard.tsx` (172) | **Step 9.** Score, win probability, confidence, risk level, positive/negative factors, recommended action. Each number is labelled with what it actually means, because four numbers on one card are easy to confuse. |
| `ManagerReviewPanel.tsx` (206) | **Step 10.** Approve / reject / override with comments, plus the review history. |
| `DealScoringForm.tsx` (146) | The **manual** 17-input form used by the deal wizard. Every categorical is a `select`, never free text — the strict scorer would reject a typed variant. |
| `KanbanBoard.tsx` (236) | Drag-and-drop pipeline board (`react-dnd`). Columns share the width until they would be too narrow to read a deal name, then the board scrolls sideways. |

### Other

| File | Purpose |
|---|---|
| `WorkflowBuilder.tsx` (370) | Visual workflow node editor. |
| `figma/ImageWithFallback.tsx` | Image with a fallback source. |

### `components/ui/` — 48 shadcn primitives

Bundled Radix-based components (accordion, dialog, select, …). **Largely unused
by the hand-written screens**, which use `crm/ui.tsx` instead. Do not delete
them, but prefer `crm/ui.tsx` for new work so the app keeps one visual language.

---

## How to add a feature

Worked example — a new field on the lead:

1. **Backend:** migration → entity → `LeadResponse` → `LeadMapper`.
2. **`types.ts`:** add the field to the `Lead` type.
3. **`queries.ts`:** usually nothing — existing hooks carry it.
4. **UI:** render it in `MainScreens.tsx` or the relevant component.
5. **`npm run build`** to catch import and syntax errors. Remember it does *not*
   catch type errors.

Adding a whole screen: add the id to `ScreenId` in `Sidebar.tsx`, an entry to
`screenMeta` in `App.tsx`, and export the component from `MainScreens.tsx`.
