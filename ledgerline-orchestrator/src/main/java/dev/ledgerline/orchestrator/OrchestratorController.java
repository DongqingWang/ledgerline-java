package dev.ledgerline.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import dev.ledgerline.common.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline demo only: clients must stop writes and explicitly acknowledge that before splitting. */
@RestController
public class OrchestratorController {
    public record Split(long splitPoint, List<String> newShardLedgerNodes, boolean writesQuiesced) {}
    private final RoutingTable table;
    public OrchestratorController(RoutingTable table) { this.table = table; }
    @GetMapping("/v1/routing") public List<RoutingTable.Shard> routing() { return table.snapshot(); }
    @GetMapping("/healthz") public Map<String, String> health() { return Map.of("status", "ok"); }

    @PostMapping("/v1/split")
    public synchronized ResponseEntity<?> split(@RequestBody Split req) {
        try {
            if (!req.writesQuiesced()) throw new IllegalArgumentException("Stop all writes and set writes_quiesced=true; online migration is not supported");
            if (req.newShardLedgerNodes() == null || req.newShardLedgerNodes().isEmpty()) throw new IllegalArgumentException("New nodes required");
            var old = table.owner(req.splitPoint());
            if (req.splitPoint() == old.rangeStart()) throw new IllegalArgumentException("Split point must be inside a shard");
            var source = new LedgerClient(String.join(",", old.ledgerNodes()));
            var target = new LedgerClient(String.join(",", req.newShardLedgerNodes()));
            if (req.newShardLedgerNodes().stream().anyMatch(old.ledgerNodes()::contains)) throw new IllegalArgumentException("Source and target must be separate clusters");
            String sourceLeader = source.leaderUrl(), targetLeader = target.leaderUrl();
            JsonNode[] history = events(sourceLeader);
            if (events(targetLeader).length != 0) throw new IllegalArgumentException("Target cluster must be empty");
            var accounts = new LinkedHashMap<String, JsonNode>();
            var transfers = new ArrayList<JsonNode>();
            for (JsonNode event : history) {
                if ("account_created".equals(event.path("kind").asText())) {
                    String id = event.path("account_id").asText();
                    if (moved(id, req.splitPoint(), old.rangeEnd())) accounts.put(id, source.account(id));
                } else if ("transfer_applied".equals(event.path("kind").asText())) {
                    boolean from = moved(event.path("from_account").asText(), req.splitPoint(), old.rangeEnd());
                    boolean to = moved(event.path("to_account").asText(), req.splitPoint(), old.rangeEnd());
                    if (from != to) throw new IllegalArgumentException("Cross-shard transfer history prevents a safe split");
                    if (from) transfers.add(event);
                }
            }
            for (JsonNode account : accounts.values()) {
                target.post("/v1/accounts", Map.of("account_id", account.path("account_id").asText(),
                    "currency", account.path("currency").asText(), "unlimited", account.path("unlimited").asBoolean()));
            }
            for (JsonNode event : transfers) {
                var body = new LinkedHashMap<String, Object>();
                for (String field : List.of("dedup_id", "from_account", "to_account", "amount_minor", "gl_account", "cost_center")) {
                    if (event.hasNonNull(field)) body.put(field, event.get(field));
                }
                body.put("currency", accounts.get(event.path("from_account").asText()).path("currency").asText());
                target.post("/v1/transfers", body);
            }
            for (var entry : accounts.entrySet()) {
                if (target.account(entry.getKey()).path("balance_minor").asLong() != entry.getValue().path("balance_minor").asLong())
                    throw new IllegalArgumentException("Migrated balance mismatch; routing unchanged");
            }
            if (!java.util.Arrays.equals(history, events(sourceLeader))) throw new IllegalArgumentException("Source changed during migration; routing unchanged");
            table.split(req.splitPoint(), req.newShardLedgerNodes());
            return ResponseEntity.ok(Map.of("status", "applied", "migrated_accounts", accounts.size(), "migrated_transfers", transfers.size()));
        } catch (RuntimeException e) {
            return ResponseEntity.status(409).body(Map.of("status", "aborted", "reason", e.getMessage() == null ? "Migration failed" : e.getMessage()));
        }
    }
    private boolean moved(String id, long start, long end) { long hash = RoutingTable.hash(id); return hash >= start && hash < end; }
    private JsonNode[] events(String url) {
        JsonNode[] result = HttpClients.create(url).get().uri("/v1/events").retrieve().body(JsonNode[].class);
        if (result == null) throw new IllegalArgumentException("Empty event history response");
        return result;
    }
}
