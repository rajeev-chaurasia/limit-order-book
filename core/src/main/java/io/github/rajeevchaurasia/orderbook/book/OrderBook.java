package io.github.rajeevchaurasia.orderbook.book;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.function.Consumer;

/**
 * Price-time priority order book over intrusive AVL trees of pooled levels.
 *
 * <p>Bids sort by negated price and asks by price, so the first node of
 * either tree is the best level. Levels are their own tree nodes (see
 * {@link LevelTree}) and are recycled through {@link LevelPool}, which makes
 * level churn allocation-free. An open-addressed hash index with primitive
 * long keys maps order id to the live {@link Order} for O(1) cancellation.
 *
 * <p>Single-threaded by design: every structure here is plain and unlocked,
 * because only the engine thread ever mutates or reads the book. Concurrency
 * lives at the edges (the command ring and the market data seqlock).
 */
public final class OrderBook {
    private final LevelTree bids = new LevelTree();
    private final LevelTree asks = new LevelTree();
    private final Long2ObjectOpenHashMap<Order> ordersById;
    private final LevelPool levelPool;

    public OrderBook(LevelPool levelPool) {
        this.levelPool = levelPool;
        this.ordersById = new Long2ObjectOpenHashMap<>(OrderPool.DEFAULT_CAPACITY);
    }

    private LevelTree tree(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    /** Best-first ordering: bids descend by price, asks ascend. */
    private static long sortKey(Side side, long price) {
        return side == Side.BUY ? -price : price;
    }

    public Order find(long orderId) {
        return ordersById.get(orderId);
    }

    public boolean contains(long orderId) {
        return ordersById.containsKey(orderId);
    }

    /** Rests an order on the book, creating its price level if needed. */
    public void rest(Order order) {
        LevelTree tree = tree(order.side);
        long sortKey = sortKey(order.side, order.price);
        OrderLevel level = tree.find(sortKey);
        if (level == null) {
            level = levelPool.borrow();
            level.init(order.price, sortKey);
            tree.insert(level);
        }
        level.addLast(order);
        ordersById.put(order.orderId, order);
    }

    /**
     * Removes a resting order from its level and the index. Drops and
     * recycles the level if it becomes empty. The caller owns the Order
     * afterwards and is responsible for releasing it.
     */
    public void removeResting(Order order) {
        OrderLevel level = order.level;
        level.unlink(order);
        ordersById.remove(order.orderId);
        if (level.isEmpty()) {
            removeLevel(order.side, level);
        }
    }

    /** Best level of a side, or null when the side is empty. */
    public OrderLevel bestLevel(Side side) {
        return tree(side).first();
    }

    /** Removes a fully drained level from its tree and recycles it. */
    public void removeLevel(Side side, OrderLevel level) {
        tree(side).remove(level);
        levelPool.release(level);
    }

    /** Removes a fully filled order from the id index only. */
    public void deindex(Order order) {
        ordersById.remove(order.orderId);
    }

    public int levelCount(Side side) {
        return tree(side).size();
    }

    public int activeOrders() {
        return ordersById.size();
    }

    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }

    /** Visits levels of a side in price priority order. Control plane only. */
    public void forEachLevel(Side side, Consumer<OrderLevel> action) {
        tree(side).forEachInOrder(action);
    }
}
