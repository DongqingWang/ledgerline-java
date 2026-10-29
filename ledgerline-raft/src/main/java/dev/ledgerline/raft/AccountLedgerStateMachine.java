package dev.ledgerline.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.raft.command.ApplyResult;
import dev.ledgerline.raft.command.Command;
import dev.ledgerline.raft.model.Account;
import dev.ledgerline.raft.model.CreateAccountRequest;
import dev.ledgerline.raft.model.LedgerEvent;
import dev.ledgerline.raft.model.TransferRequest;
import dev.ledgerline.raft.model.TransferResult;
import org.apache.ratis.io.MD5Hash;
import org.apache.ratis.proto.RaftProtos.LogEntryProto;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.server.storage.FileInfo;
import org.apache.ratis.server.storage.RaftStorage;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;
import org.apache.ratis.statemachine.impl.SimpleStateMachineStorage;
import org.apache.ratis.statemachine.impl.SingleFileSnapshotInfo;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.apache.ratis.util.MD5FileUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The replicated state machine: the event-sourced heart of the engine.
 * Every balance change is first appended to the Raft log (by the leader,
 * replicated to a majority) and only then applied here -- on every node, in
 * the same deterministic order -- which is what makes the committed log
 * itself the durable replayable history rather than a side effect
 * bolted onto one. Direct Java port of the Go version's
 * {@code internal/fsm/fsm.go}, adapted to Apache Ratis's StateMachine API.
 */
public class AccountLedgerStateMachine extends BaseStateMachine {
    private static final Logger LOG = LoggerFactory.getLogger(AccountLedgerStateMachine.class);

    /** In-memory balance state for one account. */
    private record AccountState(String currency, long balanceMinor, boolean unlimited) {
    }

    /** What gets written to a snapshot file. */
    private record SnapshotState(Map<String, AccountState> accounts, Map<String, TransferResult> dedup,
                                 List<LedgerEvent> events) {
    }

    private final Object lock = new Object();
    private final Map<String, AccountState> accounts = new HashMap<>();
    // dedup maps a transfer's dedupId to the result it originally produced,
    // giving AssertTransfer exactly-once semantics under client retries.
    private final Map<String, TransferResult> dedup = new HashMap<>();

    private final ObjectMapper objectMapper;
    private final EventBus eventBus;
    private final SimpleStateMachineStorage storage = new SimpleStateMachineStorage();

    private volatile RaftServer server;
    private volatile RaftGroupId groupId;

    public AccountLedgerStateMachine(ObjectMapper objectMapper, EventBus eventBus) {
        this.objectMapper = objectMapper;
        this.eventBus = eventBus;
    }

    public EventBus events() {
        return eventBus;
    }

    /** Point-in-time local read. See the REST layer for the same caveat the Go version documents:
     * on a follower this can be slightly behind the leader. */
    public Account getAccount(String accountId) {
        synchronized (lock) {
            AccountState s = accounts.get(accountId);
            if (s == null) {
                return null;
            }
            return new Account(accountId, s.currency(), s.balanceMinor(), s.unlimited());
        }
    }

    /** Best-effort leader check for the observability endpoint, {@code GET /v1/leader}. */
    public boolean isLeader() {
        try {
            RaftServer.Division division = server.getDivision(groupId);
            return division.getInfo().isLeader();
        } catch (Exception e) {
            return false;
        }
    }

    public String currentLeaderId() {
        try {
            RaftServer.Division division = server.getDivision(groupId);
            var leaderId = division.getInfo().getLeaderId();
            return leaderId == null ? null : leaderId.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void initialize(RaftServer server, RaftGroupId groupId, RaftStorage raftStorage) throws IOException {
        super.initialize(server, groupId, raftStorage);
        this.server = server;
        this.groupId = groupId;
        storage.init(raftStorage);
        reinitialize();
    }

    @Override
    public void reinitialize() throws IOException {
        load(storage.loadLatestSnapshot());
    }

    @Override
    public SimpleStateMachineStorage getStateMachineStorage() {
        return storage;
    }

    @Override
    public long takeSnapshot() throws IOException {
        final TermIndex applied;
        final SnapshotState state;
        synchronized (lock) {
            applied = getLastAppliedTermIndex();
            state = new SnapshotState(new HashMap<>(accounts), new HashMap<>(dedup), eventBus.snapshot());
        }
        final File snapshotFile = storage.getSnapshotFile(applied.getTerm(), applied.getIndex());
        objectMapper.writeValue(snapshotFile, state);
        final MD5Hash md5 = MD5FileUtil.computeAndSaveMd5ForFile(snapshotFile);
        final FileInfo info = new FileInfo(snapshotFile.toPath(), md5);
        storage.updateLatestSnapshot(new SingleFileSnapshotInfo(info, applied));
        return applied.getIndex();
    }

    private void load(SingleFileSnapshotInfo snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        final Path path = snapshot.getFile().getPath();
        if (!Files.exists(path)) {
            LOG.warn("snapshot file {} does not exist for snapshot {}", path, snapshot);
            return;
        }
        final TermIndex last = SimpleStateMachineStorage.getTermIndexFromSnapshotFile(path.toFile());
        final SnapshotState state = objectMapper.readValue(path.toFile(), SnapshotState.class);
        synchronized (lock) {
            accounts.clear();
            accounts.putAll(state.accounts());
            dedup.clear();
            dedup.putAll(state.dedup());
            eventBus.restore(state.events());
            updateLastAppliedTermIndex(last);
        }
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        synchronized (lock) {
            return applyEntry(trx);
        }
    }

    private CompletableFuture<Message> applyEntry(TransactionContext trx) {
        final LogEntryProto entry = trx.getLogEntry();
        final TermIndex termIndex = TermIndex.valueOf(entry);
        final ApplyResult result;
        try {
            final byte[] data = entry.getStateMachineLogEntry().getLogData().toByteArray();
            final Command cmd = objectMapper.readValue(data, Command.class);
            result = switch (cmd.type()) {
                case CREATE_ACCOUNT -> applyCreateAccount(termIndex, cmd.createAccount(), Instant.ofEpochMilli(cmd.timestampMillis()));
                case ASSERT_TRANSFER -> applyAssertTransfer(termIndex, cmd.assertTransfer(), Instant.ofEpochMilli(cmd.timestampMillis()));
            };
        } catch (Exception e) {
            // A malformed log entry would be a programming bug in the writer, not a
            // recoverable business error -- but we still must not let this future fail,
            // since that could crash this replica's apply path while others continue,
            // causing divergence. Surface it as a result instead (mirrors the Go FSM).
            LOG.error("failed to decode/apply command at {}", termIndex, e);
            synchronized (lock) {
                updateLastAppliedTermIndex(termIndex);
            }
            return completed(ApplyResult.ofError("internal", "decode command: " + e.getMessage()));
        }
        synchronized (lock) {
            updateLastAppliedTermIndex(termIndex);
        }
        return completed(result);
    }

    private CompletableFuture<Message> completed(ApplyResult result) {
        try {
            final byte[] out = objectMapper.writeValueAsBytes(result);
            return CompletableFuture.completedFuture(Message.valueOf(ByteString.copyFrom(out)));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ApplyResult applyCreateAccount(TermIndex idx, CreateAccountRequest req, Instant timestamp) {
        if (req == null || blank(req.accountId()) || blank(req.currency())) {
            return ApplyResult.ofError("invalid_request", "account_id and currency are required");
        }
        synchronized (lock) {
            if (accounts.containsKey(req.accountId())) {
                return ApplyResult.ofError("account_exists", "account \"" + req.accountId() + "\" already exists");
            }
            accounts.put(req.accountId(), new AccountState(req.currency(), 0L, req.unlimited()));
        }
        eventBus.publish(LedgerEvent.accountCreated(idx.getIndex(), timestamp, req.accountId(), req.currency()));
        return ApplyResult.ofAccount(new Account(req.accountId(), req.currency(), 0L, req.unlimited()));
    }

    private ApplyResult applyAssertTransfer(TermIndex idx, TransferRequest req, Instant timestamp) {
        synchronized (lock) {
            if (req == null || blank(req.dedupId()) || blank(req.fromAccount()) || blank(req.toAccount())
                || blank(req.currency()) || req.fromAccount().equals(req.toAccount())) {
                return ApplyResult.ofError("invalid_request", "dedup_id, distinct accounts and currency are required");
            }
            final TransferResult prior = dedup.get(req.dedupId());
            if (prior != null) {
                return ApplyResult.ofTransfer(
                    new TransferResult(TransferResult.Status.DUPLICATE, prior.journalId(), prior.raftIndex()));
            }

            final AccountState from = accounts.get(req.fromAccount());
            if (from == null) {
                return ApplyResult.ofError("account_not_found", "from_account \"" + req.fromAccount() + "\" not found");
            }
            final AccountState to = accounts.get(req.toAccount());
            if (to == null) {
                return ApplyResult.ofError("account_not_found", "to_account \"" + req.toAccount() + "\" not found");
            }
            if (req.amountMinor() <= 0) {
                return ApplyResult.ofError("invalid_request", "amount_minor must be positive");
            }
            if (!from.currency().equals(req.currency()) || !to.currency().equals(req.currency())) {
                return ApplyResult.ofError("invalid_request", "currency mismatch between request and accounts");
            }
            if (!from.unlimited() && from.balanceMinor() < req.amountMinor()) {
                return ApplyResult.ofError("insufficient_funds", "account \"" + req.fromAccount() + "\" has insufficient balance");
            }

            // The double-entry invariant: the amount debited from one side always
            // equals the amount credited to the other, by construction -- there is
            // only ever one amount moved, never two independently-specified legs.
            final long fromBalance;
            final long toBalance;
            try {
                fromBalance = Math.subtractExact(from.balanceMinor(), req.amountMinor());
                toBalance = Math.addExact(to.balanceMinor(), req.amountMinor());
            } catch (ArithmeticException e) {
                return ApplyResult.ofError("invalid_request", "balance overflow");
            }
            accounts.put(req.fromAccount(), new AccountState(from.currency(), fromBalance, from.unlimited()));
            accounts.put(req.toAccount(), new AccountState(to.currency(), toBalance, to.unlimited()));

            final String journalId = "jrn-" + idx.getIndex();
            final TransferResult result = new TransferResult(TransferResult.Status.APPLIED, journalId, idx.getIndex());
            dedup.put(req.dedupId(), result);

            eventBus.publish(LedgerEvent.transferApplied(idx.getIndex(), timestamp, req.dedupId(), journalId,
                req.fromAccount(), req.toAccount(), req.amountMinor(), req.glAccount(), req.costCenter()));

            return ApplyResult.ofTransfer(result);
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
