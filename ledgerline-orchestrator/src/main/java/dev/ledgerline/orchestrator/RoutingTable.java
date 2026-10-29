package dev.ledgerline.orchestrator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
public class RoutingTable {
    public static final long MAX = 1L << 32;
    public record Shard(long rangeStart, long rangeEnd, List<String> ledgerNodes) {
        public Shard { ledgerNodes = List.copyOf(ledgerNodes); }
        public boolean contains(long key) { return key >= rangeStart && key < rangeEnd; }
    }
    private List<Shard> shards;
    public RoutingTable(@Value("${ledger.nodes:http://localhost:8001}") String nodes) {
        shards = List.of(new Shard(0, MAX, Arrays.asList(nodes.split(","))));
    }
    public static long hash(String id) {
        int value = 0x811c9dc5;
        for (byte b : id.getBytes(StandardCharsets.UTF_8)) { value ^= b & 0xff; value *= 0x01000193; }
        return Integer.toUnsignedLong(value);
    }
    public synchronized List<Shard> snapshot() { return List.copyOf(shards); }
    public synchronized Shard owner(long key) {
        return shards.stream().filter(s -> s.contains(key)).findFirst().orElseThrow(() -> new IllegalArgumentException("Key outside hash space"));
    }
    public synchronized void split(long point, List<String> nodes) {
        Shard old = owner(point);
        if (point == old.rangeStart() || nodes == null || nodes.isEmpty()) throw new IllegalArgumentException("Invalid split boundary or nodes");
        var next = new ArrayList<Shard>();
        for (Shard shard : shards) {
            if (shard == old) {
                next.add(new Shard(old.rangeStart(), point, old.ledgerNodes()));
                next.add(new Shard(point, old.rangeEnd(), nodes));
            } else next.add(shard);
        }
        shards = List.copyOf(next);
    }
}
