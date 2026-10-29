package dev.ledgerline.orchestrator;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RoutingTableTest {
    @Test void fnvMatchesPublishedVectors() {
        assertEquals(0x811c9dc5L, RoutingTable.hash(""));
        assertEquals(0xe40c292cL, RoutingTable.hash("a"));
        assertEquals(0x4f9f2cabL, RoutingTable.hash("hello"));
    }
    @Test void splitCoversEntireUnsignedKeyspaceWithoutOverlap() {
        var table = new RoutingTable("http://old");
        table.split(1L << 31, List.of("http://new"));
        assertEquals(2, table.snapshot().size());
        assertEquals(List.of("http://old"), table.owner((1L << 31) - 1).ledgerNodes());
        assertEquals(List.of("http://new"), table.owner(1L << 31).ledgerNodes());
        assertEquals(RoutingTable.MAX, table.snapshot().getLast().rangeEnd());
        assertThrows(IllegalArgumentException.class, () -> table.split(0, List.of("x")));
        assertThrows(IllegalArgumentException.class, () -> table.split(RoutingTable.MAX, List.of("x")));
        assertEquals(2, table.snapshot().size());
    }
    @Test void splitRequiresExplicitOfflineAcknowledgment() {
        var table = new RoutingTable("http://old");
        var controller = new OrchestratorController(table);
        assertEquals(409, controller.split(new OrchestratorController.Split(100, List.of("http://new"), false)).getStatusCode().value());
        assertEquals(1, table.snapshot().size());
    }
}
