package dev.ledgerline.gateway;

import dev.ledgerline.common.Accounting.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GatewayTest {
    GatewayController.Request request(long fee) {
        return new GatewayController.Request("txn", "alice", "bob", 1000, fee, "USD", "seller_payout", "US");
    }
    Journal journal(long fee) {
        var debit = new Line("alice", "debit", 1000, "2100", "CC-DEBIT", null);
        var credit = new Line("bob", "credit", 1000 - fee, "4000", "CC-CREDIT", null);
        return new Journal(fee == 0 ? List.of(debit, credit)
            : List.of(debit, credit, new Line("platform-fee-revenue", "credit", fee, "4900", "CC-FEE", null)));
    }
    @Test void noFeeUsesDebitDimensionsAndOriginalKey() {
        var result = GatewayController.buildTransfers(request(0), journal(0));
        assertEquals(1, result.size());
        assertEquals("txn", result.getFirst().dedupId());
        assertEquals("2100", result.getFirst().glAccount());
    }
    @Test void feesUseDeterministicIndependentKeys() {
        var result = GatewayController.buildTransfers(request(100), journal(100));
        assertEquals("txn-net", result.getFirst().dedupId());
        assertEquals(900, result.getFirst().amountMinor());
        assertEquals("txn-fee", result.get(1).dedupId());
        assertEquals("4900", result.get(1).glAccount());
        assertEquals("platform-fee-revenue", result.get(1).toAccount());
    }
    @Test void allFeeSkipsZeroNetLeg() {
        var result = GatewayController.buildTransfers(request(1000), journal(1000));
        assertEquals(1, result.size());
        assertEquals("txn-fee", result.getFirst().dedupId());
    }
    @Test void unbalancedOrMismatchedJournalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> GatewayController.buildTransfers(request(0),
            new Journal(List.of(new Line("alice", "debit", 1000, "1", null, null), new Line("bob", "credit", 999, "2", null, null)))));
        assertThrows(IllegalArgumentException.class, () -> GatewayController.buildTransfers(request(100), journal(0)));
    }
}
