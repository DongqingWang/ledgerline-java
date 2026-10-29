package dev.ledgerline.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.common.ApiErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;
import java.util.TreeSet;

@Service
public class QueryTools {
    private static final Logger AUDIT = LoggerFactory.getLogger("ledgerline.audit");
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final RedactionPolicy policy;
    public QueryTools(JdbcTemplate db, ObjectMapper mapper, RedactionPolicy policy) {
        this.db = db;
        this.mapper = mapper;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public Object call(String name, JsonNode args) {
        int rows = 0;
        String outcome = "ok";
        Set<String> redactedFields = new TreeSet<>();
        try {
            Object result;
            switch (name) {
                case "get_account_balance" -> {
                    String account = required(args, "account_id");
                    var values = db.queryForList("SELECT account_id,currency,balance_minor FROM account_balances WHERE account_id=?", account);
                    if (values.isEmpty()) throw new IllegalArgumentException("Account not found");
                    rows = values.size();
                    var redacted = policy.apply(values.getFirst(), List.of("account_id", "currency", "balance_minor"));
                    redactedFields.addAll(redacted.fields());
                    result = redacted.value();
                }
                case "get_journal_entry", "search_transactions" -> {
                    boolean search = "search_transactions".equals(name);
                    String field = search ? "account_id" : "transfer_dedup_id";
                    String value = required(args, field);
                    // Field and ORDER BY are server constants, never client-supplied SQL.
                    var values = db.queryForList("SELECT account_id,direction,amount_minor,currency,gl_account,cost_center,posted_at,transfer_dedup_id "
                        + "FROM journal_entries WHERE " + field + "=? ORDER BY raft_index DESC,id DESC LIMIT ?", value,
                        search ? capLimit(args.path("limit").asInt()) : 2);
                    if (!search && values.isEmpty()) throw new IllegalArgumentException("Journal not found");
                    var fields = new ArrayList<>(List.of("account_id", "direction", "amount_minor", "currency", "gl_account", "cost_center", "posted_at"));
                    if (search) fields.add("transfer_dedup_id");
                    var entries = new ArrayList<Map<String, Object>>();
                    for (var row : values) {
                        var redacted = policy.apply(row, fields);
                        redactedFields.addAll(redacted.fields());
                        entries.add(redacted.value());
                    }
                    rows = entries.size();
                    result = Map.of("entries", entries);
                }
                default -> throw new IllegalArgumentException("Unknown tool");
            }
            return result;
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            // Never log raw account IDs, dedup IDs, or database exception details.
            try {
                AUDIT.info("{}", mapper.writeValueAsString(Map.of("timestamp", Instant.now(), "tool", name,
                    "result_row_count", rows, "outcome", outcome, "effective_limit", capLimit(args.path("limit").asInt()),
                    "redacted_fields", redactedFields)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) { AUDIT.error("Audit serialization failed"); }
        }
    }
    static int capLimit(int requested) { return requested <= 0 ? 20 : Math.min(requested, 20); }
    private static String required(JsonNode args, String field) {
        if (!args.path(field).isTextual()) throw new IllegalArgumentException(field + " must be a string");
        return ApiErrors.required(args.path(field).asText(), field);
    }
}
