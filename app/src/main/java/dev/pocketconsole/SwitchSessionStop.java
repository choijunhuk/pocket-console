package dev.pocketconsole;

import java.util.concurrent.Executor;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Serializes a fresh native stop token with an immediate UI cancellation request. */
final class SwitchSessionStop {
    private final Executor control;
    private final LongConsumer signal;
    private final LongConsumer halt;
    private boolean requested;
    private boolean ready;
    private boolean queued;
    private long generation;
    private volatile boolean finished;

    SwitchSessionStop(Executor control, LongConsumer signal, LongConsumer halt) {
        this.control = control;
        this.signal = signal;
        this.halt = halt;
    }

    boolean arm(LongSupplier reset) {
        synchronized (this) {
            if (requested || finished || ready) return false;
        }
        // An old session's Halt may hold the native lifecycle lock. Never hold the UI lock here.
        long next = reset.getAsLong();
        if (next <= 0) throw new IllegalStateException("Invalid native session generation");
        synchronized (this) {
            if (requested || finished) {
                signal.accept(next);
                return false;
            }
            generation = next;
            ready = true;
            return true;
        }
    }

    void request() {
        boolean dispatch = false;
        long current = 0;
        synchronized (this) {
            if (finished || requested) return;
            requested = true;
            if (ready) {
                current = generation;
                signal.accept(current);
                if (!queued && !finished) {
                    queued = true;
                    dispatch = true;
                }
            }
        }
        final long requestedGeneration = current;
        if (dispatch) control.execute(() -> {
            if (!finished) halt.accept(requestedGeneration);
        });
    }

    void control(LongConsumer action) {
        long current;
        synchronized (this) {
            if (!ready || requested || finished) return;
            current = generation;
        }
        control.execute(() -> {
            synchronized (this) {
                if (requested || finished) return;
            }
            action.accept(current);
        });
    }

    synchronized void finish() { finished = true; }
}
