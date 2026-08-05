package com.techcrm.crm.dealflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** Deal flow step 10 — a sales manager's verdict on the model's recommendation. */
@Entity
@Table(name = "deal_manager_reviews")
@Getter
@Setter
public class ManagerReview {

    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String OVERRIDDEN = "OVERRIDDEN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    @Column(name = "prediction_id")
    private Long predictionId;

    @Column(nullable = false, length = 20)
    private String decision;

    /**
     * What the model recommended at the moment of review, frozen.
     *
     * The recommendation is derived from the score and the score can be
     * recomputed — without this snapshot, a retrain would silently rewrite what
     * the manager actually signed off on.
     */
    @Column(name = "recommended_action", length = 200)
    private String recommendedAction;

    @Column(name = "overridden_action", length = 200)
    private String overriddenAction;

    @Column(columnDefinition = "text")
    private String comments;

    @Column(name = "reviewed_by_id")
    private Long reviewedById;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
