package com.luffy.tradeflow.common.testsupport;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;

import java.util.function.Supplier;

/**
 * Re-runs an action after the transient conflicts that the Kafka error handler retries
 * in production (optimistic-lock losses, unique-key races), so concurrency tests that
 * call listeners directly behave like real redelivery.
 */
public final class Retry {

    private Retry() {
    }

    public static <T> T onConflict(Supplier<T> action) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                return action.get();
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException
                     | PessimisticLockingFailureException e) {
                last = e;
                try {
                    Thread.sleep(5L + (long) (Math.random() * 20));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last;
    }

    public static void onConflict(Runnable action) {
        onConflict(() -> {
            action.run();
            return null;
        });
    }
}
