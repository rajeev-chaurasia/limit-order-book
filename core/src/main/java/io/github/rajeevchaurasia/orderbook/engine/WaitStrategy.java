package io.github.rajeevchaurasia.orderbook.engine;

import java.util.concurrent.locks.LockSupport;

/**
 * What the engine thread does when the ring is empty. The trade-off is
 * latency against CPU: BUSY_SPIN never yields the core and reacts in
 * nanoseconds; PARKING backs off progressively and is the right default for
 * a shared host.
 */
public enum WaitStrategy {

    BUSY_SPIN {
        @Override
        public void idle(int idleCount) {
            Thread.onSpinWait();
        }
    },

    PARKING {
        @Override
        public void idle(int idleCount) {
            if (idleCount < SPIN_THRESHOLD) {
                Thread.onSpinWait();
            } else if (idleCount < YIELD_THRESHOLD) {
                Thread.yield();
            } else {
                LockSupport.parkNanos(PARK_NANOS);
            }
        }
    };

    private static final int SPIN_THRESHOLD = 128;
    private static final int YIELD_THRESHOLD = 256;
    private static final long PARK_NANOS = 100_000;

    public abstract void idle(int idleCount);
}
