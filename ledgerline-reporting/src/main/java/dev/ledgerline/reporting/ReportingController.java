package dev.ledgerline.reporting;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ReportingController {
    private final TrialBalance report;
    private final ReportFormatter formatter;
    public ReportingController(TrialBalance report, ReportFormatter formatter) { this.report = report; this.formatter = formatter; }
    @GetMapping("/v1/reports/trial-balance")
    public org.springframework.http.ResponseEntity<String> trialBalance(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(defaultValue = "json") String format) throws com.fasterxml.jackson.core.JsonProcessingException {
        DateRange period = DateRange.parse(from, to);
        var selected = ReportFormatter.Format.parse(format);
        var result = report.run(period);
        return org.springframework.http.ResponseEntity.ok().header("Content-Type", selected.contentType())
            .header("X-Ledgerline-Reconciliation", result.balanced() ? "pass" : "fail")
            .header("X-Ledgerline-Projection-Scope", "all-history").body(formatter.format(result, selected));
    }
    @GetMapping("/healthz") public Map<String, String> health() { return Map.of("status", "ok"); }
}
