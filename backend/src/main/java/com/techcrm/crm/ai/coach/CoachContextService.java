package com.techcrm.crm.ai.coach;

import com.techcrm.crm.account.Account;
import com.techcrm.crm.account.AccountRepository;
import com.techcrm.crm.auth.AuthenticatedUser;
import com.techcrm.crm.deal.Deal;
import com.techcrm.crm.deal.DealAccess;
import com.techcrm.crm.deal.DealRepository;
import com.techcrm.crm.deal.DealStages;
import com.techcrm.crm.lead.Lead;
import com.techcrm.crm.lead.LeadRepository;
import com.techcrm.crm.user.PermissionDefaults;
import com.techcrm.crm.user.Role;
import com.techcrm.crm.user.User;
import com.techcrm.crm.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the deal coach's view of the CRM, read fresh on every question: what
 * this caller can see right now, and nothing more.
 *
 * Visibility is the same rule the rest of the CRM applies — DealAccess for the
 * pipeline, assigned-to for leads — so a sales rep's coach sees that rep's
 * pipeline, and a manager's coach sees the team's. Asking the model to "only
 * talk about your own deals" would be a request; not giving it anyone else's
 * is a guarantee.
 *
 * Everything arithmetic is done here, not by the model: totals, weighted
 * forecast, days-to-close, overdue and at-risk lists. A 4B model misadds
 * rupee amounts and miscounts days often enough that asking it to would put
 * wrong numbers in front of a rep with the CRM's authority behind them.
 *
 * Bounded so the prompt stays well inside the model's context window: at most
 * {@link #MAX_OPEN_DEALS} open deals, {@link #MAX_CLOSED_DEALS} recent closes,
 * {@link #MAX_LEADS} active leads and {@link #MAX_CONVERTED_LEADS} converted
 * ones, each field clipped by CoachGuardrails.field. Contact details (email,
 * phone) and free-text notes are never included.
 */
@Service
public class CoachContextService {

    static final int MAX_OPEN_DEALS = 25;
    static final int MAX_CLOSED_DEALS = 5;
    static final int MAX_LEADS = 15;
    static final int MAX_CONVERTED_LEADS = 15;

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private static final Map<String, String> STAGE_LABELS = Map.of(
            DealStages.OPPORTUNITY_CREATED, "Opportunity created",
            DealStages.MEETING_SCHEDULED, "Meeting scheduled",
            DealStages.PROSPECTING, "Prospecting",
            DealStages.QUALIFICATION, "Qualification",
            DealStages.PROPOSAL, "Proposal",
            DealStages.NEGOTIATION, "Negotiation",
            DealStages.CLOSED_WON, "Closed won",
            DealStages.CLOSED_LOST, "Closed lost");

    private final DealRepository dealRepository;
    private final LeadRepository leadRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public CoachContextService(DealRepository dealRepository,
                               LeadRepository leadRepository,
                               AccountRepository accountRepository,
                               UserRepository userRepository) {
        this.dealRepository = dealRepository;
        this.leadRepository = leadRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    /** What the controller needs to assemble the system prompt. */
    public record CoachContext(String userName, String roleLabel, String scopeDescription, String snapshot) {
    }

    @Transactional(readOnly = true)
    public CoachContext build(AuthenticatedUser caller) {
        return build(caller, LocalDate.now(ZONE));
    }

    /** Date injected so the snapshot is testable. */
    @Transactional(readOnly = true)
    public CoachContext build(AuthenticatedUser caller, LocalDate today) {
        Map<Long, User> users = userRepository.findByOrganizationIdAndDeletedAtIsNull(caller.organizationId())
                .stream().collect(Collectors.toMap(User::getId, Function.identity(), (a, b) -> a));
        User me = users.get(caller.userId());
        String userName = me != null && me.getFullName() != null
                ? CoachGuardrails.field(me.getFullName(), 60) : "a TechCRM user";

        boolean ownOnly = DealAccess.isScopedToOwnDeals(caller);
        boolean seesPipeline = can(caller, "pipeline");
        boolean seesLeads = can(caller, "leads");
        // A manager's snapshot names each deal's owner, since "whose deals are
        // slipping" is the question a manager asks. A rep's owns them all.
        boolean showOwners = !ownOnly;

        String scope = describeScope(ownOnly, seesPipeline, seesLeads);

        StringBuilder snapshot = new StringBuilder();
        snapshot.append("Today: ").append(today).append(" (")
                .append(today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH)).append(")\n");
        snapshot.append("Freshness: read from the CRM database just now, current as of today.\n");
        snapshot.append("Scope: ").append(scope).append("\n");

        // Deal name per id, so a converted lead can name the deal it became
        // without widening what the caller can see: only deals already in scope
        // are in here, and anything else falls back to the bare OPP reference.
        Map<Long, String> dealNames = new LinkedHashMap<>();

        if (seesPipeline) {
            Map<Long, String> accounts = accountRepository.findByOrganizationIdOrderByCreatedAtDesc(caller.organizationId())
                    .stream().collect(Collectors.toMap(Account::getId, a -> a.getName() == null ? "" : a.getName(), (a, b) -> a));
            List<Deal> visible = DealAccess.visibleDeals(dealRepository, caller);
            visible.forEach(d -> dealNames.put(d.getId(), dealName(d)));
            appendPipeline(snapshot, visible, accounts, users, showOwners, today);
        } else {
            snapshot.append("\nPipeline: not available to this user's role.\n");
        }

        if (seesLeads) {
            List<Lead> leads = ownOnly
                    ? leadRepository.findByOrganizationIdAndAssignedToId(caller.organizationId(), caller.userId())
                    : leadRepository.findByOrganizationId(caller.organizationId());
            appendLeads(snapshot, leads, users, showOwners, dealNames);
        } else {
            snapshot.append("\nLeads: not available to this user's role.\n");
        }

        return new CoachContext(userName, roleLabel(caller.role()), scope, snapshot.toString());
    }

    /** The scope sentence alone, without loading any records. */
    public String scopeFor(AuthenticatedUser caller) {
        return describeScope(DealAccess.isScopedToOwnDeals(caller), can(caller, "pipeline"), can(caller, "leads"));
    }

    /* ------------------------------------------------------------ pipeline */

    private void appendPipeline(StringBuilder out, List<Deal> deals, Map<Long, String> accounts,
                                Map<Long, User> users, boolean showOwners, LocalDate today) {
        List<Deal> open = deals.stream().filter(d -> !DealStages.isClosed(d.getStage())).toList();
        List<Deal> closed = deals.stream().filter(d -> DealStages.isClosed(d.getStage())).toList();

        BigDecimal openValue = sum(open, Deal::getValue);
        BigDecimal weighted = open.stream()
                .map(d -> {
                    Double pct = winPercent(d);
                    return d.getValue() == null || pct == null ? BigDecimal.ZERO
                            : d.getValue().multiply(BigDecimal.valueOf(pct / 100.0));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Deal> overdue = open.stream()
                .filter(d -> d.getExpectedCloseDate() != null && closeDate(d).isBefore(today)).toList();
        List<Deal> closingSoon = open.stream()
                .filter(d -> d.getExpectedCloseDate() != null && !closeDate(d).isBefore(today)
                        && ChronoUnit.DAYS.between(today, closeDate(d)) <= 30).toList();
        List<Deal> highRisk = open.stream()
                .filter(d -> "HIGH".equalsIgnoreCase(d.getRiskLevel())).toList();
        List<Deal> unscored = open.stream().filter(d -> d.getDealScore() == null).toList();

        out.append("\nPIPELINE SUMMARY\n");
        out.append("- Open deals: ").append(open.size())
                .append(", total value ").append(rupees(openValue))
                .append(", win-probability-weighted forecast ").append(rupees(weighted)).append('\n');

        Map<String, List<Deal>> byStage = new LinkedHashMap<>();
        for (String stage : DealStages.ORDERED) {
            if (!DealStages.isClosed(stage)) {
                byStage.put(stage, new ArrayList<>());
            }
        }
        open.forEach(d -> byStage.computeIfAbsent(d.getStage(), k -> new ArrayList<>()).add(d));
        StringJoiner stages = new StringJoiner("; ");
        byStage.forEach((stage, list) -> {
            if (!list.isEmpty()) {
                stages.add(stageLabel(stage) + " " + list.size() + " (" + rupees(sum(list, Deal::getValue)) + ")");
            }
        });
        out.append("- By stage: ").append(stages.length() == 0 ? "none" : stages).append('\n');
        out.append("- High risk: ").append(refs(highRisk)).append('\n');
        out.append("- Expected to close in the next 30 days: ").append(refs(closingSoon)).append('\n');
        out.append("- Past expected close date (overdue): ").append(refs(overdue)).append('\n');
        out.append("- Not yet scored by the deal model: ").append(refs(unscored)).append('\n');

        long won = closed.stream().filter(d -> DealStages.CLOSED_WON.equals(d.getStage())).count();
        BigDecimal wonValue = sum(closed.stream().filter(d -> DealStages.CLOSED_WON.equals(d.getStage())).toList(),
                Deal::getValue);
        out.append("- Closed: ").append(won).append(" won (").append(rupees(wonValue)).append("), ")
                .append(closed.size() - won).append(" lost\n");

        // Soonest close first, undated last: the order a rep should work them in.
        List<Deal> openSorted = open.stream()
                .sorted(Comparator.comparing((Deal d) -> d.getExpectedCloseDate() == null)
                        .thenComparing(d -> d.getExpectedCloseDate() == null ? LocalDate.MAX : closeDate(d)))
                .toList();
        out.append("\nOPEN DEALS (").append(Math.min(open.size(), MAX_OPEN_DEALS)).append(" of ").append(open.size())
                .append(", soonest expected close first)\n");
        if (open.isEmpty()) {
            out.append("- none\n");
        }
        openSorted.stream().limit(MAX_OPEN_DEALS)
                .forEach(d -> out.append(dealLine(d, accounts, users, showOwners, today)).append('\n'));

        List<Deal> recentClosed = closed.stream()
                .sorted(Comparator.comparing((Deal d) -> d.getClosedAt() == null)
                        .thenComparing(Deal::getClosedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_CLOSED_DEALS)
                .toList();
        if (!recentClosed.isEmpty()) {
            out.append("\nRECENTLY CLOSED DEALS\n");
            for (Deal d : recentClosed) {
                List<String> parts = new ArrayList<>();
                parts.add(dealName(d));
                parts.add(stageLabel(d.getStage()));
                parts.add("value " + rupees(d.getValue()));
                if (d.getClosedAt() != null) {
                    parts.add("closed " + d.getClosedAt().atZoneSameInstant(ZONE).toLocalDate());
                }
                add(parts, "reason", CoachGuardrails.field(d.getClosingReason(), 120));
                out.append("- ").append(String.join(" | ", parts)).append('\n');
            }
        }
    }

    private String dealLine(Deal d, Map<Long, String> accounts, Map<Long, User> users,
                            boolean showOwners, LocalDate today) {
        List<String> parts = new ArrayList<>();
        parts.add(dealName(d));
        add(parts, "account", CoachGuardrails.field(accounts.get(d.getAccountId()), 60));
        if (showOwners) {
            User owner = d.getOwnerId() == null ? null : users.get(d.getOwnerId());
            parts.add("owner " + (owner == null || owner.getFullName() == null
                    ? "unassigned" : CoachGuardrails.field(owner.getFullName(), 40)));
        }
        parts.add("stage " + stageLabel(d.getStage()));
        parts.add("value " + rupees(d.getValue()));
        if (d.getExpectedCloseDate() != null) {
            LocalDate close = closeDate(d);
            long days = ChronoUnit.DAYS.between(today, close);
            parts.add("expected close " + close + (days < 0 ? " (OVERDUE by " + (-days) + " days)"
                    : days == 0 ? " (today)" : " (in " + days + " days)"));
        } else {
            parts.add("expected close not set");
        }
        if (d.getDealScore() != null) {
            parts.add("deal score " + Math.round(d.getDealScore())
                    + (d.getDealScoreBand() == null ? "" : " (" + CoachGuardrails.field(d.getDealScoreBand(), 20) + ")"));
        }
        Double win = winPercent(d);
        if (win != null) {
            parts.add("win probability " + Math.round(win) + "%");
        }
        add(parts, "risk", CoachGuardrails.field(d.getRiskLevel(), 20));
        add(parts, "recommended action", CoachGuardrails.field(d.getDealScoreAction(), 160));
        add(parts, "sentiment", CoachGuardrails.field(d.getCustomerSentiment(), 30));
        add(parts, "buying intent", CoachGuardrails.field(d.getBuyingIntent(), 30));
        add(parts, "budget", CoachGuardrails.field(d.getBudgetStatus(), 30));
        add(parts, "decision maker", CoachGuardrails.field(d.getDecisionMakerInvolvement(), 30));
        add(parts, "urgency", CoachGuardrails.field(d.getCustomerUrgency(), 30));
        add(parts, "objections", CoachGuardrails.field(d.getMainObjections(), 120));
        add(parts, "competitor mentioned", CoachGuardrails.field(d.getCompetitorMention(), 20));
        add(parts, "requirements", CoachGuardrails.field(d.getCustomerRequirements(), 80));
        add(parts, "risk factors", CoachGuardrails.field(d.getRiskFactors(), 80));
        add(parts, "last meeting outcome", CoachGuardrails.field(d.getMeetingOutcome(), 60));
        if (d.getTotalMeetings() != null) {
            parts.add("meetings held " + d.getTotalMeetings());
        }
        if (d.getMeetingScheduledAt() != null) {
            LocalDate meeting = d.getMeetingScheduledAt().atZoneSameInstant(ZONE).toLocalDate();
            parts.add((meeting.isBefore(today) ? "last scheduled meeting " : "next meeting ") + meeting
                    + (d.getMeetingMode() == null ? "" : " " + CoachGuardrails.field(d.getMeetingMode(), 10)));
        }
        return "- " + String.join(" | ", parts);
    }

    /* --------------------------------------------------------------- leads */

    private void appendLeads(StringBuilder out, List<Lead> leads, Map<Long, User> users, boolean showOwners,
                             Map<Long, String> dealNames) {
        // Active and converted are counted and listed separately. They are kept
        // apart so the model cannot count one opportunity twice in a total, but
        // converted leads are still named: "which leads did we convert" and "who
        // became a customer" are ordinary questions, and a coach that answered
        // them with "I don't see that" was wrong — the records were simply
        // dropped before it ever saw them.
        List<Lead> active = leads.stream().filter(l -> l.getConvertedDealId() == null).toList();
        List<Lead> converted = leads.stream().filter(l -> l.getConvertedDealId() != null).toList();

        Map<String, Long> byContact = active.stream().collect(Collectors.groupingBy(
                l -> l.getContactStatus() == null ? "UNKNOWN" : l.getContactStatus(), LinkedHashMap::new, Collectors.counting()));
        long hot = active.stream().filter(l -> "Hot".equalsIgnoreCase(l.getAiScoreLabel())).count();
        long qualified = active.stream().filter(l -> "QUALIFIED".equalsIgnoreCase(l.getQualificationStatus())).count();

        out.append("\nLEADS SUMMARY\n");
        out.append("- Active (not yet converted) leads: ").append(active.size())
                .append(", of which AI-rated Hot: ").append(hot)
                .append(", qualified: ").append(qualified).append('\n');
        out.append("- Converted to deals: ").append(converted.size())
                .append(converted.isEmpty() ? "" : " (named under CONVERTED LEADS below)")
                .append('\n');
        out.append("- By contact status: ").append(byContact.isEmpty() ? "none" : byContact.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining("; "))).append('\n');
        if (showOwners) {
            long unassigned = active.stream().filter(l -> l.getAssignedToId() == null).count();
            out.append("- Unassigned: ").append(unassigned).append('\n');
        }

        List<Lead> top = active.stream()
                .sorted(Comparator.comparing(Lead::getAiScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_LEADS)
                .toList();
        out.append("\nLEADS (").append(top.size()).append(" of ").append(active.size())
                .append(", highest AI score first)\n");
        if (top.isEmpty()) {
            out.append("- none\n");
        }
        for (Lead l : top) {
            List<String> parts = new ArrayList<>();
            parts.add("Lead #" + l.getId() + " " + CoachGuardrails.field(l.getFullName(), 60));
            add(parts, "company", CoachGuardrails.field(l.getCompany(), 60));
            if (showOwners) {
                User owner = l.getAssignedToId() == null ? null : users.get(l.getAssignedToId());
                parts.add("assigned to " + (owner == null || owner.getFullName() == null
                        ? "nobody" : CoachGuardrails.field(owner.getFullName(), 40)));
            }
            add(parts, "product", CoachGuardrails.field(l.getProduct(), 60));
            if (l.getAiScore() != null) {
                parts.add("AI score " + l.getAiScore()
                        + (l.getAiScoreLabel() == null ? "" : " (" + CoachGuardrails.field(l.getAiScoreLabel(), 10) + ")"));
            }
            add(parts, "qualification", CoachGuardrails.field(l.getQualificationStatus(), 20));
            add(parts, "contact status", CoachGuardrails.field(l.getContactStatus(), 30));
            if (l.getEstimatedDealValue() != null) {
                parts.add("estimated value " + rupees(l.getEstimatedDealValue()));
            }
            add(parts, "purchase timeline", CoachGuardrails.field(l.getPurchaseTimeline(), 30));
            add(parts, "priority", CoachGuardrails.field(l.getLeadPriority(), 20));
            add(parts, "industry", CoachGuardrails.field(l.getIndustry(), 40));
            out.append("- ").append(String.join(" | ", parts)).append('\n');
        }

        appendConvertedLeads(out, converted, users, showOwners, dealNames);
    }

    /**
     * Leads that became deals, most recently converted first, each named with the
     * deal it became. These are won-the-handoff records, not pipeline: their value
     * is already counted in PIPELINE SUMMARY, so nothing here is summed.
     */
    private void appendConvertedLeads(StringBuilder out, List<Lead> converted, Map<Long, User> users,
                                      boolean showOwners, Map<Long, String> dealNames) {
        if (converted.isEmpty()) {
            return;
        }
        List<Lead> recent = converted.stream()
                .sorted(Comparator.comparing(Lead::getConvertedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_CONVERTED_LEADS)
                .toList();
        out.append("\nCONVERTED LEADS (").append(recent.size()).append(" of ").append(converted.size())
                .append(", most recently converted first — these leads are now deals)\n");
        for (Lead l : recent) {
            List<String> parts = new ArrayList<>();
            parts.add("Lead #" + l.getId() + " " + CoachGuardrails.field(l.getFullName(), 60));
            add(parts, "company", CoachGuardrails.field(l.getCompany(), 60));
            // The deal's own name when the caller can see it, its reference when
            // the deal sits outside their scope.
            parts.add("converted to " + dealNames.getOrDefault(l.getConvertedDealId(),
                    DealStages.opportunityReference(l.getConvertedDealId())));
            if (l.getConvertedAt() != null) {
                parts.add("converted on " + l.getConvertedAt().atZoneSameInstant(ZONE).toLocalDate());
            }
            if (showOwners) {
                User owner = l.getAssignedToId() == null ? null : users.get(l.getAssignedToId());
                parts.add("converted by " + (owner == null || owner.getFullName() == null
                        ? "nobody recorded" : CoachGuardrails.field(owner.getFullName(), 40)));
            }
            add(parts, "product", CoachGuardrails.field(l.getProduct(), 60));
            if (l.getAiScore() != null) {
                parts.add("AI score at conversion " + l.getAiScore());
            }
            add(parts, "source", CoachGuardrails.field(l.getSourceChannel(), 40));
            add(parts, "industry", CoachGuardrails.field(l.getIndustry(), 40));
            out.append("- ").append(String.join(" | ", parts)).append('\n');
        }
    }

    /* ------------------------------------------------------------- helpers */

    private static boolean can(AuthenticatedUser caller, String permission) {
        if (caller.role() == Role.ADMIN || caller.role() == Role.MANAGER) {
            return true;
        }
        List<String> granted = caller.permissions() == null || caller.permissions().isEmpty()
                ? PermissionDefaults.forRole(caller.role())
                : caller.permissions();
        return granted.contains(permission);
    }

    static String describeScope(boolean ownOnly, boolean pipeline, boolean leads) {
        if (!pipeline && !leads) {
            return "no deal or lead records (this role has no pipeline or lead access)";
        }
        String what = pipeline && leads ? "deals and leads" : pipeline ? "deals" : "leads";
        return ownOnly
                ? "only the " + what + " owned by or assigned to this user"
                : "all " + what + " in this user's organization";
    }

    static String roleLabel(Role role) {
        return switch (role) {
            case SALES_REP -> "sales representative";
            case MANAGER -> "sales manager";
            case ADMIN -> "CRM administrator";
            case SUPPORT_AGENT -> "support agent";
            case MARKETING -> "marketing team member";
        };
    }

    private static Double winPercent(Deal d) {
        if (d.getWinProbability() != null) {
            return d.getWinProbability();
        }
        return d.getProbability() == null ? null : d.getProbability().doubleValue();
    }

    private static LocalDate closeDate(Deal d) {
        return d.getExpectedCloseDate().atZoneSameInstant(ZONE).toLocalDate();
    }

    private static String dealName(Deal d) {
        String ref = d.getOpportunityId() != null ? d.getOpportunityId() : DealStages.opportunityReference(d.getId());
        String name = CoachGuardrails.field(d.getName(), 80);
        return (name == null ? "Untitled deal" : name) + " (" + ref + ")";
    }

    private static String stageLabel(String stage) {
        return STAGE_LABELS.getOrDefault(stage, stage);
    }

    private static String refs(List<Deal> deals) {
        if (deals.isEmpty()) {
            return "none";
        }
        return deals.size() + " — " + deals.stream().limit(10).map(CoachContextService::dealName)
                .collect(Collectors.joining(", ")) + (deals.size() > 10 ? ", …" : "");
    }

    private static void add(List<String> parts, String label, String value) {
        if (value != null) {
            parts.add(label + " " + value);
        }
    }

    private static BigDecimal sum(List<Deal> deals, Function<Deal, BigDecimal> value) {
        return deals.stream().map(value).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** ₹12,34,567 — Indian digit grouping, whole rupees, as the UI shows it. */
    static String rupees(BigDecimal amount) {
        if (amount == null) {
            return "not set";
        }
        BigDecimal whole = amount.setScale(0, RoundingMode.HALF_UP);
        String sign = whole.signum() < 0 ? "-" : "";
        String digits = whole.abs().toPlainString();
        if (digits.length() <= 3) {
            return sign + "₹" + digits;
        }
        String head = digits.substring(0, digits.length() - 3);
        String tail = digits.substring(digits.length() - 3);
        StringBuilder grouped = new StringBuilder();
        while (head.length() > 2) {
            grouped.insert(0, "," + head.substring(head.length() - 2));
            head = head.substring(0, head.length() - 2);
        }
        grouped.insert(0, head);
        return sign + "₹" + grouped + "," + tail;
    }
}
