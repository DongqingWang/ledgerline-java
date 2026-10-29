package dev.ledgerline.ai;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RedactionPolicyTest {
    RedactionPolicy yaml(String text) throws Exception {
        return new RedactionPolicy(new ByteArrayResource(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void bundledPolicyMatchesOriginalDefaults() throws Exception {
        var policy = new RedactionPolicy(new ClassPathResource("redaction.yaml"));
        var output = policy.apply(Map.of("account_id", "alice", "cost_center", "SECRET", "transfer_dedup_id", "a-long-transfer-id"),
            List.of("account_id", "cost_center", "transfer_dedup_id"));
        assertEquals("alice", output.value().get("account_id"));
        assertFalse(output.value().containsKey("cost_center"));
        assertEquals("a-lo...r-id", output.value().get("transfer_dedup_id"));
        assertEquals(java.util.Set.of("cost_center", "transfer_dedup_id"), output.fields());
    }
    @Test void partialPolicyOverlaysDefaultsInsteadOfExposingOmittedSensitiveFields() throws Exception {
        var policy = yaml("fields:\n  account_id: {classification: internal_sensitive, action: mask}\n");
        var output = policy.apply(Map.of("account_id", "alice", "cost_center", "SECRET"), List.of("account_id", "cost_center"));
        assertEquals("*****", output.value().get("account_id"));
        assertFalse(output.value().containsKey("cost_center"));
    }
    @Test void explicitOverridesAndImplicitSensitiveDefaultAreApplied() throws Exception {
        var policy = yaml("""
            fields:
              cost_center: {classification: public, action: none}
              amount_minor: {classification: internal_sensitive}
              transfer_dedup_id: {classification: internal_sensitive, action: redact}
            """);
        var output = policy.apply(Map.of("cost_center", "CC-FEES", "amount_minor", 123, "transfer_dedup_id", "txn"),
            List.of("cost_center", "amount_minor", "transfer_dedup_id"));
        assertEquals(Map.of("cost_center", "CC-FEES"), output.value());
        assertEquals(java.util.Set.of("amount_minor", "transfer_dedup_id"), output.fields());
    }
    @Test void typosMalformedFilesAndDuplicateFieldsFailClosed() {
        for (String text : List.of("", "[]", "fields: null", "fields: {costcenter: {classification: public}}",
            "fields: {cost_center: {classification: secret}}", "fields: {cost_center: {classification: public, action: reveal}}",
            "fields: {cost_center: {classification: public, actoin: none}}",
            "fields:\n  cost_center: {classification: public}\n  cost_center: {classification: internal_sensitive}\n"))
            assertThrows(Exception.class, () -> yaml(text), text);
    }
    @Test void masksUnicodeWithoutReturningShortValuesOrBrokenSurrogates() {
        assertEquals("****", RedactionPolicy.mask("😀😀😀😀", 2));
        assertEquals("😀😀...😀😀", RedactionPolicy.mask("😀😀😀😀😀", 2));
    }
}
