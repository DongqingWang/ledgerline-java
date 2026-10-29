package dev.ledgerline.ai;

import dev.ledgerline.common.HttpClients;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class McpControllerTest {
    @Test void protocolInitializationDiscoveryAndNotifications() throws Exception {
        var mapper = HttpClients.mapper();
        var controller = new McpController(null, mapper);
        var result = controller.rpc(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"), null);
        var json = mapper.valueToTree(result.getBody());
        assertEquals("2025-03-26", json.path("result").path("protocolVersion").asText());
        var listed = controller.rpc(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"), null);
        assertEquals(3, mapper.valueToTree(listed.getBody()).path("result").path("tools").size());
        var notification = controller.rpc(mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"), null);
        assertEquals(202, notification.getStatusCode().value());
    }
    @Test void invalidRequestsUnknownMethodsAndOriginsAreRejected() throws Exception {
        var mapper = HttpClients.mapper();
        var controller = new McpController(null, mapper);
        var invalid = controller.rpc(mapper.readTree("{}"), null);
        assertEquals(-32600, mapper.valueToTree(invalid.getBody()).path("error").path("code").asInt());
        var unknown = controller.rpc(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"sql/execute\"}"), null);
        assertEquals(-32601, mapper.valueToTree(unknown.getBody()).path("error").path("code").asInt());
        assertEquals(403, controller.rpc(mapper.readTree("{}"), "https://untrusted.example").getStatusCode().value());
    }
}
