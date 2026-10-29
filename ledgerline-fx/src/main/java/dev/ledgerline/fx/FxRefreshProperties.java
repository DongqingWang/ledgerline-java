package dev.ledgerline.fx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("ledger.fx")
public record FxRefreshProperties(boolean refreshEnabled, Duration refreshInterval, URI refreshEndpoint, Duration refreshTimeout) {
    public FxRefreshProperties {
        if (refreshInterval == null) refreshInterval = Duration.ofMinutes(15);
        if (refreshEndpoint == null) refreshEndpoint = URI.create("https://open.er-api.com/v6/latest/USD");
        if (refreshTimeout == null) refreshTimeout = Duration.ofSeconds(10);
        if (refreshInterval.isNegative() || refreshInterval.toMillis() < 1)
            throw new IllegalArgumentException("FX refresh interval must be at least 1ms");
        if (refreshTimeout.isNegative() || refreshTimeout.toMillis() < 1)
            throw new IllegalArgumentException("FX refresh timeout must be at least 1ms");
        if (!("https".equals(refreshEndpoint.getScheme()) || "http".equals(refreshEndpoint.getScheme()))
            || refreshEndpoint.getHost() == null || refreshEndpoint.getUserInfo() != null || refreshEndpoint.getFragment() != null)
            throw new IllegalArgumentException("FX endpoint must be an HTTP(S) URL without credentials or fragment");
    }
}
