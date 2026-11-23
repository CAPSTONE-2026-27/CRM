package com.techcrm.crm.proposal;

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

class ProposalStatusTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** The index is created by one migration and may be redefined by a later
     *  one, so the whole directory is scanned and the newest definition wins. */
    private static final Pattern LIVE_INDEX = Pattern.compile(
            "CREATE UNIQUE INDEX uq_proposals_live_per_deal.*?WHERE status IN \\(([^)]*)\\)",
            Pattern.DOTALL);

    @Test
    void terminalStatusesAreTheOnesNoWebhookCanMove() {
        assertThat(ProposalStatus.SIGNED.isTerminal()).isTrue();
        assertThat(ProposalStatus.REJECTED.isTerminal()).isTrue();
        assertThat(ProposalStatus.SUPERSEDED.isTerminal()).isTrue();

        assertThat(ProposalStatus.GENERATING.isTerminal()).isFalse();
        assertThat(ProposalStatus.GENERATED.isTerminal()).isFalse();
        assertThat(ProposalStatus.SENT_FOR_SIGNATURE.isTerminal()).isFalse();
        assertThat(ProposalStatus.VIEWED.isTerminal()).isFalse();
        assertThat(ProposalStatus.FAILED.isTerminal()).isFalse();
    }

    /** FAILED, REJECTED and SUPERSEDED release the deal's slot, which is what
     *  lets a rep fix the data and try again without an admin deleting a row. */
    @Test
    void aFailedOrFinishedProposalDoesNotBlockTheDeal() {
        assertThat(ProposalStatus.FAILED.isLive()).isFalse();
        assertThat(ProposalStatus.REJECTED.isLive()).isFalse();
        assertThat(ProposalStatus.SUPERSEDED.isLive()).isFalse();

        assertThat(ProposalStatus.GENERATED.isLive()).isTrue();
        assertThat(ProposalStatus.SIGNED.isLive()).isTrue();
    }

    /**
     * A render in flight must hold the deal's slot.
     *
     * Generation takes tens of seconds, and the whole point of the live-proposal
     * index is that two retries arriving together cannot both produce a
     * quotation for one opportunity. If GENERATING did not count as live, the
     * second retry would sail past the index during exactly the window the index
     * exists to cover.
     */
    @Test
    void aProposalStillBeingRenderedHoldsTheDealsSlot() {
        assertThat(ProposalStatus.GENERATING.isLive()).isTrue();
        assertThat(ProposalStatus.GENERATING.isTerminal()).isFalse();
    }

    /**
     * The Java view of "live" and the partial unique index have to agree.
     *
     * They are two independent statements of the same rule — one decides whether
     * to return the existing proposal, the other decides whether the insert is
     * allowed — and a drift between them turns a clean "here is your existing
     * proposal" into an opaque constraint violation.
     */
    @Test
    void theLiveSetMatchesTheDatabasePartialIndex() throws Exception {
        Set<String> inIndex = new TreeSet<>(Arrays.asList(
                latestLiveIndexDefinition().replace("'", "").replaceAll("\\s+", "").split(",")));

        Set<String> inCode = new TreeSet<>(ProposalStatus.LIVE.stream().map(Enum::name).toList());

        assertThat(inIndex).isEqualTo(inCode);
    }

    /** @return the status list from the newest migration that defines the index. */
    private String latestLiveIndexDefinition() throws Exception {
        List<Path> migrations;
        try (var files = Files.list(MIGRATIONS)) {
            migrations = files
                    .filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(Comparator.comparingInt(ProposalStatusTest::version))
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
                .as("no migration in %s defines uq_proposals_live_per_deal", MIGRATIONS)
                .isNotNull();
        return latest;
    }

    private static int version(Path migration) {
        String name = migration.getFileName().toString();
        return Integer.parseInt(name.substring(1, name.indexOf("__")));
    }

    /**
     * Storing the enum by name means renaming a constant silently orphans every
     * row that already holds the old spelling. The column is VARCHAR(30) here
     * rather than the contracts table's 20, because SENT_FOR_SIGNATURE is 18
     * characters and the review statuses this workflow will grow later would sit
     * uncomfortably close to a 20-char limit.
     */
    @Test
    void everyStatusFitsTheColumnItIsStoredIn() {
        for (ProposalStatus status : ProposalStatus.values()) {
            assertThat(status.name().length())
                    .as("%s must fit proposals.status VARCHAR(30)", status)
                    .isLessThanOrEqualTo(30);
        }
    }

    @Test
    void everyProposalTypeFitsItsColumnAndNamesATemplate() {
        for (ProposalType type : ProposalType.values()) {
            assertThat(type.name().length())
                    .as("%s must fit proposals.proposal_type VARCHAR(40)", type)
                    .isLessThanOrEqualTo(40);
            assertThat(type.defaultTemplateFile()).endsWith(".docx");
            assertThat(type.displayName()).isNotBlank();
        }
    }

    /** The proposal reference is derived from the id the same way the contract
     *  and opportunity references are, so all three sort the same. */
    @Test
    void theProposalNumberIsZeroPaddedSoReferencesSort() {
        assertThat(ProposalRecordService.proposalNumber(42L)).isEqualTo("PRP-000042");
        assertThat(ProposalRecordService.proposalNumber(7L)).isEqualTo("PRP-000007");
        assertThat(ProposalRecordService.proposalNumber(1234567L)).isEqualTo("PRP-1234567");
    }
}
