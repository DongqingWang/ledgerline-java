package dev.ledgerline.reporting;

import dev.ledgerline.common.WebConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import java.util.Arrays;

@SpringBootApplication
@Import({WebConfiguration.class, dev.ledgerline.common.ApiErrors.class})
public class ReportingApplication {
    public static void main(String[] args) throws Exception {
        boolean cli = Arrays.asList(args).contains("--report");
        ReportOptions options = null;
        if (cli) {
            try { options = ReportOptions.parse(args); }
            catch (IllegalArgumentException e) {
                System.err.println("Invalid report options: " + e.getMessage());
                System.exit(2);
                return;
            }
        }
        var application = new SpringApplication(ReportingApplication.class);
        if (cli) {
            application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
            application.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
            application.setLogStartupInfo(false);
        }
        var context = application.run(args);
        if (cli) {
            int code;
            try {
                var report = context.getBean(TrialBalance.class).run(options.period());
                String text = context.getBean(ReportFormatter.class).format(report, options.format());
                if (options.output() == null) System.out.print(text);
                else java.nio.file.Files.writeString(options.output(), text, java.nio.charset.StandardCharsets.UTF_8);
                System.err.println("RECONCILIATION: " + (report.balanced() ? "PASS" : "FAIL")
                    + " (period journal checks; account balance reconciliation uses all history)");
                code = report.balanced() ? 0 : 1;
            } finally { context.close(); }
            System.exit(code);
        }
    }
}
