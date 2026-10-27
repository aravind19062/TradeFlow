package com.luffy.tradeflow.common.kafka;

/**
 * A well-formed event that contradicts this service's state, e.g. a trade for an
 * order that holds no reservation. Retrying cannot fix it, so the event goes to the
 * DLT and is logged at ERROR: the inconsistency is detected, never silently absorbed.
 */
public class InconsistentStateException extends RuntimeException {

    public InconsistentStateException(String message) {
        super(message);
    }
}
