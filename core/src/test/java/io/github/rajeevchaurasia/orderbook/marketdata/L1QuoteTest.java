package io.github.rajeevchaurasia.orderbook.marketdata;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class L1QuoteTest {

    private static final int READERS = 4;
    private static final long RUN_MILLIS = 500;
    private static final long MIN_READS_PER_READER = 1000;

    @Test
    void singleThreadedRoundtrip() {
        L1Quote quote = new L1Quote();
        quote.publish(10_500, 25, 10_505, 40);
        L1Quote.Quote q = quote.read();
        assertEquals(10_500, q.bestBid());
        assertEquals(25, q.bestBidQuantity());
        assertEquals(10_505, q.bestAsk());
        assertEquals(40, q.bestAskQuantity());
    }

    /**
     * The writer publishes four fields all derived from one counter, so any
     * torn read shows up as a broken arithmetic relation between them.
     */
    @Test
    void concurrentReadersNeverObserveTornQuotes() throws InterruptedException {
        L1Quote quote = new L1Quote();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<String> violation = new AtomicReference<>();
        long[] readCounts = new long[READERS];

        Thread writer = new Thread(() -> {
            long x = 1;
            while (running.get()) {
                quote.publish(x, 2 * x, 3 * x, 4 * x);
                x++;
            }
        }, "l1-writer");

        Thread[] readers = new Thread[READERS];
        for (int r = 0; r < READERS; r++) {
            final int index = r;
            readers[r] = new Thread(() -> {
                long count = 0;
                while (running.get()) {
                    L1Quote.Quote q = quote.read();
                    if (q.bestBidQuantity() != 2 * q.bestBid()
                            || q.bestAsk() != 3 * q.bestBid()
                            || q.bestAskQuantity() != 4 * q.bestBid()) {
                        violation.compareAndSet(null, "torn read observed: " + q);
                        break;
                    }
                    count++;
                }
                readCounts[index] = count;
            }, "l1-reader-" + r);
        }

        writer.start();
        for (Thread reader : readers) {
            reader.start();
        }
        Thread.sleep(RUN_MILLIS);
        running.set(false);

        writer.join(10_000);
        if (writer.isAlive()) {
            fail("writer did not terminate");
        }
        for (Thread reader : readers) {
            reader.join(10_000);
            if (reader.isAlive()) {
                fail("reader did not terminate: " + reader.getName());
            }
        }

        assertNull(violation.get(), String.valueOf(violation.get()));
        for (int r = 0; r < READERS; r++) {
            assertTrue(readCounts[r] > MIN_READS_PER_READER,
                    "reader " + r + " made too few reads: " + readCounts[r]);
        }
    }
}
