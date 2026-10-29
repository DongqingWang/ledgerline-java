package dev.ledgerline.raft.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The JSON error envelope this service's HTTP API returns on any non-2xx response. */
public record ErrorBody(ErrorDetail error) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ErrorDetail(String code, String message) {
    }

    public static ErrorBody of(String code, String message) {
        return new ErrorBody(new ErrorDetail(code, message));
    }
}
