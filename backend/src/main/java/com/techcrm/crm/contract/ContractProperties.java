package com.techcrm.crm.contract;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything about contract generation that differs between environments.
 *
 * Grouped as {@code @ConfigurationProperties} rather than the {@code @Value}
 * constructor injection used by {@link com.techcrm.crm.deal.DealScoringClient}:
 * that client has two settings, this has four groups of them, and threading
 * fifteen {@code @Value} parameters through three services would be worse than
 * one bound object.
 */
@Component
@ConfigurationProperties(prefix = "contract")
@Getter
@Setter
public class ContractProperties {

    /**
     * Deal stages a contract may be generated from.
     *
     * The workflow this implements is triggered by "Proposal Accepted", which is
     * not a stage this CRM has — {@link com.techcrm.crm.deal.DealStages} is a
     * fixed eight-value vocabulary shared with the frontend pipeline board, and
     * adding to it would re-bucket every existing deal. These three are the
     * stages that mean the same thing here. Configurable so a team that works
     * its pipeline differently does not need a code change.
     */
    private List<String> eligibleStages = List.of("PROPOSAL", "NEGOTIATION", "CLOSED_WON");

    /** Term length when the caller does not supply an end date. */
    private int defaultTermMonths = 12;

    /** Neither is recorded anywhere in the CRM, so they default from here and
     *  can be overridden per request. */
    private String defaultPaymentTerms =
            "50% on execution of this agreement, 50% on delivery. Invoices are payable within 30 days.";
    private String defaultDeliveryTimeline =
            "Within 30 days of the contract start date.";

    /** At or above this deal value, a product sale is written as an enterprise
     *  subscription instead. In the deal's own currency — the CRM does not
     *  convert, and defaults to INR. */
    private BigDecimal enterpriseValueThreshold = new BigDecimal("2500000");

    private Templates templates = new Templates();
    private Storage storage = new Storage();
    private LibreOffice libreoffice = new LibreOffice();

    @Getter
    @Setter
    public static class Templates {
        /** Where the .docx templates live. A {@code classpath:} location uses the
         *  bundled set; a filesystem location lets an admin drop in their own
         *  without a rebuild. */
        private String location = "classpath:contract-templates/";

        /** Per-type overrides, keyed by {@link ContractType} name. A value here
         *  replaces the type's default file name, and may be an absolute path or
         *  any Spring resource location. */
        private Map<String, String> overrides = new LinkedHashMap<>();
    }

    @Getter
    @Setter
    public static class Storage {
        /** Root of the generated-document store. Relative paths in the contracts
         *  table are resolved against this, so moving the store is a config
         *  change rather than a data migration. */
        private String root = "./var/contracts";
    }

    @Getter
    @Setter
    public static class LibreOffice {
        /** Turn off only where no LibreOffice is available — CI, or a developer
         *  machine that has not installed it. Contracts then generate DOCX only,
         *  and the PDF endpoints return 404 rather than a stale file. */
        private boolean enabled = true;

        /** Executable to run. A bare name is resolved on PATH, which is what
         *  makes this work unchanged on Linux ({@code soffice}), macOS and
         *  Windows ({@code soffice.exe}, or the full Program Files path). */
        private String path = "soffice";

        /** A cold LibreOffice start is slow; a hung one must not hold the request
         *  thread forever. */
        private int timeoutSeconds = 120;
    }
}
