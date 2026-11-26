package com.techcrm.crm.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * One analytics request, resolved: the reporting window, the equally long
 * window immediately before it (for "vs previous period"), the time-bucket
 * size, and the owner/source/stage filters.
 *
 * Dates are inclusive calendar days in the reporting zone. They become
 * half-open instants here — [from 00:00, to+1 00:00) — so every query
 * compares timestamps with >= and <, and a deal closed at 23:59 on the last
 * day is never lost to a rounding edge.
 *
 * scopeOwnerId is the visibility rule (set for a SALES_REP, always their own
 * id); ownerId is the filter the caller asked for. Both are applied, ANDed —
 * the same composition LeadService uses — so a rep who asks for a colleague's
 * data gets an empty report rather than the colleague's numbers.
 */
public record AnalyticsFilter(
        Long organizationId,
        Long scopeOwnerId,
        Long ownerId,
        String source,
        String stage,
        LocalDate from,
        LocalDate to,
        LocalDate previousFrom,
        LocalDate previousTo,
        ZoneId zone,
        Granularity granularity
) {

    /** Long enough for a multi-year trend, short enough that a typo'd year
     *  can't ask Postgres to bucket a century of days. */
    static final int MAX_RANGE_DAYS = 1100;
    static final int DEFAULT_RANGE_DAYS = 30;

    public enum Granularity {
        DAY("day"), WEEK("week"), MONTH("month");

        private final String sql;

        Granularity(String sql) {
            this.sql = sql;
        }

        public String sql() {
            return sql;
        }

        public String label() {
            return sql;
        }
    }

    public static AnalyticsFilter of(Long organizationId, Long scopeOwnerId, LocalDate from, LocalDate to,
                                     Long ownerId, String source, String stage, ZoneId zone) {
        LocalDate end = to != null ? to : LocalDate.now(zone);
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be on or before 'to'.");
        }
        long days = ChronoUnit.DAYS.between(start, end) + 1;
        if (days > MAX_RANGE_DAYS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Date range is limited to " + MAX_RANGE_DAYS + " days.");
        }
        Granularity granularity = days <= 31 ? Granularity.DAY : days <= 184 ? Granularity.WEEK : Granularity.MONTH;
        return new AnalyticsFilter(organizationId, scopeOwnerId, ownerId, blankToNull(source), blankToNull(stage),
                start, end, start.minusDays(days), start.minusDays(1), zone, granularity);
    }

    /* ---------------------------------------------------------- instants */

    public OffsetDateTime currentStart() {
        return from.atStartOfDay(zone).toOffsetDateTime();
    }

    public OffsetDateTime currentEnd() {
        return to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    public OffsetDateTime previousStart() {
        return previousFrom.atStartOfDay(zone).toOffsetDateTime();
    }

    /** Equal to currentStart: the two windows are contiguous. */
    public OffsetDateTime previousEnd() {
        return currentStart();
    }

    /** Exclusive upper bound for DATE columns (meeting_date). */
    public LocalDate toExclusive() {
        return to.plusDays(1);
    }

    /* ---------------------------------------------------------- buckets */

    /** Every bucket start in the window, so a series has a point for each
     *  day/week/month even where nothing happened. Weeks start on Monday,
     *  matching Postgres date_trunc('week', …). */
    public List<LocalDate> buckets() {
        List<LocalDate> out = new ArrayList<>();
        LocalDate cursor = truncate(from);
        while (!cursor.isAfter(to)) {
            out.add(cursor);
            cursor = switch (granularity) {
                case DAY -> cursor.plusDays(1);
                case WEEK -> cursor.plusWeeks(1);
                case MONTH -> cursor.plusMonths(1);
            };
        }
        return out;
    }

    private LocalDate truncate(LocalDate date) {
        return switch (granularity) {
            case DAY -> date;
            case WEEK -> date.with(DayOfWeek.MONDAY);
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    public AnalyticsDtos.Window window() {
        return new AnalyticsDtos.Window(from, to, previousFrom, previousTo, granularity.label(), zone.getId());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
