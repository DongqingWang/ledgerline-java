package dev.ledgerline.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class ReportFormatter {
    public enum Format {
        JSON("application/json"), CSV("text/csv"), TABLE("text/plain");
        private final String contentType;
        Format(String contentType) { this.contentType = contentType; }
        public String contentType() { return contentType + ";charset=UTF-8"; }
        public static Format parse(String value) {
            try { return Format.valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException | NullPointerException e) { throw new IllegalArgumentException("format must be json, csv, or table"); }
        }
    }
    private final ObjectMapper mapper;
    public ReportFormatter(ObjectMapper mapper) { this.mapper = mapper; }

    public String format(TrialBalance.Report report, Format format) throws JsonProcessingException {
        return switch (format) {
            case JSON -> mapper.writeValueAsString(report) + "\n";
            case CSV -> csv(report);
            case TABLE -> table(report);
        };
    }
    private String csv(TrialBalance.Report report) {
        var text = new StringBuilder("currency,gl_account,debits_minor,credits_minor,net_minor\r\n");
        for (var row : report.rows()) {
            text.append(cell(row.currency())).append(',').append(cell(row.glAccount())).append(',')
                .append(row.debits().toPlainString()).append(',').append(row.credits().toPlainString()).append(',')
                .append(row.debits().subtract(row.credits()).toPlainString()).append("\r\n");
        }
        return text.toString();
    }
    private static String cell(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
            ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }
    private String table(TrialBalance.Report report) {
        var text = new StringBuilder("Trial Balance — integer minor units\n");
        text.append("Period: ").append(report.period().from() == null ? "unbounded" : report.period().from())
            .append(" through ").append(report.period().to() == null ? "unbounded" : report.period().to()).append(" (UTC, inclusive)\n");
        String columns = "%-8s %-16s %22s %22s %22s%n";
        text.append(String.format(Locale.ROOT, columns, "Currency", "GL account", "Debits", "Credits", "Net (D-C)"));
        for (var row : report.rows()) text.append(String.format(Locale.ROOT, columns, row.currency(), row.glAccount(),
            row.debits().toPlainString(), row.credits().toPlainString(), row.debits().subtract(row.credits()).toPlainString()));
        text.append("Unbalanced transfers in period: ").append(report.unbalancedTransfers().size()).append('\n')
            .append("Account balance mismatches (all history): ").append(report.balanceMismatches().size()).append('\n')
            .append("RECONCILIATION: ").append(report.balanced() ? "PASS" : "FAIL").append('\n');
        return text.toString();
    }
}
