package dev.ledgerline.raft;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ledger.node-id: this node's Raft peer id (e.g. "n1").
 * ledger.raft-address: this node's own host:port for Ratis' gRPC transport.
 * ledger.peers: comma-separated id=host:port entries for every member of the
 *   group, including this one -- the SAME list on every node, which is how
 *   Apache Ratis differs from hashicorp/raft: there's no separate
 *   "bootstrap" step, every node just needs to agree on the group's initial
 *   peer set and Ratis runs leader election among them automatically.
 * ledger.storage-dir: directory for this node's Raft log/snapshot storage.
 */
@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(String nodeId, String raftAddress, String peers, String storageDir) {
}
