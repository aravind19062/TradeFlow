package com.luffy.tradeflow.common.web;

import org.springframework.http.HttpStatus;

/**
 * Base for expected business errors. Each carries an HTTP status and a stable,
 * machine-readable {@code code} that clients can switch on.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    /** Seconds the client should wait before retrying (sent as Retry-After), or null. */
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException tooManyRequests(String code, String message, long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, message, retryAfterSeconds);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    /** Well-formed request that violates a business rule. */
    public static ApiException unprocessable(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** A downstream dependency (another service, Kafka) cannot be reached. */
    public static ApiException dependencyUnavailable(String dependency, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE",
                dependency + " is unavailable: " + message);
    }
}
