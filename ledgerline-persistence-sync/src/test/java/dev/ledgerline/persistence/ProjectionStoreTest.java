package dev.ledgerline.persistence;

import dev.ledgerline.common.LedgerEvent;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProjectionStoreTest {
    LedgerEvent account(long index, String id) {
        return new LedgerEvent(index, "account_created", Instant.EPOCH, id, "USD", null, null, null, null, null, null, null);
    }
    LedgerEvent transfer(long index, String to) {
        return new LedgerEvent(index, "transfer_applied", Instant.EPOCH, null, null, "txn", "jrn", "bank", to, 100L, "2100", "SECRET");
    }
    @Test void journalBalancesAndCheckpointCommitTogetherWithIdempotentReplay() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var db = new JdbcTemplate(source);
        var store = new ProjectionStore(db, new DataSourceTransactionManager(source));
        store.migrate();
        store.apply(account(1, "bank")); store.apply(account(2, "alice"));
        store.apply(transfer(3, "alice")); store.apply(transfer(3, "alice"));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM journal_entries", Integer.class));
        assertEquals(100, db.queryForObject("SELECT balance_minor FROM account_balances WHERE account_id='alice'", Long.class));
        assertEquals(3, store.lastIndex());
        assertThrows(RuntimeException.class, () -> store.apply(transfer(4, "missing")));
        assertEquals(3, store.lastIndex());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM journal_entries", Integer.class));
        // Overflow after both journal inserts must roll back rows and the checkpoint.
        var overflow = new LedgerEvent(4, "transfer_applied", Instant.EPOCH, null, null, "overflow", "jrn4",
            "bank", "alice", Long.MAX_VALUE, "2100", "SECRET");
        assertThrows(RuntimeException.class, () -> store.apply(overflow));
        assertEquals(2, db.queryForObject("SELECT count(*) FROM journal_entries", Integer.class));
        assertEquals(3, store.lastIndex());
        // Any journal uniqueness violation must also leave balances and checkpoint unchanged.
        assertThrows(RuntimeException.class, () -> store.apply(transfer(5, "alice")));
        assertEquals(3, store.lastIndex());
        assertEquals(100, db.queryForObject("SELECT balance_minor FROM account_balances WHERE account_id='alice'", Long.class));
    }
}
