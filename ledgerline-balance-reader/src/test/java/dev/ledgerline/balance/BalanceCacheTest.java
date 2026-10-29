package dev.ledgerline.balance;

import dev.ledgerline.common.LedgerEvent;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class BalanceCacheTest {
    LedgerEvent account(long index, String id) {
        return new LedgerEvent(index, "account_created", Instant.EPOCH, id, "USD", null, null, null, null, null, null, null);
    }
    LedgerEvent transfer(long index) {
        return new LedgerEvent(index, "transfer_applied", Instant.EPOCH, null, null, "txn", "jrn", "bank", "alice", 100L, "2100", null);
    }
    @Test void replayIsIdempotentAndDebitsEqualCredits() {
        var cache = new BalanceCache();
        cache.apply(account(1, "bank")); cache.apply(account(2, "alice"));
        cache.apply(transfer(3)); cache.apply(transfer(3)); cache.apply(account(2, "alice"));
        assertEquals(-100, cache.get("bank").balanceMinor());
        assertEquals(100, cache.get("alice").balanceMinor());
        assertEquals(3, cache.lastIndex());
    }
    @Test void incompleteHistoryDoesNotAdvanceCursor() {
        var cache = new BalanceCache();
        cache.apply(account(1, "bank"));
        assertThrows(IllegalStateException.class, () -> cache.apply(transfer(3)));
        assertEquals(1, cache.lastIndex());
        assertEquals(0, cache.get("bank").balanceMinor());
    }
}
