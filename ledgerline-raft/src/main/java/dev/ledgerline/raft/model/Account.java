package dev.ledgerline.raft.model;

/** The current balance state for a single account. */
public record Account(String accountId, String currency, long balanceMinor, boolean unlimited) {
}
