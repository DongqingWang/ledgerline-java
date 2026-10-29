package dev.ledgerline.balance;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
public class BalanceController {
    private final BalanceCache cache;
    public BalanceController(BalanceCache cache) { this.cache = cache; }
    @GetMapping("/v1/balances") public List<BalanceCache.Balance> all() { return cache.all(); }
    @GetMapping("/v1/balances/{id}") public ResponseEntity<?> get(@PathVariable String id) {
        var balance = cache.get(id);
        return balance == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(balance);
    }
    @GetMapping("/healthz") public Map<String, Object> health() {
        return Map.of("status", "ok", "last_raft_index", cache.lastIndex(), "cached_accounts", cache.all().size());
    }
}
