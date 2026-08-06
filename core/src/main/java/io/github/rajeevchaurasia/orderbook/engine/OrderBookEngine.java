package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;

/**
 * Behavioral contract shared by the optimized single-writer engine and the
 * naive baseline engine. The same contract test suite and the differential
 * tests run against both implementations; the baseline doubles as the
 * benchmark comparison target.
 *
 * <p>Results are reported through the {@link EngineListener} supplied at
 * construction, not through return values, so the data plane stays free of
 * allocation.
 */
public interface OrderBookEngine {

    /** Sentinel for an empty book side. Valid prices are always positive. */
    long NO_PRICE = 0L;

    /**
     * Submits a limit order: matches whatever crosses, rests the remainder.
     * Emits trades followed by one terminal accept or reject event.
     */
    void submit(long orderId, Side side, long price, long quantity);

    /** Cancels a resting order. Emits one canceled or rejected event. */
    void cancel(long orderId);

    /** Best bid price, or {@link #NO_PRICE} when there are no bids. */
    long bestBid();

    /** Best ask price, or {@link #NO_PRICE} when there are no asks. */
    long bestAsk();

    BookSnapshot snapshotBook();

    StatsSnapshot snapshotStats();
}
