package com.techcrm.crm.marketing.campaign;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** One eligibility rule from Section E, e.g. COMPANY_SIZE GT 100. */
@Entity
@Table(name = "mkt_campaign_criteria")
@Getter
@Setter
public class CampaignCriterion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(nullable = false)
    private Integer position;

    @Column(nullable = false, length = 40)
    private String field;

    @Column(nullable = false, length = 12)
    private String operator;

    @Column(nullable = false, length = 300)
    private String value;
}
