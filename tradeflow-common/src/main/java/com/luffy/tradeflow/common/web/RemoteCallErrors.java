package com.luffy.tradeflow.common.web;

/**
 * Turns failures of synchronous service-to-service calls into explicit API errors.
 * Feign wraps errors in several layers (circuit breaker, load balancer retry), so the
 * cause chain is searched for an HTTP status.
 */
public final class RemoteCallErrors {

    private RemoteCallErrors() {
    }

    /** HTTP status of the remote response if the failure was one, else -1. */
    public static int remoteStatus(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getClass().getName().startsWith("feign.FeignException")) {
                try {
                    return (int) t.getClass().getMethod("status").invoke(t);
                } catch (ReflectiveOperationException ignored) {
                    return -1;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return -1;
    }

    /** 503 with a reason that distinguishes an open circuit breaker from a failed call. */
    public static ApiException unavailable(String service, Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getClass().getSimpleName().equals("CallNotPermittedException")) {
                return ApiException.dependencyUnavailable(service, "circuit breaker is open after repeated failures");
            }
            if (t.getCause() == t) {
                break;
            }
        }
        int status = remoteStatus(error);
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String reason = status > 0 ? "HTTP " + status : root.getClass().getSimpleName()
                + (root.getMessage() == null ? "" : ": " + root.getMessage());
        org.slf4j.LoggerFactory.getLogger(RemoteCallErrors.class).warn("Call to {} failed: {}", service, reason);
        return ApiException.dependencyUnavailable(service, reason.length() > 200 ? reason.substring(0, 200) : reason);
    }
}
