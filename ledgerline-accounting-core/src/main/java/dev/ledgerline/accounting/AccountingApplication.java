package dev.ledgerline.accounting;

import dev.ledgerline.common.ApiErrors;
import dev.ledgerline.common.WebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import({WebConfiguration.class, ApiErrors.class})
public class AccountingApplication {
    public static void main(String[] args) { SpringApplication.run(AccountingApplication.class, args); }
}
