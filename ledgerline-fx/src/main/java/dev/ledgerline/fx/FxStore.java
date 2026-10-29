package dev.ledgerline.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public class FxStore {
    public record Seed(String base, Map<String, BigDecimal> rates) {}
    public record Table(Map<String, BigDecimal> rates, Instant asOf) {}
    public record Quote(BigDecimal rate, Instant asOf) {}
    private volatile Table table;

    public FxStore(ObjectMapper mapper, @Value("${ledger.fx-seed:classpath:rates_seed.json}") Resource seed) throws IOException {
        try (var input = seed.getInputStream()) {
            Seed data = mapper.readValue(input, Seed.class);
            if (!"USD".equals(data.base())) throw new IllegalArgumentException("USD-anchored seed required");
            replace(data.rates(), Instant.now());
        }
    }

    public void replace(Map<String, BigDecimal> rates, Instant asOf) {
        if (rates == null || rates.isEmpty() || asOf == null) throw new IllegalArgumentException("Nonempty rates and timestamp required");
        var normalized = new TreeMap<String, BigDecimal>();
        rates.forEach((code, rate) -> {
            if (rate == null || rate.signum() <= 0) throw new IllegalArgumentException("Rate must be positive");
            if (normalized.putIfAbsent(normalize(code), rate) != null)
                throw new IllegalArgumentException("Duplicate normalized currency code");
        });
        if (BigDecimal.ONE.compareTo(normalized.getOrDefault("USD", BigDecimal.ZERO)) != 0)
            throw new IllegalArgumentException("USD anchor must equal one");
        table = new Table(Map.copyOf(normalized), asOf);
    }

    public static String normalize(String currency) {
        if (currency == null || currency.isBlank()) throw new IllegalArgumentException("Currency is required");
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    public BigDecimal rate(String base, String quote) {
        return quote(base, quote).rate();
    }
    public Quote quote(String base, String quote) {
        Table current = table;
        BigDecimal from = current.rates().get(normalize(base)), to = current.rates().get(normalize(quote));
        if (from == null || to == null) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.NOT_FOUND, "Unknown currency");
        return new Quote(to.divide(from, MathContext.DECIMAL128), current.asOf());
    }
    public long convert(String base, String quote, long amount) {
        return convertMinor(amount, rate(base, quote));
    }
    public static long convertMinor(long amount, BigDecimal rate) {
        return BigDecimal.valueOf(amount).multiply(rate).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
    public Table snapshot() { return table; }
}
