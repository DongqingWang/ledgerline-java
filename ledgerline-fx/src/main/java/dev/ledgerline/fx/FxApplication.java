package dev.ledgerline.fx;

import dev.ledgerline.common.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@org.springframework.boot.context.properties.EnableConfigurationProperties(FxRefreshProperties.class)
@Import({WebConfiguration.class, ApiErrors.class})
public class FxApplication {
    public static void main(String[] args) { SpringApplication.run(FxApplication.class, args); }
}
