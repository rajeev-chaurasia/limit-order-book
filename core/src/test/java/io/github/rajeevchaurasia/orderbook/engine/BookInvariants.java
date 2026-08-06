package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;

import static io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine.NO_PRICE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural invariants of the optimized engine's book. Lives in the engine
 * package so it can reach the package-private {@link MatchingEngine#book()}
 * accessor for internal cross-checks.
 */
public final class BookInvariants {

    private BookInvariants() {
    }

    public static void check(MatchingEngine engine) {
        long bestBid = engine.bestBid();
        long bestAsk = engine.bestAsk();
        assertTrue(bestBid == NO_PRICE || bestAsk == NO_PRICE || bestBid < bestAsk,
                "book crossed: bestBid=" + bestBid + " bestAsk=" + bestAsk);

        BookSnapshot snapshot = engine.snapshotBook();
        int orderCount = 0;

        long previous = Long.MAX_VALUE;
        for (BookSnapshot.Level level : snapshot.bids()) {
            assertTrue(level.price() < previous,
                    "bid prices not strictly descending at " + level.price());
            assertTrue(level.quantity() > 0, "bid level " + level.price() + " has non-positive quantity");
            assertTrue(level.orders() > 0, "bid level " + level.price() + " has no orders");
            previous = level.price();
            orderCount += level.orders();
        }

        previous = Long.MIN_VALUE;
        for (BookSnapshot.Level level : snapshot.asks()) {
            assertTrue(level.price() > previous,
                    "ask prices not strictly ascending at " + level.price());
            assertTrue(level.quantity() > 0, "ask level " + level.price() + " has non-positive quantity");
            assertTrue(level.orders() > 0, "ask level " + level.price() + " has no orders");
            previous = level.price();
            orderCount += level.orders();
        }

        assertEquals(engine.snapshotStats().activeOrders(), orderCount,
                "sum of level order counts disagrees with activeOrders");
        assertEquals(engine.book().activeOrders(), orderCount,
                "book id index size disagrees with level order counts");
    }
}
