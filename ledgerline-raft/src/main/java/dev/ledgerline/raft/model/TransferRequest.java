package dev.ledgerline.raft.model;

/**
 * The AssertTransfer command: an idempotent, conditional move of funds from
 * one account to another. dedupId makes retries safe -- submitting the same
 * dedupId twice returns the original result rather than applying the
 * transfer twice.
 */
public record TransferRequest(
    String dedupId,
    String fromAccount,
    String toAccount,
    long amountMinor,
    String currency,
    String glAccount,
    String costCenter) {
}
