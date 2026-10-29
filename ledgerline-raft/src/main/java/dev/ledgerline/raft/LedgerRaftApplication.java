package dev.ledgerline.raft;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Runs a single ledgerline-raft node. Run three of them (with the same
 * ledger.peers list, different ledger.node-id/ledger.raft-address/server.port)
 * to form a cluster -- see the repo README and ledgerline-raft's own README
 * for a worked three-node example.
 */
@SpringBootApplication
public class LedgerRaftApplication {
    public static void main(String[] args) {
        SpringApplication.run(LedgerRaftApplication.class, args);
    }
}
