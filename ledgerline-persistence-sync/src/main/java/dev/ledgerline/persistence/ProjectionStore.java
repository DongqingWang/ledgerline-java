package dev.ledgerline.persistence;

import dev.ledgerline.common.LedgerEvent;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;

@Service
public class ProjectionStore {
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    public ProjectionStore(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db;
        this.transactions = new TransactionTemplate(manager);
    }
    public void migrate() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(java.util.Objects.requireNonNull(db.getDataSource()));
    }
    public long lastIndex() {
        return db.queryForObject("SELECT last_raft_index FROM sync_state WHERE id=1", Long.class);
    }
    public void apply(LedgerEvent ev) {
        transactions.executeWithoutResult(tx -> {
            // Serializes multiple consumers, and commits the cursor with all projection changes.
            long previous = db.queryForObject("SELECT last_raft_index FROM sync_state WHERE id=1 FOR UPDATE", Long.class);
            if (ev.raftIndex() <= previous) return;
            switch (ev.kind()) {
                case "account_created" -> {
                    Integer count = db.queryForObject("SELECT count(*) FROM account_balances WHERE account_id=?", Integer.class, ev.accountId());
                    if (count == 0) db.update("INSERT INTO account_balances(account_id,currency,balance_minor,last_raft_index) VALUES(?,?,0,?)",
                        ev.accountId(), ev.currency(), ev.raftIndex());
                }
                case "transfer_applied" -> {
                    String currency = db.queryForObject("SELECT currency FROM account_balances WHERE account_id=?", String.class, ev.fromAccount());
                    String toCurrency = db.queryForObject("SELECT currency FROM account_balances WHERE account_id=?", String.class, ev.toAccount());
                    if (currency == null || !currency.equals(toCurrency) || ev.amountMinor() == null || ev.amountMinor() <= 0
                        || ev.fromAccount().equals(ev.toAccount())) throw new IllegalArgumentException("Invalid transfer event");
                    journal(ev, ev.fromAccount(), "debit", currency);
                    journal(ev, ev.toAccount(), "credit", currency);
                    delta(ev.fromAccount(), -ev.amountMinor(), ev.raftIndex());
                    delta(ev.toAccount(), ev.amountMinor(), ev.raftIndex());
                }
                default -> throw new IllegalArgumentException("Unknown event kind: " + ev.kind());
            }
            db.update("UPDATE sync_state SET last_raft_index=? WHERE id=1", ev.raftIndex());
        });
    }
    private void delta(String id, long amount, long index) {
        int changed = db.update("UPDATE account_balances SET balance_minor=balance_minor+?,last_raft_index=?,updated_at=CURRENT_TIMESTAMP WHERE account_id=?", amount, index, id);
        if (changed != 1) throw new IllegalStateException("Missing account: " + id);
    }
    private void journal(LedgerEvent ev, String id, String direction, String currency) {
        db.update("INSERT INTO journal_entries(transfer_dedup_id,account_id,direction,amount_minor,currency,gl_account,cost_center,posted_at,raft_index) VALUES(?,?,?,?,?,?,?,?,?)",
            ev.dedupId(), id, direction, ev.amountMinor(), currency, ev.glAccount(), ev.costCenter(), Timestamp.from(ev.timestamp()), ev.raftIndex());
    }
}
