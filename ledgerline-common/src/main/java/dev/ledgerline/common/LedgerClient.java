package dev.ledgerline.common;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpServerErrorException;
import java.util.Arrays;
import java.util.List;

/** Ratis forwards writes; prefer the leader for reads and fail over unavailable HTTP nodes. */
public class LedgerClient {
    private final List<String> nodes;

    public LedgerClient(String nodes) {
        this.nodes = Arrays.stream(nodes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (this.nodes.isEmpty()) throw new IllegalArgumentException("ledger nodes must not be empty");
    }

    public List<String> nodes() { return nodes; }

    public String leaderUrl() {
        for (String node : nodes) {
            try {
                JsonNode health = HttpClients.create(node).get().uri("/v1/healthz").retrieve().body(JsonNode.class);
                if (health != null && "LEADER".equals(health.path("state").asText())) return node;
            } catch (ResourceAccessException | HttpServerErrorException ignored) { }
        }
        throw new ResourceAccessException("No reachable ledger leader");
    }

    public JsonNode account(String id) {
        return HttpClients.create(leaderUrl()).get().uri("/v1/accounts/{id}", id).retrieve().body(JsonNode.class);
    }

    public JsonNode post(String path, Object body) {
        RuntimeException failure = new ResourceAccessException("No ledger nodes available");
        for (String node : nodes) {
            try {
                RestClient client = HttpClients.create(node);
                return client.post().uri(path).body(body).retrieve().body(JsonNode.class);
            } catch (ResourceAccessException | HttpServerErrorException e) { failure = e; }
        }
        throw failure;
    }
}
