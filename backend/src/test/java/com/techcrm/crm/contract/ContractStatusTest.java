package com.techcrm.crm.contract;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ContractStatusTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** The index is created by one migration and may be redefined by a later
     *  one, so the whole directory is scanned and the newest definition wins. */
    private static final Pattern LIVE_INDEX = Pattern.compile(
            "CREATE UNIQUE INDEX uq_contracts_live_per_deal.*?WHERE status IN \\(([^)]*)\\)",
            Pattern.DOTALL);

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
     * A render in flight must hold the deal's slot.
     *
     * Generation takes tens of seconds, and the whole point of the live-contract
     * index is that two retries arriving together cannot both produce an
     * agreement for one opportunity. If DRAFTING did not count as live, the
     * second retry would sail past the index during exactly the window the index
     * exists to cover.
     */
    @Test
    void aContractStillBeingRenderedHoldsTheDealsSlot() {
        assertThat(ContractStatus.DRAFTING.isLive()).isTrue();
        assertThat(ContractStatus.DRAFTING.isTerminal()).isFalse();
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
        Set<String> inIndex = new TreeSet<>(Arrays.asList(
                latestLiveIndexDefinition().replace("'", "").replaceAll("\\s+", "").split(",")));

        Set<String> inCode = new TreeSet<>(ContractStatus.LIVE.stream().map(Enum::name).toList());

        assertThat(inIndex).isEqualTo(inCode);
    }

    /** @return the status list from the newest migration that defines the index. */
    private String latestLiveIndexDefinition() throws Exception {
        List<Path> migrations;
        try (var files = Files.list(MIGRATIONS)) {
            migrations = files
                    .filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(ContractStatusTest::version))
                    .toList();
        }

        String latest = null;
        for (Path migration : migrations) {
            Matcher matcher = LIVE_INDEX.matcher(Files.readString(migration, StandardCharsets.UTF_8));
            while (matcher.find()) {
                latest = matcher.group(1);
            }
        }

        assertThat(latest)
                .as("no migration in %s defines uq_contracts_live_per_deal", MIGRATIONS)
                .isNotNull();
        return latest;
    }

    private static int version(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
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
