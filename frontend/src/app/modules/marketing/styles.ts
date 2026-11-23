// Module-scoped stylesheet. Every selector sits under .mk-root, so nothing here
// can restyle another screen. Colours start from the host app's tokens
// (tokens.ts) so the module sits naturally inside TechCRM, then add the few
// roles the module needs: AI, provenance, and the chart series.

export const MK_CSS = `
.mk-root {
  --mk-ink: #141414;
  --mk-ink-2: #4F5157;
  --mk-ink-3: #85878C;
  --mk-surface: #FFFFFF;
  --mk-canvas: #F5F6F8;
  --mk-sunken: #FAFAFB;
  --mk-line: #E2E3E5;
  --mk-line-strong: #CDD0D4;
  --mk-primary: #185FA5;
  --mk-primary-hover: #0C447C;
  --mk-primary-tint: #E6F1FB;
  --mk-ai: #534AB7;
  --mk-ai-tint: #EEEDFE;
  --mk-ai-line: #C9C4F2;
  --mk-ok: #3B6D11;
  --mk-ok-tint: #EAF3DE;
  --mk-warn: #854F0B;
  --mk-warn-tint: #FAEEDA;
  --mk-warn-line: #EBC98F;
  --mk-bad: #A32D2D;
  --mk-bad-tint: #FCEBEB;
  --mk-neutral: #4B5563;
  --mk-neutral-tint: #F0F1F3;
  --mk-radius: 8px;
  --mk-radius-sm: 6px;
  --mk-focus: 0 0 0 3px rgba(24, 95, 165, 0.25);
  color: var(--mk-ink);
  font-size: 13px;
  line-height: 1.45;
  font-variant-numeric: tabular-nums;
}
.mk-root *, .mk-root *::before, .mk-root *::after { box-sizing: border-box; }
.mk-root button { font: inherit; }
.mk-root :focus-visible { outline: none; box-shadow: var(--mk-focus); }

/* ---------- page header ---------- */
.mk-header { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 18px; }
.mk-title { font-size: 22px; font-weight: 650; letter-spacing: -0.01em; margin: 0; }
.mk-subtitle { color: var(--mk-ink-2); margin: 4px 0 0; max-width: 720px; }
.mk-eyebrow { font-size: 11px; font-weight: 600; letter-spacing: .06em; text-transform: uppercase; color: var(--mk-ink-3); margin-bottom: 4px; display: flex; gap: 6px; align-items: center; }

/* ---------- buttons ---------- */
.mk-btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; height: 32px; padding: 0 12px;
  border-radius: var(--mk-radius-sm); border: 1px solid var(--mk-line-strong); background: var(--mk-surface); color: var(--mk-ink);
  font-size: 12.5px; font-weight: 550; cursor: pointer; white-space: nowrap; transition: background .12s, border-color .12s, color .12s; }
.mk-btn:hover:not(:disabled) { background: var(--mk-sunken); border-color: #B9BCC1; }
.mk-btn:disabled { opacity: .5; cursor: not-allowed; }
.mk-btn--primary { background: var(--mk-primary); border-color: var(--mk-primary); color: #fff; }
.mk-btn--primary:hover:not(:disabled) { background: var(--mk-primary-hover); border-color: var(--mk-primary-hover); }
.mk-btn--danger { color: var(--mk-bad); border-color: #E9B9B9; }
.mk-btn--danger:hover:not(:disabled) { background: var(--mk-bad-tint); border-color: #DC9C9C; }
.mk-btn--ok { color: var(--mk-ok); border-color: #BFD8A6; }
.mk-btn--ok:hover:not(:disabled) { background: var(--mk-ok-tint); }
.mk-btn--ghost { border-color: transparent; background: transparent; color: var(--mk-ink-2); }
.mk-btn--ghost:hover:not(:disabled) { background: var(--mk-neutral-tint); border-color: transparent; color: var(--mk-ink); }
.mk-btn--ai { color: var(--mk-ai); border-color: var(--mk-ai-line); background: var(--mk-surface); }
.mk-btn--ai:hover:not(:disabled) { background: var(--mk-ai-tint); }
.mk-btn--lg { height: 38px; padding: 0 16px; font-size: 13.5px; }
.mk-btn--sm { height: 26px; padding: 0 8px; font-size: 12px; }
.mk-icon-btn { width: 28px; height: 28px; padding: 0; }

/* ---------- panels ---------- */
.mk-panel { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); }
.mk-panel__head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 16px 0; flex-wrap: wrap; }
.mk-panel__title { font-size: 14px; font-weight: 620; margin: 0; }
.mk-panel__sub { color: var(--mk-ink-3); font-size: 12px; margin: 2px 0 0; }
.mk-panel__body { padding: 14px 16px 16px; }
.mk-stack { display: flex; flex-direction: column; gap: 16px; }
.mk-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.mk-spacer { flex: 1; }
.mk-grid-2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.mk-grid-main { display: grid; grid-template-columns: minmax(0, 1.65fr) minmax(0, 1fr); gap: 16px; align-items: start; }
.mk-split { display: grid; grid-template-columns: minmax(260px, 340px) minmax(0, 1fr); gap: 16px; align-items: start; }
@media (max-width: 1100px) { .mk-grid-main, .mk-split { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 760px) { .mk-grid-2 { grid-template-columns: minmax(0, 1fr); } }

/* ---------- KPI strip ---------- */
.mk-kpis { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
@media (max-width: 1100px) { .mk-kpis { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 560px) { .mk-kpis { grid-template-columns: minmax(0, 1fr); } }
.mk-kpi { position: relative; background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 14px 14px 12px; display: grid; grid-template-columns: 1fr auto; gap: 2px 10px; }
.mk-kpi__label { font-size: 12px; color: var(--mk-ink-2); font-weight: 520; }
.mk-kpi__value { font-size: 26px; font-weight: 640; letter-spacing: -0.02em; line-height: 1.15; grid-column: 1 / 2; }
.mk-kpi__icon { grid-row: 1 / 3; grid-column: 2; width: 32px; height: 32px; border-radius: 7px; display: grid; place-items: center; border: 1px solid var(--mk-line); color: var(--mk-ink-2); background: var(--mk-sunken); }
.mk-kpi__foot { grid-column: 1 / 3; font-size: 11.5px; color: var(--mk-ink-3); margin-top: 6px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.mk-kpi--alert { border-color: var(--mk-warn-line); background: linear-gradient(0deg, #FFFCF6, #FFFCF6); }
.mk-kpi--alert .mk-kpi__icon { color: var(--mk-warn); border-color: var(--mk-warn-line); background: var(--mk-warn-tint); }
.mk-kpi--alert::before { content: ""; position: absolute; left: -1px; top: 12px; bottom: 12px; width: 3px; border-radius: 0 3px 3px 0; background: #C98A1B; }
.mk-delta { display: inline-flex; align-items: center; gap: 2px; font-weight: 600; font-size: 11px; padding: 1px 6px; border-radius: 4px; }
.mk-delta--up { color: var(--mk-ok); background: var(--mk-ok-tint); }
.mk-delta--down { color: var(--mk-bad); background: var(--mk-bad-tint); }
.mk-delta--flat { color: var(--mk-neutral); background: var(--mk-neutral-tint); }

/* ---------- tabs ---------- */
.mk-tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--mk-line); margin-bottom: 16px; overflow-x: auto; scrollbar-width: none; }
.mk-tab { position: relative; background: none; border: 0; padding: 9px 12px 11px; font-size: 13px; color: var(--mk-ink-2); font-weight: 520; cursor: pointer; white-space: nowrap; display: inline-flex; gap: 6px; align-items: center; border-radius: 6px 6px 0 0; }
.mk-tab:hover { color: var(--mk-ink); background: var(--mk-sunken); }
.mk-tab[aria-selected="true"] { color: var(--mk-primary); font-weight: 620; }
.mk-tab[aria-selected="true"]::after { content: ""; position: absolute; left: 8px; right: 8px; bottom: -1px; height: 2px; background: var(--mk-primary); border-radius: 2px 2px 0 0; }
.mk-count { min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px; font-size: 11px; font-weight: 650; display: inline-grid; place-items: center; background: var(--mk-neutral-tint); color: var(--mk-ink-2); }
.mk-count--warn { background: #F6DDB0; color: #6B3E06; }

/* ---------- filters ---------- */
.mk-filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 14px; }
.mk-input, .mk-select, .mk-textarea { height: 32px; border: 1px solid var(--mk-line-strong); border-radius: var(--mk-radius-sm); background: var(--mk-surface); padding: 0 10px; font-size: 12.5px; color: var(--mk-ink); font-family: inherit; min-width: 0; }
.mk-textarea { height: auto; padding: 8px 10px; resize: vertical; line-height: 1.5; }
.mk-input:focus, .mk-select:focus, .mk-textarea:focus { outline: none; border-color: var(--mk-primary); box-shadow: var(--mk-focus); }
.mk-input::placeholder, .mk-textarea::placeholder { color: #A3A5AA; }
.mk-search { position: relative; }
.mk-search svg { position: absolute; left: 9px; top: 50%; transform: translateY(-50%); color: var(--mk-ink-3); pointer-events: none; }
.mk-search .mk-input { padding-left: 30px; width: 260px; }
@media (max-width: 560px) { .mk-search .mk-input { width: 100%; } .mk-search { flex: 1 1 100%; } }
.mk-seg { display: inline-flex; border: 1px solid var(--mk-line-strong); border-radius: var(--mk-radius-sm); overflow: hidden; background: var(--mk-surface); }
.mk-seg button { border: 0; background: none; padding: 0 10px; height: 30px; font-size: 12px; color: var(--mk-ink-2); cursor: pointer; border-right: 1px solid var(--mk-line); }
.mk-seg button:last-child { border-right: 0; }
.mk-seg button[aria-pressed="true"] { background: var(--mk-primary-tint); color: var(--mk-primary); font-weight: 620; }

/* ---------- badges & tags ---------- */
.mk-badge { display: inline-flex; align-items: center; gap: 5px; height: 21px; padding: 0 7px; border-radius: 4px; font-size: 11.5px; font-weight: 580; white-space: nowrap; border: 1px solid transparent; }
.mk-badge__dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.mk-tag { display: inline-flex; align-items: center; gap: 4px; height: 20px; padding: 0 6px; border-radius: 4px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.mk-tag--ai { color: var(--mk-ai); background: var(--mk-ai-tint); }
.mk-tag--ai-rec { color: var(--mk-ai); background: var(--mk-surface); border: 1px dashed var(--mk-ai-line); }
.mk-tag--human { color: var(--mk-ok); background: var(--mk-ok-tint); }
.mk-tag--edited { color: var(--mk-primary); background: var(--mk-primary-tint); }
.mk-tag--system { color: var(--mk-neutral); background: var(--mk-neutral-tint); }

/* ---------- tables ---------- */
.mk-table-wrap { overflow-x: auto; border-top: 1px solid var(--mk-line); }
.mk-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 12.5px; }
.mk-table th { position: sticky; top: 0; z-index: 1; text-align: left; font-weight: 600; font-size: 11.5px; color: var(--mk-ink-2); background: var(--mk-sunken); padding: 9px 12px; border-bottom: 1px solid var(--mk-line); white-space: nowrap; }
.mk-table td { padding: 10px 12px; border-bottom: 1px solid #EEEFF1; vertical-align: middle; }
.mk-table tbody tr { transition: background .1s; }
.mk-table tbody tr:hover { background: #FAFBFC; }
.mk-table tbody tr.mk-row--selected { background: #F3F8FD; }
.mk-table tbody tr:last-child td { border-bottom: 0; }
.mk-table .mk-num { text-align: right; }
.mk-table th.mk-num { text-align: right; }
.mk-link { background: none; border: 0; padding: 0; color: var(--mk-ink); font-weight: 600; cursor: pointer; text-align: left; }
.mk-link:hover { color: var(--mk-primary); text-decoration: underline; text-underline-offset: 2px; }
.mk-muted { color: var(--mk-ink-3); }
.mk-sub { color: var(--mk-ink-2); font-size: 12px; }
.mk-trunc { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---------- menu ---------- */
.mk-menu { position: absolute; right: 0; top: calc(100% + 4px); z-index: 30; min-width: 176px; background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: var(--mk-radius); box-shadow: 0 8px 24px rgba(20, 20, 20, .10); padding: 4px; }
.mk-menu button { display: flex; width: 100%; gap: 8px; align-items: center; padding: 7px 8px; border: 0; background: none; border-radius: 5px; font-size: 12.5px; color: var(--mk-ink); cursor: pointer; text-align: left; }
.mk-menu button:hover:not(:disabled) { background: var(--mk-sunken); }
.mk-menu button:disabled { color: var(--mk-ink-3); cursor: not-allowed; }
.mk-menu hr { border: 0; border-top: 1px solid var(--mk-line); margin: 4px 0; }
.mk-menu .mk-menu--danger { color: var(--mk-bad); }

/* ---------- overlays ---------- */
.mk-scrim { position: fixed; inset: 0; background: rgba(20, 22, 26, .38); z-index: 100; display: flex; justify-content: flex-end; }
.mk-scrim--center { align-items: center; justify-content: center; padding: 16px; }
.mk-drawer { width: min(560px, 100vw); height: 100%; background: var(--mk-surface); display: flex; flex-direction: column; box-shadow: -12px 0 32px rgba(0,0,0,.12); }
.mk-drawer__head { padding: 16px 18px; border-bottom: 1px solid var(--mk-line); display: flex; gap: 12px; align-items: flex-start; }
.mk-drawer__body { flex: 1; overflow-y: auto; padding: 16px 18px; }
.mk-drawer__foot { padding: 12px 18px; border-top: 1px solid var(--mk-line); display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; background: var(--mk-sunken); }
.mk-dialog { width: min(460px, 100%); background: var(--mk-surface); border-radius: 10px; box-shadow: 0 20px 48px rgba(0,0,0,.2); overflow: hidden; }
.mk-dialog__body { padding: 18px 20px 8px; }
.mk-dialog__foot { padding: 12px 20px 16px; display: flex; gap: 8px; justify-content: flex-end; }

/* ---------- form ---------- */
.mk-form-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 20px; align-items: start; }
@media (max-width: 900px) { .mk-form-layout { grid-template-columns: minmax(0, 1fr); } .mk-steps { position: static !important; flex-direction: row !important; overflow-x: auto; } }
.mk-steps { position: sticky; top: 0; display: flex; flex-direction: column; gap: 2px; }
.mk-step { display: flex; gap: 10px; align-items: center; padding: 8px 10px; border-radius: 7px; border: 0; background: none; cursor: pointer; text-align: left; color: var(--mk-ink-2); font-size: 12.5px; white-space: nowrap; }
.mk-step:hover { background: var(--mk-sunken); }
.mk-step[aria-current="step"] { background: var(--mk-primary-tint); color: var(--mk-primary); font-weight: 620; }
.mk-step__num { width: 22px; height: 22px; border-radius: 6px; border: 1px solid var(--mk-line-strong); display: grid; place-items: center; font-size: 11px; font-weight: 650; background: var(--mk-surface); flex-shrink: 0; }
.mk-step[aria-current="step"] .mk-step__num { border-color: var(--mk-primary); color: var(--mk-primary); }
.mk-step--done .mk-step__num { background: var(--mk-ok-tint); border-color: #BFD8A6; color: var(--mk-ok); }
.mk-step--error .mk-step__num { background: var(--mk-bad-tint); border-color: #E9B9B9; color: var(--mk-bad); }
.mk-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; }
@media (max-width: 760px) { .mk-fields { grid-template-columns: minmax(0, 1fr); } }
.mk-field { display: flex; flex-direction: column; gap: 5px; min-width: 0; }
.mk-field--wide { grid-column: 1 / -1; }
.mk-label { font-size: 12px; font-weight: 600; color: var(--mk-ink); display: flex; gap: 4px; align-items: baseline; }
.mk-label .mk-req { color: var(--mk-bad); }
.mk-help { font-size: 11.5px; color: var(--mk-ink-3); }
.mk-error { font-size: 11.5px; color: var(--mk-bad); }
.mk-chips { display: flex; gap: 6px; flex-wrap: wrap; }
.mk-chip { height: 28px; padding: 0 10px; border-radius: 14px; border: 1px solid var(--mk-line-strong); background: var(--mk-surface); font-size: 12px; color: var(--mk-ink-2); cursor: pointer; display: inline-flex; align-items: center; gap: 5px; }
.mk-chip:hover { border-color: #B9BCC1; }
.mk-chip[aria-pressed="true"] { border-color: var(--mk-primary); background: var(--mk-primary-tint); color: var(--mk-primary); font-weight: 600; }
.mk-token-input { display: flex; flex-wrap: wrap; gap: 4px; align-items: center; min-height: 32px; border: 1px solid var(--mk-line-strong); border-radius: var(--mk-radius-sm); padding: 3px 6px; background: var(--mk-surface); }
.mk-token-input:focus-within { border-color: var(--mk-primary); box-shadow: var(--mk-focus); }
.mk-token-input input { border: 0; outline: none; flex: 1; min-width: 100px; font-size: 12.5px; height: 24px; font-family: inherit; }
.mk-token { display: inline-flex; align-items: center; gap: 3px; background: var(--mk-neutral-tint); border-radius: 4px; padding: 1px 4px 1px 7px; font-size: 12px; }
.mk-token button { border: 0; background: none; cursor: pointer; color: var(--mk-ink-3); padding: 0 2px; display: grid; place-items: center; }
.mk-rule { display: grid; grid-template-columns: 40px minmax(140px, 1.3fr) minmax(110px, .8fr) minmax(120px, 1fr) 32px; gap: 8px; align-items: center; }
@media (max-width: 760px) { .mk-rule { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); } .mk-rule__join { grid-column: 1 / -1; } }
.mk-rule__join { font-size: 11px; font-weight: 700; color: var(--mk-ink-3); text-align: center; letter-spacing: .04em; }
.mk-formbar { position: sticky; bottom: -20px; margin: 16px -20px -20px; padding: 12px 20px; background: rgba(255,255,255,.96); border-top: 1px solid var(--mk-line); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; z-index: 5; }

/* ---------- callouts ---------- */
.mk-callout { display: flex; gap: 10px; align-items: flex-start; padding: 10px 12px; border-radius: var(--mk-radius-sm); font-size: 12.5px; border: 1px solid; }
.mk-callout svg { flex-shrink: 0; margin-top: 1px; }
.mk-callout--ai { background: #F8F7FE; border-color: var(--mk-ai-line); color: #3C3489; }
.mk-callout--warn { background: #FFFAF1; border-color: var(--mk-warn-line); color: #6B3E06; }
.mk-callout--bad { background: #FEF6F6; border-color: #EFC2C2; color: #7F1F1F; }
.mk-callout--info { background: #F4F8FC; border-color: #C5DAEE; color: #0C447C; }
.mk-callout--ok { background: #F6FAF1; border-color: #CFE3B9; color: #2D540C; }

/* ---------- pipeline ---------- */
.mk-flow { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
@media (max-width: 760px) { .mk-flow { grid-template-columns: minmax(0, 1fr); } }
.mk-track { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 14px; background: var(--mk-surface); }
.mk-track__step { display: flex; gap: 10px; align-items: flex-start; padding: 7px 0; font-size: 12.5px; }
.mk-state { width: 20px; height: 20px; border-radius: 50%; display: grid; place-items: center; flex-shrink: 0; border: 1.5px solid var(--mk-line-strong); color: var(--mk-ink-3); background: var(--mk-surface); }
.mk-state--done { background: var(--mk-ok); border-color: var(--mk-ok); color: #fff; }
.mk-state--run { border-color: var(--mk-ai); color: var(--mk-ai); }
.mk-state--fail { background: var(--mk-bad); border-color: var(--mk-bad); color: #fff; }
.mk-state--wait { border-style: dashed; }
.mk-progress { height: 6px; border-radius: 3px; background: var(--mk-neutral-tint); overflow: hidden; }
.mk-progress > span { display: block; height: 100%; background: var(--mk-ai); border-radius: 3px; transition: width .4s ease; }
.mk-spin { animation: mk-spin 1s linear infinite; }
@keyframes mk-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .mk-spin { animation: none; } .mk-progress > span { transition: none; } }

/* ---------- score ---------- */
.mk-score { display: inline-flex; align-items: center; gap: 8px; min-width: 92px; }
.mk-score__bar { flex: 1; height: 5px; border-radius: 3px; background: var(--mk-neutral-tint); overflow: hidden; min-width: 44px; }
.mk-score__bar > span { display: block; height: 100%; border-radius: 3px; }
.mk-score__val { font-weight: 650; font-size: 12.5px; min-width: 22px; text-align: right; }

/* ---------- previews ---------- */
.mk-preview-email { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); overflow: hidden; background: var(--mk-surface); }
.mk-preview-email__meta { padding: 10px 14px; border-bottom: 1px solid var(--mk-line); background: var(--mk-sunken); font-size: 12px; display: grid; grid-template-columns: 64px 1fr; gap: 4px 8px; }
.mk-preview-email__body { padding: 18px 20px; white-space: pre-wrap; font-size: 13px; line-height: 1.6; }
.mk-preview-cta { display: inline-block; margin-top: 14px; background: var(--mk-primary); color: #fff; padding: 8px 14px; border-radius: 6px; font-weight: 600; font-size: 12.5px; }

/* ---------- misc ---------- */
.mk-kv { display: grid; grid-template-columns: minmax(120px, 170px) minmax(0, 1fr); gap: 8px 14px; font-size: 12.5px; }
.mk-kv dt { color: var(--mk-ink-3); }
.mk-kv dd { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.mk-empty { padding: 36px 20px; text-align: center; color: var(--mk-ink-2); }
.mk-empty__icon { width: 44px; height: 44px; border-radius: 10px; border: 1px dashed var(--mk-line-strong); display: grid; place-items: center; margin: 0 auto 12px; color: var(--mk-ink-3); }
.mk-empty h4 { margin: 0 0 4px; font-size: 14px; color: var(--mk-ink); }
.mk-skel { background: linear-gradient(90deg, #F1F2F4 0%, #F8F9FA 50%, #F1F2F4 100%); background-size: 200% 100%; animation: mk-shimmer 1.2s infinite; border-radius: 4px; }
@keyframes mk-shimmer { to { background-position: -200% 0; } }
@media (prefers-reduced-motion: reduce) { .mk-skel { animation: none; } }
.mk-timeline { list-style: none; margin: 0; padding: 0; }
.mk-timeline li { display: grid; grid-template-columns: 28px minmax(0, 1fr); gap: 10px; padding-bottom: 14px; position: relative; }
.mk-timeline li::before { content: ""; position: absolute; left: 13px; top: 26px; bottom: 0; width: 1px; background: var(--mk-line); }
.mk-timeline li:last-child::before { display: none; }
.mk-actor { width: 26px; height: 26px; border-radius: 7px; display: grid; place-items: center; }
.mk-actor--AI { background: var(--mk-ai-tint); color: var(--mk-ai); }
.mk-actor--HUMAN { background: var(--mk-ok-tint); color: var(--mk-ok); }
.mk-actor--SYSTEM { background: var(--mk-neutral-tint); color: var(--mk-neutral); }
.mk-divider { border: 0; border-top: 1px solid var(--mk-line); margin: 14px 0; }
.mk-h { font-size: 12px; font-weight: 650; color: var(--mk-ink-2); text-transform: uppercase; letter-spacing: .05em; margin: 0 0 8px; }
.mk-factor { display: flex; gap: 8px; align-items: flex-start; font-size: 12.5px; padding: 3px 0; }
.mk-checkbox { width: 15px; height: 15px; accent-color: var(--mk-primary); cursor: pointer; }
.mk-legend { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--mk-ink-2); }
.mk-legend span { display: inline-flex; align-items: center; gap: 6px; }
.mk-legend i { width: 14px; height: 2px; border-radius: 1px; display: inline-block; }
.mk-tooltip { background: var(--mk-surface); border: 1px solid var(--mk-line); border-radius: 8px; box-shadow: 0 6px 18px rgba(0,0,0,.10); padding: 8px 10px; font-size: 12px; min-width: 150px; }
.mk-tooltip__row { display: grid; grid-template-columns: 12px auto 1fr; gap: 8px; align-items: center; padding: 1px 0; }
.mk-tooltip__row i { width: 12px; height: 2px; border-radius: 1px; }
.mk-tooltip__row b { font-weight: 650; }
.mk-tooltip__row span { color: var(--mk-ink-2); text-align: left; }
.mk-funnel { display: flex; flex-direction: column; gap: 10px; }
.mk-funnel__row { display: grid; grid-template-columns: 84px minmax(0, 1fr) 64px; gap: 10px; align-items: center; font-size: 12.5px; }
.mk-funnel__track { height: 22px; position: relative; }
.mk-funnel__bar { height: 100%; border-radius: 0 4px 4px 0; min-width: 2px; transition: filter .12s; }
.mk-funnel__bar:hover { filter: brightness(1.08); }
.mk-funnel__step { font-size: 11px; color: var(--mk-ink-3); padding-left: 94px; margin-top: -6px; }
.mk-compare { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 12px; }
.mk-variant { border: 1px solid var(--mk-line); border-radius: var(--mk-radius); padding: 12px; }
.mk-variant--win { border-color: #BFD8A6; background: #FAFDF6; }
.mk-sr { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0,0,0,0); border: 0; }
`;
