package dev.ledgerline.fx;

import dev.ledgerline.common.HttpClients;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FxStoreTest {
    FxStore store() throws Exception { return new FxStore(HttpClients.mapper(), new ClassPathResource("rates_seed.json")); }
    @Test void crossRatesAndIntegerRounding() throws Exception {
        var store = store();
        assertEquals(0, BigDecimal.ONE.compareTo(store.rate("eur", " EUR ")));
        assertEquals(9200, store.convert("USD", "EUR", 10000));
        assertEquals(1, store.convert("USD", "EUR", 1));
        assertEquals(-1, store.convert("USD", "EUR", -1));
    }
    @Test void failedRefreshKeepsLastGoodTable() throws Exception {
        var store = store();
        var previous = store.snapshot();
        assertThrows(IllegalArgumentException.class, () -> store.replace(Map.of("USD", BigDecimal.TEN), Instant.now()));
        assertSame(previous, store.snapshot());
    }
    @Test void overflowAndUnknownCurrencyAreRejected() throws Exception {
        var store = store();
        assertThrows(ArithmeticException.class, () -> store.convert("USD", "JPY", Long.MAX_VALUE));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> store.rate("USD", "ZZZ"));
    }
}
