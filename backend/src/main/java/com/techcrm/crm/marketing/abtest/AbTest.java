package com.techcrm.crm.marketing.abtest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "mkt_ab_tests")
@Getter
@Setter
public class AbTest {

    /**
     * What differs between email variant A and B. EMAIL_STRUCTURE varies the
     * layout and order of the same content (greeting, body, offer, CTA) rather
     * than one field.
     */
    public enum Element { EMAIL_SUBJECT, PREVIEW_TEXT, EMAIL_BODY, CTA, EMAIL_STRUCTURE }

    public enum Metric { OPEN_RATE, CLICK_RATE, CONVERSION_RATE, RESPONSE_RATE }

    /**
     * MANUAL: the Marketing Head picks the variant after reviewing the measured
     * results. HIGHEST_PRIMARY_METRIC: once the test window closes and both
     * variants have reached the minimum sample, the variant with the higher
     * measured primary metric is selected. There is no model judgment in
     * either path — only counted events.
     */
    public enum DecisionRule { MANUAL, HIGHEST_PRIMARY_METRIC }

    public enum Status { CONFIGURED, RUNNING, AWAITING_DECISION, DECIDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false, unique = true, updatable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Boolean enabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "test_element", nullable = false, length = 20)
    private Element testElement = Element.EMAIL_SUBJECT;

    @Column(name = "split_a_pct", nullable = false)
    private Integer splitAPct = 50;

    @Column(name = "test_audience_pct", nullable = false)
    private Integer testAudiencePct = 100;

    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours = 72;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_metric", nullable = false, length = 20)
    private Metric primaryMetric = Metric.OPEN_RATE;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_rule", nullable = false, length = 30)
    private DecisionRule decisionRule = DecisionRule.MANUAL;

    @Column(name = "min_sample_per_variant", nullable = false)
    private Integer minSamplePerVariant = 100;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.CONFIGURED;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "ends_at")
    private OffsetDateTime endsAt;

    @Column(name = "winner_variant", length = 1)
    private String winnerVariant;

    @Column(name = "decided_by")
    private Long decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;
}
