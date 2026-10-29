package dev.ledgerline.raft.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransferResult(Status status, String journalId, long raftIndex) {
    public enum Status {
        @JsonProperty("applied") APPLIED,
        @JsonProperty("duplicate") DUPLICATE
    }
}
