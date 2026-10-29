package dev.ledgerline.accounting;

import dev.ledgerline.common.Accounting.*;
import dev.ledgerline.common.HttpClients;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuleEngineTest {
    private RuleEngine engine() throws Exception { return new RuleEngine(new ClassPathResource("rules.yaml")); }
    private Context context(long amount, long fee, String category, String country) {
        return new Context("alice", "bob", amount, fee, "USD", category, country);
    }
    @Test void firstMatchWinsAndFeeBalances() throws Exception {
        Journal journal = engine().compose(context(1000, 100, "seller_payout", "US"));
        journal.validate();
        assertEquals(3, journal.lines().size());
        assertEquals("CC-PAYOUT-US", journal.lines().getFirst().costCenter());
        assertEquals(900, journal.lines().get(1).amountMinor());
        assertEquals("4900", journal.lines().get(2).glAccount());
    }
    @Test void wildcardFallbackAndInternationalRules() throws Exception {
        assertEquals("CC-PAYOUT-INTL", engine().compose(context(100, 0, "seller_payout", "DE")).lines().getFirst().costCenter());
        assertEquals("9999", engine().compose(context(100, 0, "unknown", "DE")).lines().getFirst().glAccount());
    }
    @Test void invalidAmountsAndUnsupportedFeesAreRejected() throws Exception {
        var engine = engine();
        assertThrows(IllegalArgumentException.class, () -> engine.compose(context(0, 0, "refund", "US")));
        assertThrows(IllegalArgumentException.class, () -> engine.compose(context(100, 101, "seller_payout", "US")));
        assertThrows(IllegalArgumentException.class, () -> engine.compose(context(100, -1, "seller_payout", "US")));
        assertThrows(IllegalArgumentException.class, () -> engine.compose(context(100, 1, "refund", "US")));
    }
    @Test void realJsonBoundaryUsesSnakeCase() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new AccountingController(engine()))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(HttpClients.mapper())).build();
        mvc.perform(post("/v1/compose").contentType(MediaType.APPLICATION_JSON).content("""
            {"from_account":"alice","to_account":"bob","amount_minor":1000,"fee_minor":100,
             "currency":"USD","category":"seller_payout","country":"US"}
            """)).andExpect(status().isOk()).andExpect(jsonPath("$.lines[1].amount_minor").value(900))
            .andExpect(jsonPath("$.lines[0].gl_account").value("2100"));
    }
}
