package dev.ledgerline.raft;

import dev.ledgerline.raft.model.LedgerEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The CQRS fan-out point: every applied command publishes one LedgerEvent
 * here, and any number of SSE subscribers (ledgerline-balance-reader,
 * ledgerline-persistence-sync) can replay from a given raft index and then
 * keep receiving new events live. Mirrors the Go version's
 * {@code internal/fsm/eventbus.go}.
 *
 * <p>Keeping the full history in memory is a deliberate demo-scale
 * simplification, same as the Go version -- a production system would
 * window/compact this or replay from the durable store instead of RAM.
 */
public class EventBus {
    private final List<LedgerEvent> history = new ArrayList<>();
    private final Map<Long, SseEmitter> subscribers = new HashMap<>();
    private long nextSubId = 0;

    public synchronized List<LedgerEvent> snapshot() {
        return List.copyOf(history);
    }

    public synchronized void restore(List<LedgerEvent> events) {
        history.clear();
        if (events != null) history.addAll(events);
    }

    public synchronized void publish(LedgerEvent event) {
        history.add(event);
        for (Map.Entry<Long, SseEmitter> entry : new ArrayList<>(subscribers.entrySet())) {
            try {
                entry.getValue().send(event, org.springframework.http.MediaType.APPLICATION_JSON);
            } catch (IOException | IllegalStateException e) {
                subscribers.remove(entry.getKey());
            }
        }
    }

    /**
     * Registers an emitter and replays every event with raftIndex &gt;=
     * fromIndex before returning, so the caller sees a consistent
     * backlog-then-live stream with no gap or duplicate.
     */
    public synchronized void subscribe(long fromIndex, SseEmitter emitter) {
        long id = nextSubId++;
        subscribers.put(id, emitter);
        emitter.onCompletion(() -> removeSubscriber(id));
        emitter.onTimeout(() -> removeSubscriber(id));
        emitter.onError(t -> removeSubscriber(id));

        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            removeSubscriber(id);
            return;
        }

        for (LedgerEvent event : history) {
            if (event.raftIndex() >= fromIndex) {
                try {
                    emitter.send(event, org.springframework.http.MediaType.APPLICATION_JSON);
                } catch (IOException e) {
                    removeSubscriber(id);
                    return;
                }
            }
        }
    }

    private synchronized void removeSubscriber(long id) {
        subscribers.remove(id);
    }
}
