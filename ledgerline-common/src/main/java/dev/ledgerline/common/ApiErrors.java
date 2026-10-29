package dev.ledgerline.common;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request: " + e.getMessage()));
    }

    @ExceptionHandler(RestClientResponseException.class)
    ResponseEntity<?> upstream(RestClientResponseException e) {
        return ResponseEntity.status(e.getStatusCode()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(e.getResponseBodyAsString());
    }

    @ExceptionHandler(ResourceAccessException.class)
    ResponseEntity<?> unavailable(ResourceAccessException e) {
        return ResponseEntity.status(502).body(Map.of("error", "Upstream service unavailable"));
    }

    public static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }
}
