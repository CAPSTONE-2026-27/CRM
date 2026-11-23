package com.techcrm.crm.proposal;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One priced line of a proposal, frozen at generation time.
 *
 * This is not a product catalogue and does not try to become one. The CRM has no
 * product entity, so a line is resolved from the originating lead
 * ({@code leads.product} / {@code product_quantity}) or from the deal's own
 * value, and then stored. Re-deriving it when the document is reprinted would
 * let a later edit to that lead silently change what a customer was quoted.
 */
@Entity
@Table(name = "proposal_line_items")
@Getter
@Setter
public class ProposalLineItem {

    /** Where a line came from, so a reviewer can tell a real product line from
     *  the single-line fallback. */
    public enum Source {
        /** {@code leads.product} + {@code leads.product_quantity}. */
        LEAD_PRODUCT,
        /** No product anywhere on the opportunity; one line for the deal value. */
        DEAL_VALUE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "proposal_id", nullable = false)
    private Long proposalId;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(nullable = false, length = 300)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Source source;
}
