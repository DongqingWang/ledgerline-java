package dev.ledgerline.orchestrator;

import dev.ledgerline.common.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({WebConfiguration.class, ApiErrors.class})
public class OrchestratorApplication {
    public static void main(String[] args) { SpringApplication.run(OrchestratorApplication.class, args); }
}
