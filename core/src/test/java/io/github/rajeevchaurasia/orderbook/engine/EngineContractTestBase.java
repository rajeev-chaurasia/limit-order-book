package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;
import io.github.rajeevchaurasia.orderbook.support.RecordingListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine.NO_PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract every {@link OrderBookEngine} implementation must
 * satisfy. Subclasses only supply the engine; every test here runs against
 * both the optimized engine and the naive baseline.
 */
public abstract class EngineContractTestBase {

    protected RecordingListener listener;
    protected OrderBookEngine engine;

    protected abstract OrderBookEngine createEngine(EngineListener listener, LongSupplier clock);

    @BeforeEach
    protected void setUp() {
        listener = new RecordingListener();
        AtomicLong counter = new AtomicLong();
        engine = createEngine(listener, counter::incrementAndGet);
    }

    @Test
    protected void restingOrderAcceptedAndVisible() {
        engine.submit(1, Side.BUY, 10_490, 100);
        engine.submit(2, Side.SELL, 10_500, 50);

        assertEquals(List.of(
                "ACCEPT id=1 filled=0 resting=100",
                "ACCEPT id=2 filled=0 resting=50"), listener.events());
        assertEquals(10_490, engine.bestBid());
        assertEquals(10_500, engine.bestAsk());

        BookSnapshot book = engine.snapshotBook();
        assertEquals(List.of(new BookSnapshot.Level(10_490, 100, 1)), book.bids());
        assertEquals(List.of(new BookSnapshot.Level(10_500, 50, 1)), book.asks());
    }

    @Test
    protected void fullMatchEmptiesBook() {
        engine.submit(1, Side.SELL, 10_500, 100);
        listener.clear();

        engine.submit(2, Side.BUY, 10_500, 100);

        assertEquals(List.of(
                "TRADE buy=2 sell=1 px=10500 qty=100 ts=1",
                "ACCEPT id=2 filled=100 resting=0"), listener.events());
        assertEquals(NO_PRICE, engine.bestBid());
        assertEquals(NO_PRICE, engine.bestAsk());

        BookSnapshot book = engine.snapshotBook();
        assertTrue(book.bids().isEmpty());
        assertTrue(book.asks().isEmpty());
    }

    @Test
    protected void partialFillLeavesRemainder() {
        engine.submit(1, Side.SELL, 10_500, 100);
        listener.clear();

        engine.submit(2, Side.BUY, 10_500, 40);

        assertEquals(List.of(
                "TRADE buy=2 sell=1 px=10500 qty=40 ts=1",
                "ACCEPT id=2 filled=40 resting=0"), listener.events());

        BookSnapshot book = engine.snapshotBook();
        assertTrue(book.bids().isEmpty());
        assertEquals(List.of(new BookSnapshot.Level(10_500, 60, 1)), book.asks());
    }

    @Test
    protected void incomingLargerThanResting() {
        engine.submit(1, Side.SELL, 10_500, 100);
        listener.clear();

        engine.submit(2, Side.BUY, 10_510, 150);

        assertEquals(List.of(
                "TRADE buy=2 sell=1 px=10500 qty=100 ts=1",
                "ACCEPT id=2 filled=100 resting=50"), listener.events());
        assertEquals(10_510, engine.bestBid());
        assertEquals(NO_PRICE, engine.bestAsk());
        assertEquals(List.of(new BookSnapshot.Level(10_510, 50, 1)), engine.snapshotBook().bids());
    }

    @Test
    protected void executionAtRestingPrice() {
        engine.submit(1, Side.SELL, 10_400, 100);
        listener.clear();

        engine.submit(2, Side.BUY, 10_500, 100);

        assertEquals(List.of(
                "TRADE buy=2 sell=1 px=10400 qty=100 ts=1",
                "ACCEPT id=2 filled=100 resting=0"), listener.events());
    }

    @Test
    protected void fifoWithinLevel() {
        engine.submit(1, Side.SELL, 10_500, 100);
        engine.submit(2, Side.SELL, 10_500, 80);
        listener.clear();

        engine.submit(3, Side.BUY, 10_500, 100);

        assertEquals(List.of(
                "TRADE buy=3 sell=1 px=10500 qty=100 ts=1",
                "ACCEPT id=3 filled=100 resting=0"), listener.events());
        assertEquals(List.of(new BookSnapshot.Level(10_500, 80, 1)), engine.snapshotBook().asks());
    }

    @Test
    protected void sweepsLevelsInPriceOrder() {
        engine.submit(1, Side.SELL, 10_500, 100);
        engine.submit(2, Side.SELL, 10_510, 100);
        engine.submit(3, Side.SELL, 10_520, 100);
        listener.clear();

        engine.submit(4, Side.BUY, 10_520, 300);

        assertEquals(List.of(
                "TRADE buy=4 sell=1 px=10500 qty=100 ts=1",
                "TRADE buy=4 sell=2 px=10510 qty=100 ts=2",
                "TRADE buy=4 sell=3 px=10520 qty=100 ts=3",
                "ACCEPT id=4 filled=300 resting=0"), listener.events());
        assertEquals(NO_PRICE, engine.bestAsk());
        assertTrue(engine.snapshotBook().asks().isEmpty());
    }

    @Test
    protected void nonCrossingOrdersRest() {
        engine.submit(1, Side.BUY, 10_490, 100);
        engine.submit(2, Side.SELL, 10_500, 100);

        assertEquals(0, listener.tradeCount());
        assertEquals(10_490, engine.bestBid());
        assertEquals(10_500, engine.bestAsk());
        assertEquals(2, engine.snapshotStats().activeOrders());
    }

    @Test
    protected void cancelRestingOrder() {
        engine.submit(1, Side.BUY, 10_490, 100);
        listener.clear();

        engine.cancel(1);

        assertEquals(List.of("CANCELED id=1"), listener.events());
        assertEquals(NO_PRICE, engine.bestBid());
        assertTrue(engine.snapshotBook().bids().isEmpty());
    }

    @Test
    protected void cancelUnknownOrder() {
        engine.cancel(99);

        assertEquals(List.of("REJECT id=99 reason=UNKNOWN_ORDER"), listener.events());
    }

    @Test
    protected void duplicateActiveIdRejected() {
        engine.submit(1, Side.BUY, 10_490, 100);
        listener.clear();

        engine.submit(1, Side.BUY, 10_480, 50);

        assertEquals(List.of("REJECT id=1 reason=DUPLICATE_ORDER_ID"), listener.events());
        assertEquals(1, engine.snapshotStats().activeOrders());
    }

    @Test
    protected void idReusableAfterCancel() {
        engine.submit(1, Side.BUY, 10_490, 100);
        engine.cancel(1);
        listener.clear();

        engine.submit(1, Side.SELL, 10_500, 60);

        assertEquals(List.of("ACCEPT id=1 filled=0 resting=60"), listener.events());
        assertEquals(10_500, engine.bestAsk());
    }

    @Test
    protected void invalidQuantityRejected() {
        engine.submit(1, Side.BUY, 10_490, 0);
        engine.submit(2, Side.SELL, 10_500, -5);

        assertEquals(List.of(
                "REJECT id=1 reason=INVALID_QUANTITY",
                "REJECT id=2 reason=INVALID_QUANTITY"), listener.events());
        assertEquals(0, engine.snapshotStats().activeOrders());
    }

    @Test
    protected void invalidPriceRejected() {
        engine.submit(1, Side.BUY, 0, 10);
        engine.submit(2, Side.SELL, -100, 10);

        assertEquals(List.of(
                "REJECT id=1 reason=INVALID_PRICE",
                "REJECT id=2 reason=INVALID_PRICE"), listener.events());
        assertEquals(0, engine.snapshotStats().activeOrders());
    }

    @Test
    protected void validationOrderQuantityFirst() {
        engine.submit(1, Side.BUY, 0, 0);

        assertEquals(List.of("REJECT id=1 reason=INVALID_QUANTITY"), listener.events());
    }

    @Test
    protected void emptyBookQuotes() {
        assertEquals(NO_PRICE, engine.bestBid());
        assertEquals(NO_PRICE, engine.bestAsk());
    }

    @Test
    protected void statsReflectActivity() {
        engine.submit(1, Side.BUY, 10_490, 100);
        engine.submit(2, Side.BUY, 10_480, 50);
        engine.submit(3, Side.SELL, 10_500, 70);
        // Fills 40 against order 1, leaving 60 resting on the bid.
        engine.submit(4, Side.SELL, 10_490, 40);
        engine.cancel(2);

        StatsSnapshot stats = engine.snapshotStats();
        assertEquals(2, stats.activeOrders());
        assertEquals(1, stats.bidLevels());
        assertEquals(1, stats.askLevels());
        assertEquals(1, stats.totalTrades());
    }
}
