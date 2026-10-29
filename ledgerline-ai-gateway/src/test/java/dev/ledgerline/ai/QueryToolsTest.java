package dev.ledgerline.ai;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class QueryToolsTest {
    @Test void capCannotBeBypassed() {
        assertEquals(20, QueryTools.capLimit(9999));
        assertEquals(20, QueryTools.capLimit(-1));
        assertEquals(1, QueryTools.capLimit(1));
    }
    @Test void shortIdsAreFullyMaskedAndSensitiveFieldsAreOmitted() {
        assertEquals("*****", RedactionPolicy.mask("short", 4));
        assertEquals("abcd...jklm", RedactionPolicy.mask("abcdefghijklm", 4));
        var row = Map.<String, Object>of("account_id", "alice", "transfer_dedup_id", "sensitive-id", "cost_center", "SECRET");
        var policy = new RedactionPolicy(Map.of());
        var redacted = policy.apply(row, List.of("account_id", "cost_center", "transfer_dedup_id")).value();
        assertFalse(redacted.containsKey("cost_center"));
        assertNotEquals("sensitive-id", redacted.get("transfer_dedup_id"));
        assertFalse(policy.apply(row, List.of("account_id", "cost_center")).value().containsKey("transfer_dedup_id"));
    }
}
