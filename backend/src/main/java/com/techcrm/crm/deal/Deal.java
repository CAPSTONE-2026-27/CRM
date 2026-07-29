package com.techcrm.crm.deal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "deals")
@Getter
@Setter
public class Deal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private String name;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private BigDecimal value;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @Column(name = "expected_close_date")
    private OffsetDateTime expectedCloseDate;

    /** PROSPECTING | QUALIFICATION | PROPOSAL | NEGOTIATION | CLOSED_WON | CLOSED_LOST */
    @Column(nullable = false, length = 30)
    private String stage = "PROSPECTING";

    /** 0-100 */
    private Integer probability;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "forecast_category")
    private String forecastCategory;

    @Column(name = "weighted_forecast_value")
    private BigDecimal weightedForecastValue;

    @Column(name = "best_case_value")
    private BigDecimal bestCaseValue;

    @Column(name = "auto_generate_proposal", nullable = false)
    private boolean autoGenerateProposal = false;

    @Column(name = "push_to_erp_on_close", nullable = false)
    private boolean pushToErpOnClose = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
