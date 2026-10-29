package dev.ledgerline.ai;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Startup-loaded policy. Partial files overlay safe defaults; malformed policies fail startup. */
@Component
public class RedactionPolicy {
    public record FieldRule(String classification, String action) {}
    private record Document(Map<String, FieldRule> fields) {}
    public record Redacted(Map<String, Object> value, Set<String> fields) {}
    private enum Action { NONE, REDACT, MASK }
    private static final Set<String> KNOWN_FIELDS = Set.of("account_id", "currency", "balance_minor", "direction",
        "amount_minor", "gl_account", "cost_center", "posted_at", "transfer_dedup_id");
    private final Map<String, Action> actions;

    @Autowired
    public RedactionPolicy(@Value("${ledger.redaction-policy:classpath:redaction.yaml}") Resource resource) throws IOException {
        this(load(resource));
    }

    RedactionPolicy(Map<String, FieldRule> overrides) {
        var merged = new LinkedHashMap<String, Action>();
        KNOWN_FIELDS.forEach(field -> merged.put(field, Action.NONE));
        merged.put("cost_center", Action.REDACT);
        merged.put("transfer_dedup_id", Action.MASK);
        overrides.forEach((field, rule) -> {
            if (!KNOWN_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown redaction policy field: " + field);
            if (rule == null || !("public".equals(rule.classification()) || "internal_sensitive".equals(rule.classification())))
                throw new IllegalArgumentException("Invalid classification for policy field: " + field);
            Action action;
            if (rule.action() == null) action = "internal_sensitive".equals(rule.classification()) ? Action.REDACT : Action.NONE;
            else action = switch (rule.action()) {
                case "none" -> Action.NONE;
                case "redact" -> Action.REDACT;
                case "mask" -> Action.MASK;
                default -> throw new IllegalArgumentException("Invalid action for policy field: " + field);
            };
            merged.put(field, action);
        });
        actions = Map.copyOf(merged);
    }

    private static Map<String, FieldRule> load(Resource resource) throws IOException {
        var yaml = new ObjectMapper(YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
        try (var input = resource.getInputStream()) {
            Document document = yaml.readValue(input, Document.class);
            if (document == null || document.fields() == null) throw new IllegalArgumentException("Redaction YAML must contain a fields mapping");
            return document.fields();
        }
    }

    public Redacted apply(Map<String, Object> row, List<String> allowedFields) {
        var output = new LinkedHashMap<String, Object>();
        var changed = new TreeSet<String>();
        // Tools still restrict their output shape. A YAML entry cannot expose new DB columns.
        for (String field : allowedFields) {
            if (!KNOWN_FIELDS.contains(field)) throw new IllegalArgumentException("Unknown tool output field");
            if (!row.containsKey(field)) continue;
            Object value = row.get(field);
            switch (actions.get(field)) {
                case NONE -> output.put(field, value);
                case REDACT -> changed.add(field);
                case MASK -> {
                    output.put(field, value == null ? null : mask(String.valueOf(value), "cost_center".equals(field) ? 2 : 4));
                    if (value != null) changed.add(field);
                }
            }
        }
        return new Redacted(output, changed);
    }

    static String mask(String value, int visible) {
        int[] points = value.codePoints().toArray();
        return points.length <= visible * 2 ? "*".repeat(points.length)
            : new String(points, 0, visible) + "..." + new String(points, points.length - visible, visible);
    }
}
