package io.github.rajeevchaurasia.orderbook.gateway;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.CancelResult;
import io.github.rajeevchaurasia.orderbook.engine.EngineLoop;
import io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine;
import io.github.rajeevchaurasia.orderbook.engine.OrderResult;
import io.github.rajeevchaurasia.orderbook.engine.WaitStrategy;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.L1Quote;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;
import io.github.rajeevchaurasia.orderbook.ring.MpscCommandRing;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Conservation under concurrency: 4 threads submit blocking orders, then
 * everything left is canceled. For each order the passively filled quantity
 * is derived as quantity - takerFill - remainingAtCancel; total executed buy
 * quantity must equal total executed sell quantity, and the book must be
 * empty at the end.
 */
class EndToEndStressTest {

    private static final int PRODUCERS = 4;
    private static final int ORDERS_PER_PRODUCER = 25_000;
    private static final int TOTAL_ORDERS = PRODUCERS * ORDERS_PER_PRODUCER;
    private static final long JOIN_TIMEOUT_MILLIS = 60_000;
    private static final long SEED = 20260805;

    private static long orderId(int producer, int i) {
        return producer * 1_000_000L + i;
    }

    @Test
    void quantityConservedAcrossConcurrentSubmitsThenCancelAll() throws InterruptedException {
        MpscCommandRing ring = new MpscCommandRing(1 << 14);
        EngineLoop loop = new EngineLoop(ring, WaitStrategy.PARKING);
        loop.start();
        EngineGateway gateway = new EngineGateway(ring, loop);
        try {
            run(gateway);
        } finally {
            loop.stop();
        }
    }

    private void run(EngineGateway gateway) throws InterruptedException {
        long[] quantity = new long[TOTAL_ORDERS];
        boolean[] isBuy = new boolean[TOTAL_ORDERS];
        long[] ackFilled = new long[TOTAL_ORDERS];
        long[] ackResting = new long[TOTAL_ORDERS];
        long[] remainingAtCancel = new long[TOTAL_ORDERS];
        AtomicReference<Throwable> error = new AtomicReference<>();

        Thread[] producers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final int producer = p;
            producers[p] = new Thread(() -> {
                Random random = new Random(SEED + producer);
                try {
                    for (int i = 0; i < ORDERS_PER_PRODUCER; i++) {
                        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
                        long price = 9_500 + 5L * random.nextInt(201);
                        long qty = 1 + random.nextInt(100);
                        OrderResult result = gateway.submit(orderId(producer, i), side, price, qty);
                        if (!result.accepted()) {
                            throw new AssertionError("order rejected: " + result);
                        }
                        int idx = producer * ORDERS_PER_PRODUCER + i;
                        quantity[idx] = qty;
                        isBuy[idx] = side == Side.BUY;
                        ackFilled[idx] = result.filledQuantity();
                        ackResting[idx] = result.restingQuantity();
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            }, "submit-" + p);
        }
        startAndJoin(producers);
        assertNull(error.get(), String.valueOf(error.get()));

        // All submits were blocking, so once the producers have joined every
        // command has been processed. Cancel everything that still rests;
        // ids are partitioned per producer so parallel cancels are safe.
        Thread[] cancelers = new Thread[PRODUCERS];
        for (int p = 0; p < PRODUCERS; p++) {
            final int producer = p;
            cancelers[p] = new Thread(() -> {
                try {
                    for (int i = 0; i < ORDERS_PER_PRODUCER; i++) {
                        CancelResult result = gateway.cancel(orderId(producer, i));
                        int idx = producer * ORDERS_PER_PRODUCER + i;
                        remainingAtCancel[idx] = result.canceled() ? result.remainingQuantity() : 0;
                    }
                } catch (Throwable t) {
                    error.compareAndSet(null, t);
                }
            }, "cancel-" + p);
        }
        startAndJoin(cancelers);
        assertNull(error.get(), String.valueOf(error.get()));

        long buyExecuted = 0;
        long sellExecuted = 0;
        for (int idx = 0; idx < TOTAL_ORDERS; idx++) {
            long passive = quantity[idx] - ackFilled[idx] - remainingAtCancel[idx];
            final int at = idx;
            final long passiveAt = passive;
            assertTrue(passive >= 0, () -> "order " + at + " negative passive fill: " + passiveAt);
            assertTrue(passive <= ackResting[idx], () -> "order " + at + " passive fill " + passiveAt
                    + " exceeds acked resting " + ackResting[at]);
            if (isBuy[idx]) {
                buyExecuted += ackFilled[idx] + passive;
            } else {
                sellExecuted += ackFilled[idx] + passive;
            }
        }
        assertEquals(buyExecuted, sellExecuted, "executed buy and sell quantity must match");

        StatsSnapshot stats = gateway.stats();
        assertEquals(0, stats.activeOrders(), "book must be empty after cancel-all");

        BookSnapshot book = gateway.book();
        assertTrue(book.bids().isEmpty(), "bids left after cancel-all");
        assertTrue(book.asks().isEmpty(), "asks left after cancel-all");

        L1Quote.Quote quote = gateway.quote();
        assertEquals(OrderBookEngine.NO_PRICE, quote.bestBid());
        assertEquals(0, quote.bestBidQuantity());
        assertEquals(OrderBookEngine.NO_PRICE, quote.bestAsk());
        assertEquals(0, quote.bestAskQuantity());
    }

    private static void startAndJoin(Thread[] threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.start();
        }
        long deadline = System.currentTimeMillis() + JOIN_TIMEOUT_MILLIS;
        for (Thread thread : threads) {
            long budget = deadline - System.currentTimeMillis();
            if (budget > 0) {
                thread.join(budget);
            }
            if (thread.isAlive()) {
                thread.interrupt();
                fail("thread did not finish within the deadline: " + thread.getName());
            }
        }
    }
}
