package com.techcrm.crm.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The reporting window: previous period, bucket size and zero-fill buckets. */
class AnalyticsFilterTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static AnalyticsFilter window(String from, String to) {
        return AnalyticsFilter.of(1L, null, LocalDate.parse(from), LocalDate.parse(to), null, null, null, IST);
    }

    @Test
    void previousPeriodIsTheEquallyLongWindowImmediatelyBefore() {
        AnalyticsFilter f = window("2026-09-01", "2026-09-30");
        assertThat(f.previousFrom()).isEqualTo(LocalDate.parse("2026-08-02"));
        assertThat(f.previousTo()).isEqualTo(LocalDate.parse("2026-08-31"));
        assertThat(f.previousEnd()).isEqualTo(f.currentStart());
    }

    @Test
    void windowIsHalfOpenInTheReportingZone() {
        AnalyticsFilter f = window("2026-09-01", "2026-09-01");
        assertThat(f.currentStart().toString()).isEqualTo("2026-09-01T00:00+05:30");
        assertThat(f.currentEnd().toString()).isEqualTo("2026-09-02T00:00+05:30");
    }

    @Test
    void granularityGrowsWithTheRange() {
        assertThat(window("2026-09-01", "2026-09-30").granularity()).isEqualTo(AnalyticsFilter.Granularity.DAY);
        assertThat(window("2026-07-01", "2026-09-30").granularity()).isEqualTo(AnalyticsFilter.Granularity.WEEK);
        assertThat(window("2026-01-01", "2026-09-30").granularity()).isEqualTo(AnalyticsFilter.Granularity.MONTH);
    }

    @Test
    void bucketsCoverEveryPeriodStartingOnTheTruncatedFrom() {
        assertThat(window("2026-09-01", "2026-09-07").buckets()).hasSize(7);
        // 2026-07-01 is a Wednesday; weekly buckets start on the Monday before.
        assertThat(window("2026-07-01", "2026-09-30").buckets().get(0)).isEqualTo(LocalDate.parse("2026-06-29"));
        assertThat(window("2026-01-15", "2026-09-30").buckets()).hasSize(9);
    }

    @Test
    void defaultsToTheLastThirtyDays() {
        AnalyticsFilter f = AnalyticsFilter.of(1L, null, null, null, null, null, null, IST);
        assertThat(f.to()).isEqualTo(LocalDate.now(IST));
        assertThat(f.from()).isEqualTo(f.to().minusDays(29));
    }

    @Test
    void rejectsAnInvertedOrOversizedRange() {
        assertThatThrownBy(() -> window("2026-09-10", "2026-09-01")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> window("2020-01-01", "2026-09-01")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void blankFiltersAreIgnored() {
        AnalyticsFilter f = AnalyticsFilter.of(1L, null, null, null, null, "  ", "", IST);
        assertThat(f.source()).isNull();
        assertThat(f.stage()).isNull();
    }
}
