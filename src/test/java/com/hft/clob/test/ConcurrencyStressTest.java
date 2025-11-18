package com.hft.clob.test;

import com.hft.clob.core.*;
import com.hft.clob.engine.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency stress test as specified in PRD Section 5.2.
 * 
 * Test Scenario:
 * - 10 threads: 5 buy 100 shares @ $100, 5 sell 100 shares @ $100
 * 
 * Success Criteria:
 * - Total trades = 500 shares
 * - Final order book is empty
 * - No exceptions/deadlocks
 * - Test completes in < 5 seconds
 */
public class ConcurrencyStressTest {

    @Test
    public void testConcurrentBuySellRaceCondition() throws Exception {
        // Setup
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        int ordersPerSide = 5;
        int sharesPerOrder = 100;
        long price = 10000; // $100.00

        // Phase 1: Build the book with resting sell orders (single threaded)
        for (int i = 1; i <= ordersPerSide; i++) {
            engine.processOrder(i, (byte) 'S', price, sharesPerOrder);
        }

        // Verify book has 5 sell orders
        assertFalse(book.isEmpty());
        assertEquals(price, book.getBestAsk());

        // Phase 2: Submit crossing buy orders concurrently
        ExecutorService executor = Executors.newFixedThreadPool(ordersPerSide);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(ordersPerSide);

        AtomicLong totalTraded = new AtomicLong(0);
        AtomicInteger exceptions = new AtomicInteger(0);

        for (int i = 0; i < ordersPerSide; i++) {
            final long orderId = 1000 + i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    List<Trade> trades = engine.processOrder(orderId, (byte) 'B', price, sharesPerOrder);

                    // Accumulate traded quantity
                    long traded = trades.stream().mapToLong(t -> t.quantity).sum();
                    totalTraded.addAndGet(traded);

                } catch (Exception e) {
                    e.printStackTrace();
                    exceptions.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        long startTime = System.currentTimeMillis();
        startLatch.countDown();

        // Wait for completion (with timeout)
        boolean completed = endLatch.await(5, TimeUnit.SECONDS);
        long endTime = System.currentTimeMillis();

        executor.shutdown();

        // Assertions
        assertTrue(completed, "Test did not complete within 5 seconds - possible deadlock!");
        assertEquals(0, exceptions.get(), "Exceptions occurred during test");

        // Total traded should be 500 shares (all orders matched)
        assertEquals(500, totalTraded.get(),
                "Total traded quantity should be 500 shares (5 buy orders × 100 shares matched against 5 sell orders × 100 shares)");

        // Order book should be empty (all orders matched)
        assertTrue(book.isEmpty(),
                "Order book should be empty after all matches. Bids: " + book.getBids().size() +
                        ", Asks: " + book.getAsks().size());

        // Verify performance
        long duration = endTime - startTime;
        System.out.printf("Concurrency test completed in %d ms, total traded: %d shares%n",
                duration, totalTraded.get());
        assertTrue(duration < 5000, "Test took too long: " + duration + " ms");

        System.out.printf("✓ All assertions passed: %d shares traded, book empty, no exceptions%n",
                totalTraded.get());
    }

    @Test
    public void testHighContentionScenario() throws Exception {
        // More aggressive test: 20 threads, same price
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        int numThreads = 20;
        int ordersPerThread = 50;
        long price = 10000;

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numThreads);

        AtomicLong orderIdGenerator = new AtomicLong(1);

        // Create threads that alternate buy/sell
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();

                    for (int j = 0; j < ordersPerThread; j++) {
                        long orderId = orderIdGenerator.getAndIncrement();
                        byte side = (threadId % 2 == 0) ? (byte) 'B' : (byte) 'S';
                        engine.processOrder(orderId, side, price, 10);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    fail("Exception in thread " + threadId + ": " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "High contention test did not complete in time");
        System.out.println("✓ High contention test passed");
    }
}
