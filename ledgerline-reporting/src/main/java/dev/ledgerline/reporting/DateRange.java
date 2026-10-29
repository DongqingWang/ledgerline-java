package dev.ledgerline.reporting;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** Inclusive UTC calendar dates, implemented using an exclusive next-day upper bound. */
public record DateRange(LocalDate from, LocalDate to) {
    public record Filter(String sql, List<Object> parameters) {}
    public DateRange {
        if ((from != null && from.getYear() < 1) || (to != null && to.getYear() < 1))
            throw new IllegalArgumentException("Report dates must be in year 0001 or later");
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must not be after to");
    }
    public static DateRange all() { return new DateRange(null, null); }
    public static DateRange parse(String from, String to) { return new DateRange(date(from), date(to)); }
    private static LocalDate date(String text) {
        if (text == null) return null;
        if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Dates must use YYYY-MM-DD");
        try { return LocalDate.parse(text); }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("Invalid report date: " + text, e); }
    }
    public Filter filter() {
        var terms = new ArrayList<String>();
        var parameters = new ArrayList<Object>();
        if (from != null) {
            terms.add("posted_at>=?");
            parameters.add(Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        if (to != null) {
            terms.add("posted_at<?");
            parameters.add(Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
        }
        return new Filter(terms.isEmpty() ? "" : " WHERE " + String.join(" AND ", terms), List.copyOf(parameters));
    }
}
