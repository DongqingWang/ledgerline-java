package dev.ledgerline.raft.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * One entry on the engine's committed-event stream. This is the CQRS
 * fan-out tap that ledgerline-balance-reader and ledgerline-persistence-sync
 * both subscribe to via the SSE stream (newline-free, one JSON object per
 * "message" event).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LedgerEvent(
    long raftIndex,
    Kind kind,
    Instant timestamp,
    // Populated for ACCOUNT_CREATED.
    String accountId,
    String currency,
    // Populated for TRANSFER_APPLIED.
    String dedupId,
    String journalId,
    String fromAccount,
    String toAccount,
    Long amountMinor,
    String glAccount,
    String costCenter) {

    public enum Kind {
        @JsonProperty("account_created") ACCOUNT_CREATED,
        @JsonProperty("transfer_applied") TRANSFER_APPLIED
    }

    public static LedgerEvent accountCreated(long raftIndex, Instant ts, String accountId, String currency) {
        return new LedgerEvent(raftIndex, Kind.ACCOUNT_CREATED, ts, accountId, currency,
            null, null, null, null, null, null, null);
    }

    public static LedgerEvent transferApplied(long raftIndex, Instant ts, String dedupId, String journalId,
            String fromAccount, String toAccount, long amountMinor, String glAccount, String costCenter) {
        return new LedgerEvent(raftIndex, Kind.TRANSFER_APPLIED, ts, null, null,
            dedupId, journalId, fromAccount, toAccount, amountMinor, glAccount, costCenter);
    }
}
