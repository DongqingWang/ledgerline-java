package dev.ledgerline.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@SpringBootApplication
@RestController
public class AdminApplication {
    public static void main(String[] args) { SpringApplication.run(AdminApplication.class, args); }
    @GetMapping("/healthz") public Map<String, String> health() { return Map.of("status", "ok"); }
}
