package dev.ledgerline.ai;

import dev.ledgerline.common.WebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(WebConfiguration.class)
public class AiGatewayApplication {
    public static void main(String[] args) { SpringApplication.run(AiGatewayApplication.class, args); }
}
