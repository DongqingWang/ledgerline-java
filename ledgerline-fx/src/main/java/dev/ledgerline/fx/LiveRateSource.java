package dev.ledgerline.fx;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Same USD-anchored public response contract as the Go source. No network I/O in the constructor. */
@Component
public class LiveRateSource implements FxRateSource {
    private record Response(String result, String baseCode, Map<String, BigDecimal> rates) {}
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final FxRefreshProperties properties;

    public LiveRateSource(ObjectMapper mapper, FxRefreshProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
        client = HttpClient.newBuilder().connectTimeout(properties.refreshTimeout()).build();
    }

    @Override
    public Map<String, BigDecimal> fetch() throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(properties.refreshEndpoint()).timeout(properties.refreshTimeout())
            .header("Accept", "application/json").GET().build();
        // Bound the entire body download, not only time to receive response headers.
        var pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
        final HttpResponse<byte[]> response;
        try { response = pending.get(properties.refreshTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException e) {
            pending.cancel(true);
            throw new java.net.http.HttpTimeoutException("FX response exceeded refresh timeout");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException("FX source request failed", e.getCause());
        } catch (InterruptedException e) {
            pending.cancel(true);
            throw e;
        }
        if (response.statusCode() != 200) throw new IOException("FX source returned HTTP " + response.statusCode());
        var payload = mapper.readValue(response.body(), Response.class);
        if (payload == null || !"success".equals(payload.result()) || !"USD".equals(payload.baseCode())
            || payload.rates() == null || payload.rates().isEmpty())
            throw new IOException("FX source must return success with a nonempty USD-anchored rate table");
        return payload.rates();
    }
}
