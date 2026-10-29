package dev.ledgerline.reporting;

import dev.ledgerline.common.HttpClients;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReportOutputTest {
    TrialBalance.Report report(String gl) {
        return new TrialBalance.Report(true, List.of(new TrialBalance.Row("USD", gl, new BigDecimal("9223372036854775808"),
            new BigDecimal("9223372036854775808"))), List.of(), List.of(), DateRange.parse("2026-01-01", "2026-01-31"));
    }
    @Test void jsonIsMachineReadableAndRetainsReconciliationDetails() throws Exception {
        String text = new ReportFormatter(HttpClients.mapper()).format(report("1000"), ReportFormatter.Format.JSON);
        var parsed = HttpClients.mapper().readTree(text);
        assertTrue(parsed.path("balanced").asBoolean());
        assertEquals("2026-01-01", parsed.path("period").path("from").asText());
        assertEquals("9223372036854775808", parsed.path("rows").get(0).path("debits").asText());
    }
    @Test void csvEscapesCommasQuotesAndNewlinesWithoutLosingIntegerPrecision() throws Exception {
        String text = new ReportFormatter(HttpClients.mapper()).format(report("a,\"b\"\nc"), ReportFormatter.Format.CSV);
        assertEquals("currency,gl_account,debits_minor,credits_minor,net_minor\r\nUSD,\"a,\"\"b\"\"\nc\",9223372036854775808,9223372036854775808,0\r\n", text);
        assertFalse(text.contains("RECONCILIATION")); // No footer corrupting a rectangular CSV export.
    }
    @Test void tableIncludesPeriodUnitsAndReconciliationScope() throws Exception {
        String text = new ReportFormatter(HttpClients.mapper()).format(report("1000"), ReportFormatter.Format.TABLE);
        assertTrue(text.contains("integer minor units"));
        assertTrue(text.contains("2026-01-01 through 2026-01-31 (UTC, inclusive)"));
        assertTrue(text.contains("all history"));
        assertTrue(text.contains("RECONCILIATION: PASS"));
    }
    @Test void cliAcceptsBothAssignmentAndSeparatedValues() {
        var args = ReportOptions.parse(new String[]{"--report", "--format", "CSV", "--from=2026-01-01", "--to", "2026-01-31", "--output", "trial.csv",
            "--spring.datasource.url=jdbc:postgresql://localhost/test"});
        assertEquals(ReportFormatter.Format.CSV, args.format());
        assertEquals(java.nio.file.Path.of("trial.csv"), args.output());
        assertEquals(java.time.LocalDate.of(2026, 1, 31), args.period().to());
        assertEquals(ReportFormatter.Format.JSON, ReportOptions.parse(new String[]{"--report"}).format());
    }
    @Test void invalidDatesFormatsAndMissingOrDuplicateOptionsFailBeforeDatabaseAccess() {
        for (String[] args : new String[][]{
            {"--format=xml"}, {"--format"}, {"--output="}, {"--from=2026-02-30"}, {"--from=2026-2-01"},
            {"--from=0000-01-01"}, {"--from=2026-02-01", "--to=2026-01-01"}, {"--from=2026-01-01' OR 1=1"},
            {"--format=json", "--format=csv"}})
            assertThrows(IllegalArgumentException.class, () -> ReportOptions.parse(args), java.util.Arrays.toString(args));
    }
}
