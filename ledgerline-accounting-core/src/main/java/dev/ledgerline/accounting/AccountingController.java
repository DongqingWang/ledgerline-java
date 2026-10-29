package dev.ledgerline.accounting;

import dev.ledgerline.common.Accounting.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class AccountingController {
    private final RuleEngine engine;
    public AccountingController(RuleEngine engine) { this.engine = engine; }
    @PostMapping({"/v1/compose", "/v1/journal/compose"}) public Journal compose(@RequestBody Context context) { return engine.compose(context); }
    @GetMapping("/healthz") public Map<String, String> health() { return Map.of("status", "ok"); }
}
