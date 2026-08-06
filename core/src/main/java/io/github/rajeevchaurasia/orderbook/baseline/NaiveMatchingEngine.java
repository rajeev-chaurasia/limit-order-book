package io.github.rajeevchaurasia.orderbook.baseline;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.EngineListener;
import io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine;
import io.github.rajeevchaurasia.orderbook.engine.RejectReason;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Deliberately naive reference engine: coarse synchronization, boxed keys,
 * JDK collections, allocation everywhere. Correct by construction and easy
 * to audit, which gives it two jobs: it is the oracle the optimized engine
 * is differentially tested against, and it is the baseline the benchmark
 * graphs compare against.
 *
 * <p>It must emit exactly the same event sequence as the optimized engine
 * for any command stream (given the same clock). Any divergence is a bug in
 * one of the two.
 */
public final class NaiveMatchingEngine implements OrderBookEngine {

    private static final class RestingOrder {
        final long orderId;
        final Side side;
        final long price;
        long quantity;

        RestingOrder(long orderId, Side side, long price, long quantity) {
            this.orderId = orderId;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
        }
    }

    private final TreeMap<Long, ArrayDeque<RestingOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<RestingOrder>> asks = new TreeMap<>();
    private final Map<Long, RestingOrder> ordersById = new HashMap<>();
    private final EngineListener listener;
    private final LongSupplier clock;
    private long tradeCount;

    public NaiveMatchingEngine(EngineListener listener) {
        this(listener, System::nanoTime);
    }

    public NaiveMatchingEngine(EngineListener listener, LongSupplier clock) {
        this.listener = listener;
        this.clock = clock;
    }

    private TreeMap<Long, ArrayDeque<RestingOrder>> tree(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    @Override
    public synchronized void submit(long orderId, Side side, long price, long quantity) {
        if (quantity <= 0) {
            listener.onOrderRejected(orderId, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (price <= 0) {
            listener.onOrderRejected(orderId, RejectReason.INVALID_PRICE);
            return;
        }
        if (ordersById.containsKey(orderId)) {
            listener.onOrderRejected(orderId, RejectReason.DUPLICATE_ORDER_ID);
            return;
        }

        long remaining = match(orderId, side, price, quantity);
        if (remaining > 0) {
            RestingOrder order = new RestingOrder(orderId, side, price, remaining);
            tree(side).computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(order);
            ordersById.put(orderId, order);
        }
        listener.onOrderAccepted(orderId, quantity - remaining, remaining);
    }

    private long match(long takerId, Side takerSide, long limitPrice, long quantity) {
        TreeMap<Long, ArrayDeque<RestingOrder>> restingTree = tree(takerSide.opposite());
        long remaining = quantity;

        while (remaining > 0 && !restingTree.isEmpty()) {
            Map.Entry<Long, ArrayDeque<RestingOrder>> best = restingTree.firstEntry();
            if (!crosses(takerSide, limitPrice, best.getKey())) {
                break;
            }

            ArrayDeque<RestingOrder> level = best.getValue();
            while (remaining > 0 && !level.isEmpty()) {
                RestingOrder resting = level.peekFirst();
                long fill = Math.min(remaining, resting.quantity);
                emitTrade(takerId, takerSide, resting, fill);
                remaining -= fill;
                resting.quantity -= fill;

                if (resting.quantity == 0) {
                    level.pollFirst();
                    ordersById.remove(resting.orderId);
                }
            }

            if (level.isEmpty()) {
                restingTree.remove(best.getKey());
            }
        }
        return remaining;
    }

    private void emitTrade(long takerId, Side takerSide, RestingOrder resting, long quantity) {
        long buyOrderId = takerSide == Side.BUY ? takerId : resting.orderId;
        long sellOrderId = takerSide == Side.SELL ? takerId : resting.orderId;
        tradeCount++;
        listener.onTrade(buyOrderId, sellOrderId, resting.price, quantity, clock.getAsLong());
    }

    private static boolean crosses(Side takerSide, long takerPrice, long restingPrice) {
        return takerSide == Side.BUY ? takerPrice >= restingPrice : takerPrice <= restingPrice;
    }

    @Override
    public synchronized void cancel(long orderId) {
        RestingOrder order = ordersById.remove(orderId);
        if (order == null) {
            listener.onOrderRejected(orderId, RejectReason.UNKNOWN_ORDER);
            return;
        }
        TreeMap<Long, ArrayDeque<RestingOrder>> restingTree = tree(order.side);
        ArrayDeque<RestingOrder> level = restingTree.get(order.price);
        level.remove(order);
        if (level.isEmpty()) {
            restingTree.remove(order.price);
        }
        listener.onOrderCanceled(orderId, order.quantity);
    }

    @Override
    public synchronized long bestBid() {
        return bids.isEmpty() ? NO_PRICE : bids.firstKey();
    }

    @Override
    public synchronized long bestAsk() {
        return asks.isEmpty() ? NO_PRICE : asks.firstKey();
    }

    @Override
    public synchronized BookSnapshot snapshotBook() {
        return new BookSnapshot(snapshotSide(bids), snapshotSide(asks));
    }

    private static List<BookSnapshot.Level> snapshotSide(TreeMap<Long, ArrayDeque<RestingOrder>> tree) {
        List<BookSnapshot.Level> levels = new ArrayList<>(tree.size());
        for (Map.Entry<Long, ArrayDeque<RestingOrder>> entry : tree.entrySet()) {
            long total = 0;
            for (RestingOrder order : entry.getValue()) {
                total += order.quantity;
            }
            levels.add(new BookSnapshot.Level(entry.getKey(), total, entry.getValue().size()));
        }
        return levels;
    }

    @Override
    public synchronized StatsSnapshot snapshotStats() {
        return new StatsSnapshot(ordersById.size(), 0, 0, bids.size(), asks.size(), tradeCount);
    }
}
