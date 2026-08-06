package io.github.rajeevchaurasia.orderbook.engine;

import io.github.rajeevchaurasia.orderbook.book.LevelPool;
import io.github.rajeevchaurasia.orderbook.book.Order;
import io.github.rajeevchaurasia.orderbook.book.OrderBook;
import io.github.rajeevchaurasia.orderbook.book.OrderLevel;
import io.github.rajeevchaurasia.orderbook.book.OrderPool;
import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.StatsSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Price-time priority matching engine, single-writer by design.
 *
 * <p>Exactly one thread may call the mutating methods. That single rule
 * replaces every lock the previous design needed: there is no contention,
 * no lock ordering, and no interleaving to reason about. Concurrency is
 * handled at the edges (the command ring feeds this engine; market data is
 * published through the seqlock and snapshot commands).
 *
 * <p>Data plane (submit, cancel) allocates nothing in steady state: orders
 * and levels are pooled, fills are reported through primitive-argument
 * listener callbacks, and the book keys are primitive longs. Control plane
 * (snapshots) allocates freely.
 */
public final class MatchingEngine implements OrderBookEngine {
    private final OrderPool orderPool;
    private final OrderBook book;
    private final EngineListener listener;
    private final LongSupplier clock;
    private long tradeCount;

    public MatchingEngine(EngineListener listener) {
        this(new OrderPool(), new LevelPool(), listener, System::nanoTime);
    }

    public MatchingEngine(OrderPool orderPool, LevelPool levelPool, EngineListener listener, LongSupplier clock) {
        this.orderPool = orderPool;
        this.book = new OrderBook(levelPool);
        this.listener = listener;
        this.clock = clock;
    }

    @Override
    public void submit(long orderId, Side side, long price, long quantity) {
        if (quantity <= 0) {
            listener.onOrderRejected(orderId, RejectReason.INVALID_QUANTITY);
            return;
        }
        if (price <= 0) {
            listener.onOrderRejected(orderId, RejectReason.INVALID_PRICE);
            return;
        }
        if (book.contains(orderId)) {
            listener.onOrderRejected(orderId, RejectReason.DUPLICATE_ORDER_ID);
            return;
        }

        long remaining = match(orderId, side, price, quantity);
        if (remaining > 0) {
            Order order = orderPool.borrow();
            order.init(orderId, side, price, remaining);
            book.rest(order);
        }
        listener.onOrderAccepted(orderId, quantity - remaining, remaining);
    }

    private long match(long takerId, Side takerSide, long limitPrice, long quantity) {
        Side restingSide = takerSide.opposite();
        long remaining = quantity;

        OrderLevel level;
        while (remaining > 0
                && (level = book.bestLevel(restingSide)) != null
                && crosses(takerSide, limitPrice, level.price())) {

            while (remaining > 0 && !level.isEmpty()) {
                Order resting = level.peekFirst();
                long fill = Math.min(remaining, resting.quantity);
                emitTrade(takerId, takerSide, resting, fill);
                remaining -= fill;

                if (fill == resting.quantity) {
                    level.unlink(resting);
                    book.deindex(resting);
                    orderPool.release(resting);
                } else {
                    level.reduce(resting, fill);
                }
            }

            if (level.isEmpty()) {
                book.removeLevel(restingSide, level);
            }
        }
        return remaining;
    }

    private void emitTrade(long takerId, Side takerSide, Order resting, long quantity) {
        long buyOrderId = takerSide == Side.BUY ? takerId : resting.orderId;
        long sellOrderId = takerSide == Side.SELL ? takerId : resting.orderId;
        tradeCount++;
        listener.onTrade(buyOrderId, sellOrderId, resting.price, quantity, clock.getAsLong());
    }

    private static boolean crosses(Side takerSide, long takerPrice, long restingPrice) {
        return takerSide == Side.BUY ? takerPrice >= restingPrice : takerPrice <= restingPrice;
    }

    @Override
    public void cancel(long orderId) {
        Order order = book.find(orderId);
        if (order == null) {
            listener.onOrderRejected(orderId, RejectReason.UNKNOWN_ORDER);
            return;
        }
        long remaining = order.quantity;
        book.removeResting(order);
        orderPool.release(order);
        listener.onOrderCanceled(orderId, remaining);
    }

    @Override
    public long bestBid() {
        OrderLevel best = book.bestLevel(Side.BUY);
        return best == null ? NO_PRICE : best.price();
    }

    @Override
    public long bestAsk() {
        OrderLevel best = book.bestLevel(Side.SELL);
        return best == null ? NO_PRICE : best.price();
    }

    @Override
    public BookSnapshot snapshotBook() {
        List<BookSnapshot.Level> bids = new ArrayList<>(book.levelCount(Side.BUY));
        List<BookSnapshot.Level> asks = new ArrayList<>(book.levelCount(Side.SELL));
        book.forEachLevel(Side.BUY, level ->
                bids.add(new BookSnapshot.Level(level.price(), level.totalQuantity(), level.size())));
        book.forEachLevel(Side.SELL, level ->
                asks.add(new BookSnapshot.Level(level.price(), level.totalQuantity(), level.size())));
        return new BookSnapshot(bids, asks);
    }

    @Override
    public StatsSnapshot snapshotStats() {
        return new StatsSnapshot(
                book.activeOrders(),
                orderPool.capacity() - orderPool.available(),
                orderPool.capacity(),
                book.levelCount(Side.BUY),
                book.levelCount(Side.SELL),
                tradeCount);
    }

    OrderBook book() {
        return book;
    }

    OrderPool orderPool() {
        return orderPool;
    }
}
