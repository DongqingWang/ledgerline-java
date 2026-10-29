package dev.ledgerline.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@SpringBootApplication
@RestController
@Import({WebConfiguration.class, ApiErrors.class})
public class PersistenceApplication {
    private final ProjectionStore store;
    public PersistenceApplication(ProjectionStore store) { this.store = store; }
    public static void main(String[] args) { SpringApplication.run(PersistenceApplication.class, args); }
    @Bean(destroyMethod = "close")
    EventFollower follower(@Value("${ledger.nodes:http://localhost:8001}") String nodes, ObjectMapper mapper, ProjectionStore store) {
        store.migrate();
        return new EventFollower(new LedgerClient(nodes), mapper, store::lastIndex, store::apply);
    }
    @GetMapping("/healthz") public Map<String, Object> health() {
        return Map.of("status", "ok", "last_raft_index", store.lastIndex());
    }
}
