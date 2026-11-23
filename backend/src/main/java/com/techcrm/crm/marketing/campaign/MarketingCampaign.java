package com.techcrm.crm.marketing.campaign;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/*
 * @DynamicUpdate: pipeline workers write their progress columns with targeted
 * UPDATEs while a person may be editing the campaign. Writing only the columns
 * that changed keeps a user's save from reverting a worker's status, and a
 * worker's update from reverting a user's edit.
 */
@Entity
@Table(name = "mkt_campaigns")
@DynamicUpdate
@Getter
@Setter
public class MarketingCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private Long organizationId;

    @Column(nullable = false, length = 160)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false, length = 40)
    private CampaignType campaignType;

    @Column(length = 300)
    private String objective;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CampaignStatus status = CampaignStatus.DRAFT;

    /* ---- Product / offer ---- */

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "product_description", columnDefinition = "text")
    private String productDescription;

    @Column(name = "main_benefits", columnDefinition = "text")
    private String mainBenefits;

    @Column(columnDefinition = "text")
    private String offer;

    @Column(length = 120)
    private String discount;

    @Column(name = "pricing_info", columnDefinition = "text")
    private String pricingInfo;

    @Column(name = "selling_points", columnDefinition = "text")
    private String sellingPoints;

    @Column(name = "call_to_action", length = 200)
    private String callToAction;

    /** Where the email's call-to-action links (unless the email sets its own CTA link). */
    @Column(name = "landing_page_url", length = 500)
    private String landingPageUrl;

    /* ---- Audience, content configuration, rules ---- */

    @Column(name = "audience_json", nullable = false, columnDefinition = "text")
    private String audienceJson = "{}";

    @Column(name = "content_config_json", nullable = false, columnDefinition = "text")
    private String contentConfigJson = "{}";

    /** ALL | ANY: how the criteria rows combine. */
    @Column(name = "rule_match", nullable = false, length = 3)
    private String ruleMatch = "ALL";

    /* ---- Strategy ---- */

    @Column(name = "strategy_json", nullable = false, columnDefinition = "text")
    private String strategyJson = "{}";

    @Column(name = "scheduled_at")
    private OffsetDateTime scheduledAt;

    /* ---- Pipelines ---- */

    @Enumerated(EnumType.STRING)
    @Column(name = "lead_pipeline_status", nullable = false, length = 20)
    private PipelineStatus leadPipelineStatus = PipelineStatus.IDLE;

    @Column(name = "lead_pipeline_done", nullable = false)
    private Integer leadPipelineDone = 0;

    @Column(name = "lead_pipeline_total", nullable = false)
    private Integer leadPipelineTotal = 0;

    @Column(name = "lead_pipeline_error", length = 500)
    private String leadPipelineError;

    @Column(name = "lead_pipeline_run", nullable = false)
    private Integer leadPipelineRun = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_pipeline_status", nullable = false, length = 20)
    private PipelineStatus contentPipelineStatus = PipelineStatus.IDLE;

    @Column(name = "content_pipeline_error", length = 500)
    private String contentPipelineError;

    @Column(name = "content_pipeline_run", nullable = false)
    private Integer contentPipelineRun = 0;

    /* ---- Lifecycle ---- */

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "launched_at")
    private OffsetDateTime launchedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
