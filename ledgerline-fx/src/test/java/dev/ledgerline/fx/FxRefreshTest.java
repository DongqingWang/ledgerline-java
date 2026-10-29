package dev.ledgerline.fx;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerline.common.HttpClients;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FxRefreshTest {
    FxStore store() throws Exception { return new FxStore(HttpClients.mapper(), new ClassPathResource("rates_seed.json")); }
    @Test void refreshIsDisabledByDefaultAndNeverCallsSource() throws Exception {
        var calls = new AtomicInteger();
        var refresher = new FxRefresher(store(), () -> { calls.incrementAndGet(); return Map.of(); }, new FxRefreshProperties(false, null, null, null));
        refresher.start();
        assertFalse(refresher.isRunning());
        assertEquals(0, calls.get());
        refresher.stop();
    }
    @Test void failedFetchOrInvalidTableRetainsLastGoodValuesAndTimestamp() throws Exception {
        var store = store();
        var baseline = store.snapshot();
        var failure = new FxRefresher(store, () -> { throw new IOException("offline"); }, new FxRefreshProperties(true, null, null, null));
        assertFalse(failure.refreshOnce());
        assertSame(baseline, store.snapshot());
        var invalid = new FxRefresher(store, () -> Map.of("USD", BigDecimal.TEN), new FxRefreshProperties(true, null, null, null));
        assertFalse(invalid.refreshOnce());
        assertSame(baseline, store.snapshot());
    }
    @Test void scheduledRefreshStartsImmediatelyAndCanBeStopped() throws Exception {
        var store = store();
        var called = new CountDownLatch(1);
        var refresher = new FxRefresher(store, () -> {
            called.countDown();
            return Map.of("USD", BigDecimal.ONE, "EUR", new BigDecimal("0.8"));
        }, new FxRefreshProperties(true, Duration.ofHours(1), null, null));
        try {
            refresher.start();
            assertTrue(called.await(3, TimeUnit.SECONDS));
            // Calling this synchronously also checks successful validation/publication.
            assertTrue(refresher.refreshOnce());
            assertEquals(80, store.convert("USD", "EUR", 100));
            assertTrue(refresher.isRunning());
        } finally { refresher.stop(); }
        assertFalse(refresher.isRunning());
    }
    @Test void liveHttpSourceValidatesStatusJsonResultAndBase() throws Exception {
        var status = new AtomicInteger(200);
        var body = new AtomicReference<>("{\"result\":\"success\",\"base_code\":\"USD\",\"rates\":{\"USD\":1,\"EUR\":0.8},\"extra_metadata\":true}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rates", exchange -> {
            byte[] data = body.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), data.length);
            try (var output = exchange.getResponseBody()) { output.write(data); }
        });
        server.start();
        try {
            var properties = new FxRefreshProperties(true, null, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/rates"), Duration.ofSeconds(2));
            var source = new LiveRateSource(HttpClients.mapper(), properties);
            assertEquals(new BigDecimal("0.8"), source.fetch().get("EUR"));
            for (String invalid : new String[]{"not-json", "null", "{\"result\":\"error\"}",
                "{\"result\":\"success\",\"base_code\":\"EUR\",\"rates\":{\"EUR\":1}}",
                "{\"result\":\"success\",\"base_code\":\"USD\",\"rates\":{}}"}) {
                body.set(invalid);
                assertThrows(IOException.class, source::fetch);
            }
            status.set(503);
            assertThrows(IOException.class, source::fetch);
        } finally { server.stop(0); }
    }
    @Test void invalidIntervalsAndEndpointsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FxRefreshProperties(true, Duration.ZERO, null, null));
        assertThrows(IllegalArgumentException.class, () -> new FxRefreshProperties(true, null, null, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new FxRefreshProperties(true, null, URI.create("file:/etc/passwd"), null));
        assertThrows(IllegalArgumentException.class, () -> new FxRefreshProperties(true, null, URI.create("https://user:secret@example.com/rates"), null));
    }
    @Test void timeoutAlsoBoundsSlowResponseBodies() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 100);
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var source = new LiveRateSource(HttpClients.mapper(), new FxRefreshProperties(true, null,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow"), Duration.ofMillis(150)));
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(IOException.class, source::fetch));
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void quoteCapturesRateAndTimestampFromOneTable() throws Exception {
        var store = store();
        var quote = store.quote("USD", "EUR");
        store.replace(Map.of("USD", BigDecimal.ONE, "EUR", new BigDecimal("0.5")), java.time.Instant.now().plusSeconds(10));
        assertEquals(92, FxStore.convertMinor(100, quote.rate()));
        assertNotEquals(quote.asOf(), store.snapshot().asOf());
    }
}
