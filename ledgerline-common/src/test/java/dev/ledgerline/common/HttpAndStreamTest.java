package dev.ledgerline.common;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class HttpAndStreamTest {
    @Test void fractionalMinorUnitsAreNeverSilentlyTruncated() {
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> HttpClients.mapper().readValue(
            "{\"from_account\":\"alice\",\"to_account\":\"bob\",\"amount_minor\":1.5,\"currency\":\"USD\"}", Accounting.Context.class));
    }

    @Test void actualHttpBoundarySerializesSnakeCaseAndFailsOverOnlyTransientErrors() throws Exception {
        var unavailable = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var healthy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var payloads = new CopyOnWriteArrayList<String>();
        unavailable.createContext("/v1/transfers", ex -> { ex.sendResponseHeaders(503, -1); ex.close(); });
        healthy.createContext("/v1/transfers", ex -> {
            payloads.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] data = "{\"status\":\"applied\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, data.length);
            try (var out = ex.getResponseBody()) { out.write(data); }
        });
        unavailable.start(); healthy.start();
        try {
            var client = new LedgerClient("http://127.0.0.1:" + unavailable.getAddress().getPort() + ",http://127.0.0.1:" + healthy.getAddress().getPort());
            var result = client.post("/v1/transfers", new Accounting.Context("alice", "bob", 123, 0, "USD", "refund", "US"));
            assertEquals("applied", result.path("status").asText());
            var body = HttpClients.mapper().readTree(payloads.getFirst());
            assertEquals(123, body.path("amount_minor").asLong());
            assertFalse(body.has("amountMinor"));
        } finally { unavailable.stop(0); healthy.stop(0); }
    }

    @Test void streamRetriesUncommittedEventFromSameCursor() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var queries = new CopyOnWriteArrayList<String>();
        server.createContext("/v1/healthz", ex -> {
            byte[] data = "{\"state\":\"LEADER\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, data.length);
            try (var out = ex.getResponseBody()) { out.write(data); }
        });
        server.createContext("/v1/events/stream", ex -> {
            queries.add(ex.getRequestURI().getQuery());
            byte[] data = ": heartbeat\n\ndata: {\"raft_index\":5,\"kind\":\"account_created\",\"account_id\":\"alice\",\"currency\":\"USD\",\"timestamp\":\"2026-01-01T00:00:00Z\"}\n\n".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, data.length);
            try (var out = ex.getResponseBody()) { out.write(data); }
        });
        server.start();
        var index = new AtomicLong();
        var attempts = new AtomicInteger();
        var complete = new CountDownLatch(1);
        try (var follower = new EventFollower(new LedgerClient("http://127.0.0.1:" + server.getAddress().getPort()), HttpClients.mapper(), index::get, event -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("Simulated transaction rollback");
            index.set(event.raftIndex());
            complete.countDown();
        })) {
            assertTrue(complete.await(8, TimeUnit.SECONDS));
            assertEquals(5, index.get());
            assertEquals(List.of("from_index=1", "from_index=1"), queries.subList(0, 2));
        } finally { server.stop(0); }
    }
}
