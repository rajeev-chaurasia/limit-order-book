package io.github.rajeevchaurasia.orderbook.app;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.EngineListener;
import io.github.rajeevchaurasia.orderbook.engine.MatchingEngine;
import io.github.rajeevchaurasia.orderbook.engine.RejectReason;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.TradeRecord;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe facade over the single-writer {@link MatchingEngine}.
 *
 * <p>Temporary: this coarse-grained lock will be replaced by a lock-free
 * command gateway in a later phase.
 *
 * <p>The facade is the engine's {@link EngineListener}. Callbacks fire
 * synchronously inside the engine call, on the caller's thread, while the
 * monitor is held, so the per-call scratch fields are safe to collect into.
 */
public final class EngineFacade implements EngineListener {

    private static final int MAX_RECENT_TRADES = 100;

    private final MatchingEngine engine;
    private final ArrayDeque<TradeRecord> recentTrades = new ArrayDeque<>(MAX_RECENT_TRADES);

    // Per-call scratch, reset before each engine call, valid only under the lock.
    private long filled;
    private long resting;
    private int tradeCount;
    private boolean rejected;
    private RejectReason reason;

    public EngineFacade() {
        // Fields above are initialized before the engine can call back into this.
        this.engine = new MatchingEngine(this);
    }

    /** Outcome of one submit call, collapsed from the engine's event stream. */
    public record OrderResult(
            long orderId,
            boolean accepted,
            RejectReason reason,
            long filledQuantity,
            long restingQuantity,
            int tradeCount) {
    }

    public synchronized OrderResult submit(long orderId, Side side, long price, long quantity) {
        resetScratch();
        engine.submit(orderId, side, price, quantity);
        if (rejected) {
            return new OrderResult(orderId, false, reason, 0, 0, 0);
        }
        return new OrderResult(orderId, true, null, filled, resting, tradeCount);
    }

    /** Returns true when the order was canceled, false when it was unknown. */
    public synchronized boolean cancel(long orderId) {
        resetScratch();
        engine.cancel(orderId);
        return !rejected;
    }

    public synchronized BookSnapshot book() {
        return engine.snapshotBook();
    }

    public synchronized StatsSnapshot stats() {
        return engine.snapshotStats();
    }

    public synchronized long bestBid() {
        return engine.bestBid();
    }

    public synchronized long bestAsk() {
        return engine.bestAsk();
    }

    /** Most recent trades, oldest first, capped at {@value MAX_RECENT_TRADES}. Returns a copy. */
    public synchronized List<TradeRecord> recentTrades() {
        return new ArrayList<>(recentTrades);
    }

    private void resetScratch() {
        filled = 0;
        resting = 0;
        tradeCount = 0;
        rejected = false;
        reason = null;
    }

    @Override
    public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
        tradeCount++;
        recentTrades.addLast(new TradeRecord(buyOrderId, sellOrderId, price, quantity, timestamp));
        if (recentTrades.size() > MAX_RECENT_TRADES) {
            recentTrades.pollFirst();
        }
    }

    @Override
    public void onOrderAccepted(long orderId, long filledQuantity, long restingQuantity) {
        filled = filledQuantity;
        resting = restingQuantity;
    }

    @Override
    public void onOrderCanceled(long orderId) {
        // Success is signaled by the absence of a reject.
    }

    @Override
    public void onOrderRejected(long orderId, RejectReason rejectReason) {
        rejected = true;
        reason = rejectReason;
    }
}
