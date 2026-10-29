package dev.ledgerline.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.raft.command.ApplyResult;
import dev.ledgerline.raft.command.Command;
import dev.ledgerline.raft.model.CreateAccountRequest;
import dev.ledgerline.raft.model.TransferRequest;
import dev.ledgerline.raft.model.TransferResult;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.net.ServerSocket;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RaftRecoveryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private ApplyResult send(RaftClient client, Command command) throws Exception {
        var reply = client.io().send(Message.valueOf(ByteString.copyFrom(mapper.writeValueAsBytes(command))));
        assertTrue(reply.isSuccess());
        return mapper.readValue(reply.getMessage().getContent().toByteArray(), ApplyResult.class);
    }

    @Test
    @Timeout(45)
    void realRatisStartsWithMetricsAndRecoversItsExistingLog(@TempDir Path directory) throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var props = new LedgerProperties("n1", "localhost:" + port, "n1=localhost:" + port, directory.toString());
        var config = new RaftConfig();
        var group = config.raftGroup(config.raftPeers(props));
        var original = new AccountLedgerStateMachine(mapper, new EventBus());
        var transfer = new TransferRequest("recovery-transfer", "bank", "alice", 123, "USD", null, null);
        try (var server = config.raftServer(props, group, original); var client = config.raftClient(group)) {
            assertFalse(send(client, Command.createAccount(new CreateAccountRequest("bank", "USD", true))).isError());
            assertFalse(send(client, Command.createAccount(new CreateAccountRequest("alice", "USD", false))).isError());
            assertEquals(TransferResult.Status.APPLIED, send(client, Command.assertTransfer(transfer)).transfer().status());
        }
        var restored = new AccountLedgerStateMachine(mapper, new EventBus());
        try (var server = config.raftServer(props, group, restored); var client = config.raftClient(group)) {
            assertEquals(TransferResult.Status.DUPLICATE, send(client, Command.assertTransfer(transfer)).transfer().status());
            assertEquals(123, restored.getAccount("alice").balanceMinor());
            assertEquals(-123, restored.getAccount("bank").balanceMinor());
            assertEquals(original.events().snapshot(), restored.events().snapshot());
        }
    }
}
