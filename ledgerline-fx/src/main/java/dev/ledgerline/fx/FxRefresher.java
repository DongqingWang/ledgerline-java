package dev.ledgerline.fx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One non-overlapping refresh at startup, then fixed delay after each completed attempt. */
@Component
public class FxRefresher implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(FxRefresher.class);
    private final FxStore store;
    private final FxRateSource source;
    private final FxRefreshProperties properties;
    private ScheduledExecutorService scheduler;

    public FxRefresher(FxStore store, FxRateSource source, FxRefreshProperties properties) {
        this.store = store;
        this.source = source;
        this.properties = properties;
    }

    boolean refreshOnce() {
        try {
            store.replace(source.fetch(), Instant.now());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // Invalid responses never replace the last-known-good table or its timestamp.
            LOG.warn("FX refresh failed; retaining last-known-good rates ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public synchronized void start() {
        if (!properties.refreshEnabled() || isRunning()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("fx-refresh").factory());
        scheduler.scheduleWithFixedDelay(this::refreshOnce, 0, properties.refreshInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override public synchronized boolean isRunning() { return scheduler != null && !scheduler.isShutdown(); }
}
