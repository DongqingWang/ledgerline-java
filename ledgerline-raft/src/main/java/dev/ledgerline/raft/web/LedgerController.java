package dev.ledgerline.raft.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.raft.AccountLedgerStateMachine;
import dev.ledgerline.raft.command.ApplyResult;
import dev.ledgerline.raft.command.Command;
import dev.ledgerline.raft.model.Account;
import dev.ledgerline.raft.model.CreateAccountRequest;
import dev.ledgerline.raft.model.ErrorBody;
import dev.ledgerline.raft.model.LeaderInfo;
import dev.ledgerline.raft.model.TransferRequest;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * The engine's HTTP API: account/transfer operations, a leader-observability
 * endpoint, and the committed-event stream. Direct port of the Go version's
 * {@code internal/httpapi}, with one deliberate difference: writes don't
 * need a 409/leader_hint dance, because this node's embedded Ratis
 * {@link RaftClient} (built against the full RaftGroup) transparently
 * routes to whichever node is actually leader.
 */
@RestController
@RequestMapping("/v1")
public class LedgerController {
    private static final Logger LOG = LoggerFactory.getLogger(LedgerController.class);

    private final RaftClient raftClient;
    private final AccountLedgerStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public LedgerController(RaftClient raftClient, AccountLedgerStateMachine stateMachine, ObjectMapper objectMapper) {
        this.raftClient = raftClient;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/accounts")
    public ResponseEntity<?> createAccount(@RequestBody CreateAccountRequest req) {
        if (isBlank(req.accountId()) || isBlank(req.currency())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "account_id and currency are required");
        }
        final ApplyResult result = submit(Command.createAccount(req));
        if (result == null) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "raft submit failed");
        }
        if (result.isError()) {
            return error(statusFor(result.errCode()), result.errCode(), result.errMsg());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.account());
    }

    @GetMapping("/accounts/{id}")
    public ResponseEntity<?> getAccount(@PathVariable String id) {
        // Local, eventually consistent read; even a leader-local read without
        // a quorum/read-index barrier must not be described as linearizable.
        final Account account = stateMachine.getAccount(id);
        if (account == null) {
            return error(HttpStatus.NOT_FOUND, "account_not_found", "account \"" + id + "\" not found");
        }
        return ResponseEntity.ok(account);
    }

    @PostMapping("/transfers")
    public ResponseEntity<?> transfer(@RequestBody TransferRequest req) {
        if (isBlank(req.dedupId()) || isBlank(req.fromAccount()) || isBlank(req.toAccount()) || req.amountMinor() <= 0) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request",
                "dedup_id, from_account, to_account are required and amount_minor must be positive");
        }
        final ApplyResult result = submit(Command.assertTransfer(req));
        if (result == null) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "raft submit failed");
        }
        if (result.isError()) {
            return error(statusFor(result.errCode()), result.errCode(), result.errMsg());
        }
        return ResponseEntity.ok(result.transfer());
    }

    @GetMapping("/leader")
    public ResponseEntity<?> leader() {
        final String leaderId = stateMachine.currentLeaderId();
        if (leaderId == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "internal", "no leader elected yet");
        }
        return ResponseEntity.ok(new LeaderInfo(leaderId, true));
    }

    @GetMapping("/healthz")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("state", stateMachine.isLeader() ? "LEADER" : "FOLLOWER"));
    }

    @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(name = "from_index", defaultValue = "0") long fromIndex) {
        final SseEmitter emitter = new SseEmitter(0L);
        stateMachine.events().subscribe(fromIndex, emitter);
        return emitter;
    }

    /** Bounded point-in-time history for demo migration; unlike SSE this has an explicit end. */
    @GetMapping("/events")
    public java.util.List<dev.ledgerline.raft.model.LedgerEvent> history() {
        return stateMachine.events().snapshot();
    }

    /** Submits a command through Ratis and waits for it to commit and apply, returning the typed ApplyResult. */
    private ApplyResult submit(Command cmd) {
        try {
            final byte[] data = objectMapper.writeValueAsBytes(cmd);
            final RaftClientReply reply = raftClient.io().send(Message.valueOf(ByteString.copyFrom(data)));
            if (!reply.isSuccess()) {
                LOG.error("raft client reply not successful: {}", reply);
                return null;
            }
            final byte[] out = reply.getMessage().getContent().toByteArray();
            return objectMapper.readValue(out, ApplyResult.class);
        } catch (Exception e) {
            LOG.error("failed to submit command", e);
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<ErrorBody> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ErrorBody.of(code, message));
    }

    private HttpStatus statusFor(String code) {
        return switch (code) {
            case "account_not_found" -> HttpStatus.NOT_FOUND;
            case "account_exists" -> HttpStatus.CONFLICT;
            case "insufficient_funds" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "invalid_request" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
