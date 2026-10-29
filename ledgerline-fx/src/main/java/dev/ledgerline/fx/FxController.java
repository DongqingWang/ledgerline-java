package dev.ledgerline.fx;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class FxController {
    public record Request(String base, String quote, long amountMinor) {}
    private final FxStore store;
    private final FxRefreshProperties properties;
    public FxController(FxStore store, FxRefreshProperties properties) { this.store = store; this.properties = properties; }
    @GetMapping("/v1/rates/{base}/{quote}") public Map<String, Object> rate(@PathVariable String base, @PathVariable String quote) {
        var current = store.quote(base, quote);
        return Map.of("base", FxStore.normalize(base), "quote", FxStore.normalize(quote), "rate", current.rate(), "as_of", current.asOf());
    }
    @PostMapping("/v1/convert") public Map<String, Object> convert(@RequestBody Request req) {
        var current = store.quote(req.base(), req.quote());
        return Map.of("base", FxStore.normalize(req.base()), "quote", FxStore.normalize(req.quote()), "amount_minor", req.amountMinor(),
            "converted_amount_minor", FxStore.convertMinor(req.amountMinor(), current.rate()), "rate", current.rate());
    }
    @GetMapping("/healthz") public Map<String, Object> health() {
        return Map.of("status", "ok", "rates_as_of", store.snapshot().asOf(), "refresh_enabled", properties.refreshEnabled());
    }
}
