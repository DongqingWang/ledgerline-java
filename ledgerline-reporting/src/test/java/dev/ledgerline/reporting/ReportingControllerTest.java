package dev.ledgerline.reporting;

import dev.ledgerline.common.ApiErrors;
import dev.ledgerline.common.HttpClients;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportingControllerTest {
    @Test void endpointSupportsFormatsBoundsAndClearReconciliationHeaders() throws Exception {
        var db = new TrialBalanceTest().database();
        var controller = new ReportingController(new TrialBalance(db), new ReportFormatter(HttpClients.mapper()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get("/v1/reports/trial-balance").param("from", "2026-01-01").param("to", "2026-01-01"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.balanced").value(true)).andExpect(jsonPath("$.period.from").value("2026-01-01"))
            .andExpect(header().string("X-Ledgerline-Projection-Scope", "all-history"));
        mvc.perform(get("/v1/reports/trial-balance").param("format", "csv"))
            .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andExpect(content().string(org.hamcrest.Matchers.startsWith("currency,gl_account,")));
        mvc.perform(get("/v1/reports/trial-balance").param("format", "table"))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("RECONCILIATION: PASS")));
        mvc.perform(get("/v1/reports/trial-balance").param("from", "2026-02-30")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/reports/trial-balance").param("format", "xml")).andExpect(status().isBadRequest());
    }
}
