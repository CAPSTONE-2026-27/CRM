package com.techcrm.crm.dealflow;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * Deal flow steps 5-6 — the language model's reading of one meeting output.
 *
 * The raw reply is kept verbatim. When an extraction looks wrong, the only way
 * to tell a bad prompt from a bad parse is to read what the model actually
 * returned, and by then the call is long gone.
 */
@Entity
@Table(name = "deal_analyses")
@Getter
@Setter
public class DealAnalysis {

    /** The model answered and its JSON parsed. */
    public static final String SUCCEEDED = "SUCCEEDED";
    /** The model was unreachable or unusable; parameters came from the
     *  rule-based fallback, so the score rests on weaker evidence. */
    public static final String DEGRADED = "DEGRADED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(name = "deal_id", nullable = false)
    private Long dealId;

    @Column(name = "opportunity_id", length = 30)
    private String opportunityId;

    @Column(name = "lead_id")
    private Long leadId;

    @Column(name = "meeting_output_id", nullable = false)
    private Long meetingOutputId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "model_version", length = 100)
    private String modelVersion;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
