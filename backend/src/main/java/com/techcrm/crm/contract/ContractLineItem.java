package com.techcrm.crm.contract;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One line of a contract's schedule, frozen at generation time.
 *
 * The CRM has no product catalogue, so these are resolved from whatever the
 * opportunity actually carries — see {@code ContractLineItemResolver} — and then
 * stored. Re-deriving them on each print would let a later edit to the
 * originating lead silently rewrite a signed contract.
 */
@Entity
@Table(name = "contract_line_items")
@Getter
@Setter
public class ContractLineItem {

    /** Where a line came from, so a reviewer can tell a real product line from
     *  the single-line fallback the deal value produces. */
    public enum Source {
        /** leads.product / leads.product_quantity on the deal's originating lead. */
        LEAD_PRODUCT,
        /** No product recorded anywhere — one line for the deal's own value. */
        DEAL_VALUE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

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
