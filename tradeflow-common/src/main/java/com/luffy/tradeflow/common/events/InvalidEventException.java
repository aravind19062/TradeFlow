package com.luffy.tradeflow.common.events;

/**
 * A message that can never be processed (malformed JSON, wrong type, unsupported
 * schema version, failed validation). Not retried; routed straight to the DLT.
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
