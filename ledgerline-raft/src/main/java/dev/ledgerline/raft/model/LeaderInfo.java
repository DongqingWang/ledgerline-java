package dev.ledgerline.raft.model;

/**
 * Reports the engine's current known raft leader, for observability.
 * Unlike the Go/hashicorp-raft version of this project, callers don't need
 * this to implement their own retry: any node's HTTP endpoint accepts
 * writes and this node's own embedded Ratis RaftClient (built against the
 * full RaftGroup) transparently routes the request to the real leader.
 */
public record LeaderInfo(String leaderId, boolean leaderKnown) {
}
