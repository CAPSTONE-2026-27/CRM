package com.techcrm.crm.analytics;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A small builder for the analytics native queries.
 *
 * Filters are optional, and a bound-but-null parameter is exactly what
 * Postgres cannot type ("could not determine data type of parameter $1"), so
 * a clause is only appended — and its parameter only bound — when the filter
 * is actually set. Parameters are declared up front and bound only if the
 * final SQL references them, so a shared set of window parameters can be
 * offered to every query without "unknown parameter" errors.
 *
 * Only values ever travel as parameters. The two things inlined into SQL —
 * the date_trunc unit and the time zone — come from an enum and a validated
 * ZoneId respectively, never from request text.
 */
final class NativeSql {

    private final StringBuilder sql;
    private final Map<String, Object> params = new LinkedHashMap<>();

    NativeSql(String base) {
        this.sql = new StringBuilder(base);
    }

    NativeSql append(String fragment) {
        sql.append(' ').append(fragment);
        return this;
    }

    NativeSql and(String clause) {
        sql.append(" AND ").append(clause);
        return this;
    }

    NativeSql param(String name, Object value) {
        params.put(name, value);
        return this;
    }

    /** Appends "AND column = :name" only when the value is present. */
    NativeSql andEquals(String column, String name, Object value) {
        if (value != null) {
            and(column + " = :" + name);
            param(name, value);
        }
        return this;
    }

    /** The owner visibility rule and the owner filter, on one column. */
    NativeSql owner(String column, AnalyticsFilter f) {
        andEquals(column, "scopeOwner", f.scopeOwnerId());
        andEquals(column, "ownerFilter", f.ownerId());
        return this;
    }

    /** The standard window parameters: current and previous instants and the
     *  DATE bounds for date columns. Bound only where referenced. */
    NativeSql window(AnalyticsFilter f) {
        param("org", f.organizationId());
        param("cs", f.currentStart());
        param("ce", f.currentEnd());
        param("ps", f.previousStart());
        param("pe", f.previousEnd());
        param("cd", f.from());
        param("cde", f.toExclusive());
        param("pd", f.previousFrom());
        return this;
    }

    List<Object[]> list(EntityManager em) {
        String text = sql.toString();
        Query query = em.createNativeQuery(text);
        params.forEach((name, value) -> {
            if (Pattern.compile(":" + name + "(?![A-Za-z0-9_])").matcher(text).find()) {
                query.setParameter(name, value);
            }
        });
        List<?> raw = query.getResultList();
        List<Object[]> rows = new ArrayList<>(raw.size());
        for (Object row : raw) {
            rows.add(row instanceof Object[] array ? array : new Object[]{row});
        }
        return rows;
    }

    Object[] single(EntityManager em) {
        List<Object[]> rows = list(em);
        return rows.isEmpty() ? new Object[0] : rows.get(0);
    }

    /* ------------------------------------------------------ SQL fragments */

    /** Buckets a timestamptz column in the reporting zone, as 'YYYY-MM-DD'.
     *  Rendered to text so the driver never has to guess a Java time type. */
    static String bucket(String column, AnalyticsFilter f) {
        return "to_char(date_trunc('" + f.granularity().sql() + "', " + column + " AT TIME ZONE '"
                + f.zone().getId() + "'), 'YYYY-MM-DD')";
    }

    /** Buckets a DATE column. */
    static String bucketDate(String column, AnalyticsFilter f) {
        return "to_char(date_trunc('" + f.granularity().sql() + "', CAST(" + column + " AS timestamp)), 'YYYY-MM-DD')";
    }

    /* ---------------------------------------------------- value readers */

    static long num(Object[] row, int i) {
        return i < row.length && row[i] instanceof Number n ? n.longValue() : 0L;
    }

    static BigDecimal dec(Object[] row, int i) {
        if (i >= row.length || row[i] == null) return BigDecimal.ZERO;
        if (row[i] instanceof BigDecimal b) return b;
        if (row[i] instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    /** Nullable double, rounded to one decimal — for averages, where "no
     *  rows" must stay distinguishable from zero. */
    static Double dbl(Object[] row, int i) {
        if (i >= row.length || !(row[i] instanceof Number n)) return null;
        return round1(n.doubleValue());
    }

    static String str(Object[] row, int i) {
        return i < row.length && row[i] != null ? row[i].toString() : null;
    }

    static Double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    /** part / whole as a percentage, or null when whole is zero. */
    static Double rate(long part, long whole) {
        return whole <= 0 ? null : round1(100.0 * part / whole);
    }
}
