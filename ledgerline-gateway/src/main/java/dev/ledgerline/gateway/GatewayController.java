package dev.ledgerline.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerline.common.*;
import dev.ledgerline.common.Accounting.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class GatewayController {
    public record Request(String dedupId, String fromAccount, String toAccount, long amountMinor, long feeMinor,
                          String currency, String category, String country) {}
    public record Transfer(String dedupId, String fromAccount, String toAccount, long amountMinor, String currency,
                           String glAccount, String costCenter) {}
    public record Result(String status, Journal journal, List<JsonNode> ledgerResults) {}
    private final LedgerClient ledger;
    private final RestClient accounting;

    public GatewayController(@Value("${ledger.nodes:http://localhost:8001}") String nodes,
                             @Value("${ledger.accounting-url:http://localhost:8081}") String accountingUrl) {
        ledger = new LedgerClient(nodes);
        accounting = HttpClients.create(accountingUrl);
    }

    @PostMapping("/v1/transfers")
    public Result transfer(@RequestBody Request req) {
        ApiErrors.required(req.dedupId(), "dedup_id");
        var ctx = new Context(req.fromAccount(), req.toAccount(), req.amountMinor(), req.feeMinor(), req.currency(), req.category(), req.country());
        Journal journal = accounting.post().uri("/v1/compose").body(ctx).retrieve().body(Journal.class);
        if (journal == null) throw new IllegalArgumentException("Empty composed journal");
        List<Transfer> transfers = buildTransfers(req, journal);
        var results = new ArrayList<JsonNode>();
        for (Transfer tr : transfers) results.add(ledger.post("/v1/transfers", tr));
        String status = results.stream().allMatch(r -> "duplicate".equals(r.path("status").asText())) ? "duplicate" : "applied";
        return new Result(status, journal, List.copyOf(results));
    }

    public static List<Transfer> buildTransfers(Request req, Journal journal) {
        journal.validate();
        if (req.amountMinor() <= 0 || req.feeMinor() < 0 || req.feeMinor() > req.amountMinor())
            throw new IllegalArgumentException("Invalid amount or fee");
        Line debit = journal.lines().getFirst();
        if (!"debit".equals(debit.direction()) || !req.fromAccount().equals(debit.accountId()) || debit.amountMinor() != req.amountMinor())
            throw new IllegalArgumentException("Journal debit does not match request");
        var result = new ArrayList<Transfer>();
        for (int i = 1; i < journal.lines().size(); i++) {
            Line credit = journal.lines().get(i);
            if (!"credit".equals(credit.direction())) throw new IllegalArgumentException("Expected credit line");
            boolean fee = i == 2;
            if (i > 2 || !(fee ? "platform-fee-revenue" : req.toAccount()).equals(credit.accountId())
                || credit.amountMinor() != (fee ? req.feeMinor() : req.amountMinor() - req.feeMinor()))
                throw new IllegalArgumentException("Journal credit does not match request");
            if (credit.amountMinor() == 0) continue;
            String id = req.feeMinor() == 0 ? req.dedupId() : req.dedupId() + (fee ? "-fee" : "-net");
            Line dims = req.feeMinor() == 0 ? debit : credit;
            result.add(new Transfer(id, req.fromAccount(), credit.accountId(), credit.amountMinor(), req.currency(), dims.glAccount(), dims.costCenter()));
        }
        return List.copyOf(result);
    }

    @GetMapping("/v1/accounts/{id}") public JsonNode account(@PathVariable String id) { return ledger.account(id); }
    @PostMapping("/v1/accounts") public JsonNode create(@RequestBody Map<String, Object> request) { return ledger.post("/v1/accounts", request); }
    @GetMapping("/healthz") public Map<String, String> health() { return Map.of("status", "ok"); }
}
