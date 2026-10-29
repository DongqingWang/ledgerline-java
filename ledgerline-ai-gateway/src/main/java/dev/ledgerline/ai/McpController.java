package dev.ledgerline.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

/** Stateless MCP Streamable HTTP subset: initialize, ping, tools/list, tools/call. No write tools. */
@RestController
public class McpController {
    private final QueryTools tools;
    private final ObjectMapper mapper;
    public McpController(QueryTools tools, ObjectMapper mapper) { this.tools = tools; this.mapper = mapper; }

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<?> rpc(@RequestBody JsonNode request,
                                 @RequestHeader(value = "Origin", required = false) String origin) {
        if (origin != null && !List.of("http://localhost:5180", "http://127.0.0.1:5180").contains(origin))
            return ResponseEntity.status(403).build();
        Object id = request.has("id") ? request.get("id") : com.fasterxml.jackson.databind.node.NullNode.instance;
        if (!"2.0".equals(request.path("jsonrpc").asText()) || !request.path("method").isTextual())
            return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32600, "message", "Invalid Request")));
        String method = request.path("method").asText();
        if (!request.has("id")) return ResponseEntity.accepted().build();
        Object result;
        switch (method) {
            case "initialize" -> result = Map.of("protocolVersion", "2025-03-26", "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "ledgerline-java-ai-gateway", "version", "0.1.0"));
            case "ping" -> result = Map.of();
            case "tools/list" -> result = Map.of("tools", List.of(
                tool("get_account_balance", "account_id", false), tool("get_journal_entry", "transfer_dedup_id", false),
                tool("search_transactions", "account_id", true)));
            case "tools/call" -> {
                String name = request.path("params").path("name").asText();
                try {
                    Object output = tools.call(name, request.path("params").path("arguments"));
                    result = Map.of("content", List.of(Map.of("type", "text", "text", mapper.writeValueAsString(output))), "isError", false);
                } catch (IllegalArgumentException e) {
                    result = Map.of("content", List.of(Map.of("type", "text", "text", e.getMessage())), "isError", true);
                } catch (Exception e) {
                    result = Map.of("content", List.of(Map.of("type", "text", "text", "Ledger query unavailable")), "isError", true);
                }
            }
            default -> {
                return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32601, "message", "Method not found")));
            }
        }
        return ResponseEntity.ok(Map.of("jsonrpc", "2.0", "id", id, "result", result));
    }
    private Map<String, Object> tool(String name, String required, boolean search) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        properties.put(required, Map.of("type", "string"));
        if (search) properties.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
        return Map.of("name", name, "description", "Read-only ledger projection query; configured field policy applied.",
            "inputSchema", Map.of("type", "object", "properties", properties, "required", List.of(required), "additionalProperties", false),
            "annotations", Map.of("readOnlyHint", true));
    }
    @GetMapping("/healthz") public Map<String, String> health() { return Map.of("status", "ok"); }
}
