package dev.ledgerline.balance;

import dev.ledgerline.common.LedgerEvent;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class BalanceCache {
    public record Balance(String accountId, String currency, long balanceMinor, long lastRaftIndex) {}
    private final Map<String, Balance> accounts = new TreeMap<>();
    private long index;

    public synchronized void apply(LedgerEvent event) {
        if (event.raftIndex() <= index) return;
        switch (event.kind()) {
            case "account_created" -> accounts.putIfAbsent(event.accountId(), new Balance(event.accountId(), event.currency(), 0, event.raftIndex()));
            case "transfer_applied" -> {
                Balance from = require(event.fromAccount());
                Balance to = require(event.toAccount());
                long debit = Math.subtractExact(from.balanceMinor(), event.amountMinor());
                long credit = Math.addExact(to.balanceMinor(), event.amountMinor());
                accounts.put(from.accountId(), new Balance(from.accountId(), from.currency(), debit, event.raftIndex()));
                accounts.put(to.accountId(), new Balance(to.accountId(), to.currency(), credit, event.raftIndex()));
            }
            default -> throw new IllegalArgumentException("Unknown event kind: " + event.kind());
        }
        index = event.raftIndex();
    }
    private Balance require(String id) {
        Balance account = accounts.get(id);
        if (account == null) throw new IllegalStateException("Missing account event: " + id);
        return account;
    }
    public synchronized long lastIndex() { return index; }
    public synchronized Balance get(String id) { return accounts.get(id); }
    public synchronized List<Balance> all() { return List.copyOf(accounts.values()); }
}
