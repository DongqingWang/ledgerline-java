package dev.ledgerline.raft.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ledgerline.raft.model.Account;
import dev.ledgerline.raft.model.TransferResult;

/**
 * What applying a {@link Command} produces. Business-rule failures (account
 * not found, insufficient funds, ...) are reported here rather than as a
 * thrown exception, because applying a log entry must behave identically,
 * deterministically, on every replica -- an error here is just data,
 * computed the same way on every node. Mirrors the Go version's
 * {@code fsm.ApplyResult}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplyResult(Account account, TransferResult transfer, String errCode, String errMsg) {

    public static ApplyResult ofAccount(Account account) {
        return new ApplyResult(account, null, null, null);
    }

    public static ApplyResult ofTransfer(TransferResult transfer) {
        return new ApplyResult(null, transfer, null, null);
    }

    public static ApplyResult ofError(String code, String message) {
        return new ApplyResult(null, null, code, message);
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isError() {
        return errCode != null;
    }
}
