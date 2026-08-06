package io.github.rajeevchaurasia.orderbook.ring;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Multi-producer stress: 4 producers push 200k commands each through a small
 * ring while the test thread drains. Verifies no loss, no duplication, and
 * strict FIFO per producer.
 */
class MpscCommandRingStressTest {

    private static final int PRODUCERS = 4;
    private static final int PER_PRODUCER = 200_000;
    private static final long TOTAL = (long) PRODUCERS * PER_PRODUCER;
    private static final long DEADLINE_MILLIS = 60_000;

    @Test
    void fourProducersFifoPerProducerNoLossNoDuplication() throws InterruptedException {
        MpscCommandRing ring = new MpscCommandRing(1024);
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicReference<String> failure = new AtomicReference<>();

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final long producerId = p;
            producers[p] = new Thread(() -> {
                for (long seq = 0; seq < PER_PRODUCER; seq++) {
                    long ticket;
                    while ((ticket = ring.tryClaim()) == MpscCommandRing.FULL) {
                        if (stop.get()) {
                            return;
                        }
                        Thread.onSpinWait();
                    }
                    OrderCommand slot = ring.slot(ticket);
                    slot.type = CommandType.NEW;
                    slot.orderId = (producerId << 32) | seq;
                    ring.publish(ticket);
                }
            }, "ring-producer-" + p);
        }
        for (Thread producer : producers) {
            producer.start();
        }

        // Consumer state; the handler runs on this test thread only.
        long[] lastSeq = new long[PRODUCERS];
        Arrays.fill(lastSeq, -1);
        long[] perProducer = new long[PRODUCERS];
        long[] total = {0};

        MpscCommandRing.CommandHandler handler = command -> {
            int p = (int) (command.orderId >>> 32);
            long seq = command.orderId & 0xFFFF_FFFFL;
            if (p < 0 || p >= PRODUCERS) {
                failure.compareAndSet(null, "bad producer index in orderId: " + command.orderId);
                return;
            }
            if (seq != lastSeq[p] + 1) {
                failure.compareAndSet(null,
                        "producer " + p + " sequence jumped from " + lastSeq[p] + " to " + seq);
            }
            lastSeq[p] = seq;
            perProducer[p]++;
            total[0]++;
        };

        long deadline = System.currentTimeMillis() + DEADLINE_MILLIS;
        while (total[0] < TOTAL && failure.get() == null) {
            int drained = ring.drain(handler, 256);
            if (drained == 0) {
                if (System.currentTimeMillis() > deadline) {
                    failure.compareAndSet(null,
                            "deadline exceeded with " + total[0] + " of " + TOTAL + " drained");
                    break;
                }
                Thread.onSpinWait();
            }
        }

        stop.set(true);
        for (Thread producer : producers) {
            producer.join(10_000);
            if (producer.isAlive()) {
                fail("producer did not terminate: " + producer.getName());
            }
        }

        assertNull(failure.get(), String.valueOf(failure.get()));
        assertEquals(TOTAL, total[0], "total commands drained");
        for (int p = 0; p < PRODUCERS; p++) {
            assertEquals(PER_PRODUCER, perProducer[p], "commands drained for producer " + p);
            assertEquals(PER_PRODUCER - 1, lastSeq[p], "last sequence for producer " + p);
        }
    }
}
