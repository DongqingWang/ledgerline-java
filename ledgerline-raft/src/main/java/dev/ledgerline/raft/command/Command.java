package dev.ledgerline.raft.command;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ledgerline.raft.model.CreateAccountRequest;
import dev.ledgerline.raft.model.TransferRequest;

/**
 * The single envelope type serialized into every Ratis log entry. Keeping
 * one envelope (rather than raw per-command bytes) makes it trivial to add
 * new command types later without touching the Ratis plumbing -- mirrors
 * the Go version's {@code fsm.Command}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Command(Type type, CreateAccountRequest createAccount, TransferRequest assertTransfer, long timestampMillis) {

    public enum Type { CREATE_ACCOUNT, ASSERT_TRANSFER }

    public static Command createAccount(CreateAccountRequest req) {
        return new Command(Type.CREATE_ACCOUNT, req, null, System.currentTimeMillis());
    }

    public static Command assertTransfer(TransferRequest req) {
        return new Command(Type.ASSERT_TRANSFER, null, req, System.currentTimeMillis());
    }
}
