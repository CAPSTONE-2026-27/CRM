package com.techcrm.crm.contract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ContractStatusTest {

    private static final Path MIGRATION =
            Path.of("src/main/resources/db/migration/V19__contracts.sql");

    @Test
    void terminalStatusesAreTheOnesNoWebhookCanMove() {
        assertThat(ContractStatus.SIGNED.isTerminal()).isTrue();
        assertThat(ContractStatus.REJECTED.isTerminal()).isTrue();
        assertThat(ContractStatus.SUPERSEDED.isTerminal()).isTrue();

        assertThat(ContractStatus.GENERATED.isTerminal()).isFalse();
        assertThat(ContractStatus.SENT.isTerminal()).isFalse();
        assertThat(ContractStatus.VIEWED.isTerminal()).isFalse();
        assertThat(ContractStatus.FAILED.isTerminal()).isFalse();
    }

    /** FAILED, REJECTED and SUPERSEDED release the deal's slot, which is what
     *  lets a rep fix the data and try again without an admin deleting a row. */
    @Test
    void aFailedOrFinishedContractDoesNotBlockTheDeal() {
        assertThat(ContractStatus.FAILED.isLive()).isFalse();
        assertThat(ContractStatus.REJECTED.isLive()).isFalse();
        assertThat(ContractStatus.SUPERSEDED.isLive()).isFalse();

        assertThat(ContractStatus.GENERATED.isLive()).isTrue();
        assertThat(ContractStatus.SIGNED.isLive()).isTrue();
    }

    /**
     * The Java view of "live" and the partial unique index have to agree.
     *
     * They are two independent statements of the same rule — one decides whether
     * to return the existing contract, the other decides whether the insert is
     * allowed — and a drift between them turns a clean "here is your existing
     * contract" into an opaque constraint violation. Cheaper to catch here than
     * in production, so the index is parsed out of the migration and compared.
     */
    @Test
    void theLiveSetMatchesTheDatabasePartialIndex() throws Exception {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);

        Matcher matcher = Pattern.compile(
                        "CREATE UNIQUE INDEX uq_contracts_live_per_deal.*?WHERE status IN \\(([^)]*)\\)",
                        Pattern.DOTALL)
                .matcher(sql);

        assertThat(matcher.find())
                .as("uq_contracts_live_per_deal not found in %s", MIGRATION)
                .isTrue();

        Set<String> inIndex = new LinkedHashSet<>(Arrays.asList(
                matcher.group(1).replace("'", "").replaceAll("\\s+", "").split(",")));

        Set<String> inCode = new LinkedHashSet<>(
                ContractStatus.LIVE.stream().map(Enum::name).sorted().toList());

        assertThat(new LinkedHashSet<>(inIndex.stream().sorted().toList())).isEqualTo(inCode);
    }

    /** Storing the enum by name means renaming a constant silently orphans every
     *  row that already holds the old spelling. */
    @Test
    void everyStatusFitsTheColumnItIsStoredIn() {
        for (ContractStatus status : ContractStatus.values()) {
            assertThat(status.name().length())
                    .as("%s must fit contracts.status VARCHAR(20)", status)
                    .isLessThanOrEqualTo(20);
        }
    }

    @Test
    void everyContractTypeFitsItsColumnAndNamesATemplate() {
        for (ContractType type : ContractType.values()) {
            assertThat(type.name().length())
                    .as("%s must fit contracts.contract_type VARCHAR(40)", type)
                    .isLessThanOrEqualTo(40);
            assertThat(type.defaultTemplateFile()).endsWith(".docx");
            assertThat(type.displayName()).isNotBlank();
        }
    }
}
