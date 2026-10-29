package dev.ledgerline.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.raft.command.ApplyResult;
import dev.ledgerline.raft.command.Command;
import dev.ledgerline.raft.model.Account;
import dev.ledgerline.raft.model.CreateAccountRequest;
import dev.ledgerline.raft.model.TransferRequest;
import dev.ledgerline.raft.model.TransferResult;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.proto.RaftProtos.StateMachineLogEntryProto;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class AccountLedgerStateMachineTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private AccountLedgerStateMachine newStateMachine() {
        return new AccountLedgerStateMachine(objectMapper, new EventBus());
    }

    private ApplyResult apply(AccountLedgerStateMachine sm, long index, Command cmd) throws Exception {
        final byte[] data = objectMapper.writeValueAsBytes(cmd);
        final StateMachineLogEntryProto smEntry = StateMachineLogEntryProto.newBuilder()
            .setLogData(ByteString.copyFrom(data))
            .build();
        final LogEntryProto entry = LogEntryProto.newBuilder()
            .setTerm(1)
            .setIndex(index)
            .setStateMachineLogEntry(smEntry)
            .build();
        final TransactionContext trx = new TransactionContext.Builder()
            .setServerRole(org.apache.ratis.proto.RaftProtos.RaftPeerRole.FOLLOWER)
            .setStateMachine(sm)
            .setLogEntry(entry)
            .build();
        final CompletableFuture<?> future = sm.applyTransaction(trx);
        final org.apache.ratis.protocol.Message message = (org.apache.ratis.protocol.Message) future.get();
        return objectMapper.readValue(message.getContent().toByteArray(), ApplyResult.class);
    }

    private ApplyResult createAccount(AccountLedgerStateMachine sm, long index, String id, String currency) throws Exception {
        return apply(sm, index, Command.createAccount(new CreateAccountRequest(id, currency, false)));
    }

    private ApplyResult createUnlimitedAccount(AccountLedgerStateMachine sm, long index, String id, String currency) throws Exception {
        return apply(sm, index, Command.createAccount(new CreateAccountRequest(id, currency, true)));
    }

    private ApplyResult fund(AccountLedgerStateMachine sm, long index, String from, String to, long amount) throws Exception {
        final ApplyResult result = apply(sm, index, Command.assertTransfer(
            new TransferRequest("seed-" + to, from, to, amount, "USD", null, null)));
        assertFalse(result.isError(), "seed transfer failed: " + result.errCode() + " " + result.errMsg());
        return result;
    }

    @Test
    void createAccount_startsAtZeroBalance_andRejectsDuplicates() throws Exception {
        final AccountLedgerStateMachine sm = newStateMachine();

        final ApplyResult res = createAccount(sm, 1, "acct-a", "USD");
        assertFalse(res.isError());
        assertEquals(0L, res.account().balanceMinor());

        final ApplyResult dup = createAccount(sm, 2, "acct-a", "USD");
        assertEquals("account_exists", dup.errCode());
    }

    @Test
    void assertTransfer_happyPath_debitsAndCreditsExactly() throws Exception {
        final AccountLedgerStateMachine sm = newStateMachine();
        createUnlimitedAccount(sm, 1, "external-deposit", "USD");
        createAccount(sm, 2, "alice", "USD");
        createAccount(sm, 3, "bob", "USD");
        fund(sm, 4, "external-deposit", "alice", 10_000);

        final ApplyResult res = apply(sm, 5, Command.assertTransfer(
            new TransferRequest("txn-1", "alice", "bob", 2_500, "USD", "4000", "CC-1")));
        assertFalse(res.isError());
        assertEquals(TransferResult.Status.APPLIED, res.transfer().status());
        assertNotNull(res.transfer().journalId());

        final Account alice = sm.getAccount("alice");
        final Account bob = sm.getAccount("bob");
        assertEquals(7_500L, alice.balanceMinor());
        assertEquals(2_500L, bob.balanceMinor());
        // Double-entry invariant: what alice lost, bob gained, exactly.
        assertEquals(10_000L - alice.balanceMinor(), bob.balanceMinor());
    }

    @Test
    void assertTransfer_duplicateDedupId_isIdempotent() throws Exception {
        final AccountLedgerStateMachine sm = newStateMachine();
        createUnlimitedAccount(sm, 1, "external-deposit", "USD");
        createAccount(sm, 2, "alice", "USD");
        createAccount(sm, 3, "bob", "USD");
        fund(sm, 4, "external-deposit", "alice", 1_000);

        final TransferRequest req = new TransferRequest("retry-me", "alice", "bob", 500, "USD", null, null);
        final ApplyResult first = apply(sm, 5, Command.assertTransfer(req));
        assertEquals(TransferResult.Status.APPLIED, first.transfer().status());

        final ApplyResult second = apply(sm, 6, Command.assertTransfer(req));
        assertEquals(TransferResult.Status.DUPLICATE, second.transfer().status());
        assertEquals(first.transfer().journalId(), second.transfer().journalId());

        assertEquals(500L, sm.getAccount("alice").balanceMinor());
        assertEquals(500L, sm.getAccount("bob").balanceMinor());
    }

    @Test
    void assertTransfer_insufficientFunds_isRejected() throws Exception {
        final AccountLedgerStateMachine sm = newStateMachine();
        createAccount(sm, 1, "alice", "USD");
        createAccount(sm, 2, "bob", "USD");

        final ApplyResult res = apply(sm, 3, Command.assertTransfer(
            new TransferRequest("txn-1", "alice", "bob", 100, "USD", null, null)));
        assertEquals("insufficient_funds", res.errCode());
    }

    @Test
    void assertTransfer_unlimitedAccountCanGoNegative() throws Exception {
        final AccountLedgerStateMachine sm = newStateMachine();
        createUnlimitedAccount(sm, 1, "external-deposit", "USD");
        createAccount(sm, 2, "alice", "USD");

        final ApplyResult res = apply(sm, 3, Command.assertTransfer(
            new TransferRequest("txn-1", "external-deposit", "alice", 1_000_000, "USD", null, null)));
        assertFalse(res.isError());
        assertEquals(-1_000_000L, sm.getAccount("external-deposit").balanceMinor());
    }

    @Test
    void assertTransfer_accountNotFound() throws Exception {
        final AccountLedgerStateMachine sm = newStateMachine();
        createAccount(sm, 1, "alice", "USD");

        final ApplyResult res = apply(sm, 2, Command.assertTransfer(
            new TransferRequest("txn-1", "alice", "ghost", 100, "USD", null, null)));
        assertEquals("account_not_found", res.errCode());
    }

    @Test
    void selfTransferCannotCreateMoney() throws Exception {
        var sm = newStateMachine();
        createUnlimitedAccount(sm, 1, "bank", "USD");
        createAccount(sm, 2, "alice", "USD");
        fund(sm, 3, "bank", "alice", 100);
        var result = apply(sm, 4, Command.assertTransfer(new TransferRequest("self", "alice", "alice", 50, "USD", null, null)));
        assertEquals("invalid_request", result.errCode());
        assertEquals(100, sm.getAccount("alice").balanceMinor());
        assertEquals(3, sm.events().snapshot().size());
    }

    @Test
    void overflowIsRejectedWithoutPartialMutation() throws Exception {
        var sm = newStateMachine();
        createUnlimitedAccount(sm, 1, "bank", "USD");
        createAccount(sm, 2, "alice", "USD");
        fund(sm, 3, "bank", "alice", Long.MAX_VALUE);
        var result = apply(sm, 4, Command.assertTransfer(new TransferRequest("overflow", "bank", "alice", 1, "USD", null, null)));
        assertEquals("invalid_request", result.errCode());
        assertEquals(-Long.MAX_VALUE, sm.getAccount("bank").balanceMinor());
        assertEquals(Long.MAX_VALUE, sm.getAccount("alice").balanceMinor());
    }

    @Test
    void replicasPublishIdenticalTimestamps() throws Exception {
        var a = newStateMachine();
        var b = newStateMachine();
        var command = Command.createAccount(new CreateAccountRequest("alice", "USD", false));
        apply(a, 1, command);
        apply(b, 1, command);
        assertEquals(a.events().snapshot(), b.events().snapshot());
    }

    @Test
    void currencyMismatchDoesNotMoveFunds() throws Exception {
        var sm = newStateMachine();
        createUnlimitedAccount(sm, 1, "bank", "USD");
        createAccount(sm, 2, "alice", "EUR");
        var result = apply(sm, 3, Command.assertTransfer(new TransferRequest("fx", "bank", "alice", 10, "USD", null, null)));
        assertEquals("invalid_request", result.errCode());
        assertEquals(0, sm.getAccount("bank").balanceMinor());
    }

    @Test
    void snapshotRestoresBalancesDedupAndReplayHistory(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        try (var storage = org.apache.ratis.server.storage.RaftStorage.newBuilder()
            .setDirectory(directory.toFile())
            .setOption(org.apache.ratis.server.storage.RaftStorage.StartupOption.FORMAT)
            .setStorageFreeSpaceMin(org.apache.ratis.util.SizeInBytes.valueOf(0))
            .build()) {
            storage.initialize();
            var original = newStateMachine();
            original.getStateMachineStorage().init(storage);
            createUnlimitedAccount(original, 1, "bank", "USD");
            createAccount(original, 2, "alice", "USD");
            fund(original, 3, "bank", "alice", 100);
            assertEquals(3, original.takeSnapshot());
            var restored = newStateMachine();
            restored.getStateMachineStorage().init(storage);
            restored.reinitialize();
            assertEquals(100, restored.getAccount("alice").balanceMinor());
            assertEquals(original.events().snapshot(), restored.events().snapshot());
            assertEquals(3, restored.getLastAppliedTermIndex().getIndex());
            var duplicate = apply(restored, 4, Command.assertTransfer(new TransferRequest("seed-alice", "bank", "alice", 100, "USD", null, null)));
            assertEquals(TransferResult.Status.DUPLICATE, duplicate.transfer().status());
            assertEquals(100, restored.getAccount("alice").balanceMinor());
            assertEquals(3, restored.events().snapshot().size());
        }
    }

}
