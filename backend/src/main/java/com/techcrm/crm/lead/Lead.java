package com.techcrm.crm.lead;

import com.techcrm.crm.deal.DealStages;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "leads")
@Getter
@Setter
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    private String company;
    private String industry;

    @Column(name = "employee_count")
    private String employeeCount;

    private String email;
    private String phone;
    private String product;

    @Column(name = "estimated_deal_value")
    private BigDecimal estimatedDealValue;

    /* ---- Lead-scoring factors (see V17) -----------------------------------
     * The fine-tuned model scores five factors; these are the two the CRM did
     * not previously record. Without them a lead could not score above 60/100.
     * Both are optional — a lead is still worth capturing when the rep does not
     * know them yet, and the model handles their absence. */

    /** Units the lead intends to buy. */
    @Column(name = "product_quantity")
    private Integer productQuantity;

    /** When the lead intends to buy. Constrained by the DB to the six strings
     *  the model was trained on — see LeadRequest.PURCHASE_TIMELINES. Any other
     *  spelling scores zero for urgency instead of failing, so it is enforced
     *  rather than trusted. */
    @Column(name = "purchase_timeline", length = 30)
    private String purchaseTimeline;

    /* ---- Qualification-meeting context (see V18) ---------------------------
     * Carried on the lead so a meeting reading has the same business context a
     * sales director would have. All nullable: existing leads predate them, and
     * a lead is still worth working without them. */

    @Column(name = "company_location", length = 150)
    private String companyLocation;

    /** New Business / Existing Customer / Renewal / Upsell / Partner.
     *  Constrained by leads_customer_type_allowed. */
    @Column(name = "customer_type", length = 30)
    private String customerType;

    /** The band of the most recent meeting score — High/Medium/Low/Very Low
     *  Priority. Written only by LeadScoreService, never from a request. */
    @Column(name = "lead_priority", length = 20)
    private String leadPriority;

    @Column(name = "source_channel")
    private String sourceChannel;

    @Column(name = "capture_method")
    private String captureMethod;

    @Column(length = 1000)
    private String notes;

    @Column(name = "ai_score")
    private Integer aiScore;

    @Column(name = "ai_score_label")
    private String aiScoreLabel;

    @Column(name = "ai_score_reason", length = 1000)
    private String aiScoreReason;

    @Column(nullable = false, length = 30)
    private String status = "NEW";

    /* ---- Qualification (flow step 3) --------------------------------------
     * Separate from `status`, which is the Hot/Warm/Cold temperature. These two
     * answer different questions: whether to work the lead at all, and how
     * urgently. PENDING | QUALIFIED | UNQUALIFIED. */

    @Column(name = "qualification_status", nullable = false, length = 20)
    private String qualificationStatus = "PENDING";

    @Column(name = "qualification_probability")
    private Double qualificationProbability;

    @Column(name = "qualification_reasoning", columnDefinition = "text")
    private String qualificationReasoning;

    @Column(name = "assigned_to_id")
    private Long assignedToId;

    /* ---- Assignment (flow step 4) ---- */

    @Column(name = "assigned_at")
    private OffsetDateTime assignedAt;

    /** UNASSIGNED | ASSIGNED */
    @Column(name = "assignment_status", nullable = false, length = 20)
    private String assignmentStatus = "UNASSIGNED";

    /* ---- First contact (flow step 5) ----
     * NOT_CONTACTED | MEETING_SCHEDULED | NO_RESPONSE | INTERESTED | NOT_INTERESTED */

    @Column(name = "contact_status", nullable = false, length = 30)
    private String contactStatus = "NOT_CONTACTED";

    @Column(name = "contact_status_updated_at")
    private OffsetDateTime contactStatusUpdatedAt;

    @Column(name = "contact_notes", columnDefinition = "text")
    private String contactNotes;

    /** Copied to the account on conversion when the account has none; a
     *  proposal or contract cannot be generated without an address (see V31). */
    @Column(name = "billing_address", length = 500)
    private String billingAddress;

    /* ---- Pipeline board placement (see V30) ----
     * Which of the board's lead columns this open lead sits in:
     * PROSPECTING | QUALIFICATION | MEETING_SCHEDULED. Moving past these means
     * converting it, at which point the deal's own stage takes over. */

    @Column(name = "pipeline_stage", nullable = false, length = 30)
    private String pipelineStage = DealStages.PROSPECTING;

    /* ---- Conversion (flow step 6) ----
     * Set once and never cleared: a converted lead keeps pointing at the deal
     * it produced, which is what makes the opportunity traceable to its origin. */

    @Column(name = "converted_deal_id")
    private Long convertedDealId;

    @Column(name = "converted_at")
    private OffsetDateTime convertedAt;

    @Column(name = "organization_id")
    private Long organizationId;

    @Column(name = "created_by")
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}