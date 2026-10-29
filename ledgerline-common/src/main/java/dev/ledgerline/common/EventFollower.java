package dev.ledgerline.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Resumes inclusively from the next index. Consumer must commit before its cursor advances. */
public final class EventFollower implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(EventFollower.class);
    private final Thread worker;
    private volatile boolean closed;
    private volatile InputStream stream;

    public EventFollower(LedgerClient ledger, ObjectMapper mapper, LongSupplier cursor, Consumer<LedgerEvent> consumer) {
        worker = Thread.ofVirtual().name("ledger-event-follower").unstarted(() -> {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            while (!closed) {
                try {
                    var request = HttpRequest.newBuilder(URI.create(ledger.leaderUrl()
                        + "/v1/events/stream?from_index=" + (cursor.getAsLong() + 1)))
                        .header("Accept", "text/event-stream").GET().build();
                    var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                    stream = response.body();
                    try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                        if (response.statusCode() != 200) throw new IllegalStateException("Event stream HTTP " + response.statusCode());
                        StringBuilder data = new StringBuilder();
                        String line;
                        while (!closed && (line = reader.readLine()) != null) {
                            if (line.isEmpty() && !data.isEmpty()) {
                                consumer.accept(mapper.readValue(data.toString(), LedgerEvent.class));
                                data.setLength(0);
                            } else if (line.startsWith("data:")) {
                                if (!data.isEmpty()) data.append('\n');
                                data.append(line.substring(5).stripLeading());
                            }
                        }
                    } finally { stream = null; }
                } catch (Exception e) {
                    if (!closed) LOG.warn("Event stream interrupted; retrying: {}", e.toString());
                }
                if (!closed) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            }
        });
        worker.start();
    }

    @Override
    public void close() {
        closed = true;
        worker.interrupt();
        InputStream current = stream;
        if (current != null) try { current.close(); } catch (java.io.IOException ignored) { }
    }
}
