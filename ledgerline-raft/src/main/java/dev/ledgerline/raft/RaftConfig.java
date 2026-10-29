package dev.ledgerline.raft;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.grpc.GrpcConfigKeys;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftGroupId;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.server.RaftServer;
import org.apache.ratis.server.RaftServerConfigKeys;
import org.apache.ratis.util.NetUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Wires the Apache Ratis server + client beans for this node. Every node in
 * the cluster is configured with the identical {@link RaftGroup} (same
 * group id, same peer list) -- that shared configuration is what lets them
 * find each other and elect a leader automatically on startup, with no
 * separate bootstrap step (unlike the Go version's hashicorp/raft, which
 * requires exactly one node to call BootstrapCluster).
 */
@Configuration
@EnableConfigurationProperties(LedgerProperties.class)
public class RaftConfig {

    // Fixed, arbitrary group id shared by every node -- analogous to a
    // well-known cluster name. Any valid UUID works; it just has to match
    // across all peers in the group.
    private static final UUID GROUP_ID = UUID.fromString("c3b1a2d4-5e6f-47a8-9b0c-1d2e3f4a5b6c");

    @Bean
    public EventBus eventBus() {
        return new EventBus();
    }

    @Bean
    public List<RaftPeer> raftPeers(LedgerProperties props) {
        final List<RaftPeer> peers = new ArrayList<>();
        for (String entry : props.peers().split(",")) {
            final String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            final String[] parts = trimmed.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid ledger.peers entry %s, want id=host:port".formatted(trimmed));
            }
            peers.add(RaftPeer.newBuilder().setId(parts[0]).setAddress(parts[1]).build());
        }
        if (peers.isEmpty()) {
            throw new IllegalArgumentException("ledger.peers must list at least this node");
        }
        return peers;
    }

    @Bean
    public RaftGroup raftGroup(List<RaftPeer> raftPeers) {
        return RaftGroup.valueOf(RaftGroupId.valueOf(GROUP_ID), raftPeers);
    }

    @Bean
    public AccountLedgerStateMachine stateMachine(ObjectMapper objectMapper, EventBus eventBus) {
        return new AccountLedgerStateMachine(objectMapper, eventBus);
    }

    @Bean(destroyMethod = "close")
    public RaftServer raftServer(LedgerProperties props, RaftGroup raftGroup, AccountLedgerStateMachine stateMachine) throws Exception {
        final RaftProperties properties = new RaftProperties();
        RaftServerConfigKeys.setStorageDir(properties, Collections.singletonList(new File(props.storageDir())));
        final int port = NetUtils.createSocketAddr(props.raftAddress()).getPort();
        GrpcConfigKeys.Server.setPort(properties, port);

        final RaftServer server = RaftServer.newBuilder()
            // RECOVER also initializes a missing single-volume store. The Ratis
            // builder default is FORMAT, which refuses every subsequent restart.
            .setOption(org.apache.ratis.server.storage.RaftStorage.StartupOption.RECOVER)
            .setGroup(raftGroup)
            .setProperties(properties)
            .setServerId(RaftPeerId.valueOf(props.nodeId()))
            .setStateMachine(stateMachine)
            .build();
        try {
            server.start();
            return server;
        } catch (Exception e) {
            // Spring cannot destroy a bean that its factory never returned.
            try { server.close(); } catch (Exception closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    @Bean(destroyMethod = "close")
    public RaftClient raftClient(RaftGroup raftGroup) {
        // Built against the FULL group, so this client automatically
        // discovers and routes requests to whichever node is currently
        // leader -- no manual leader-hint/retry needed, unlike the Go
        // version's hand-written pkg/ledgerclient.
        return RaftClient.newBuilder()
            .setProperties(new RaftProperties())
            .setRaftGroup(raftGroup)
            .build();
    }
}
