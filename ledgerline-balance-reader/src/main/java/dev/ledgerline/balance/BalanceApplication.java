package dev.ledgerline.balance;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ledgerline.common.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({WebConfiguration.class, ApiErrors.class})
public class BalanceApplication {
    public static void main(String[] args) { SpringApplication.run(BalanceApplication.class, args); }
    @Bean(destroyMethod = "close")
    EventFollower follower(@Value("${ledger.nodes:http://localhost:8001}") String nodes, ObjectMapper mapper, BalanceCache cache) {
        return new EventFollower(new LedgerClient(nodes), mapper, cache::lastIndex, cache::apply);
    }
}
