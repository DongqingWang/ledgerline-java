package dev.ledgerline.common;

import java.time.Instant;

public record LedgerEvent(long raftIndex, String kind, Instant timestamp, String accountId, String currency,
                          String dedupId, String journalId, String fromAccount, String toAccount,
                          Long amountMinor, String glAccount, String costCenter) {}
