package dev.ledgerline.raft.model;

/**
 * Creates a new zero-balance account. {@code unlimited} marks a
 * clearing/external-funding account (e.g. "external-deposit") that is
 * allowed to go negative -- the standard double-entry way money enters or
 * leaves the ledger from outside the system, rather than a special-cased
 * "mint" bypass.
 */
public record CreateAccountRequest(String accountId, String currency, boolean unlimited) {
}
