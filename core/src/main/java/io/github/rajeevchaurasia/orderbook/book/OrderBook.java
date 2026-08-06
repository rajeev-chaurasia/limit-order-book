package io.github.rajeevchaurasia.orderbook.book;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.longs.LongComparators;

import java.util.function.Consumer;

/**
 * Price-time priority order book over primitive-keyed red-black trees.
 *
 * <p>Bids sort descending (best bid first), asks ascending (best ask first),
 * so the best level of either side is the first tree entry. Keys are
 * primitive longs: no boxing on any book operation. An open-addressed hash
 * index maps order id to the live {@link Order} for O(1) cancellation.
 *
 * <p>Single-threaded by design: every structure here is plain and unlocked,
 * because only the engine thread ever mutates or reads the book. Concurrency
 * lives at the edges (see the engine package).
 */
public final class OrderBook {
    private final Long2ObjectRBTreeMap<OrderLevel> bids =
            new Long2ObjectRBTreeMap<>(LongComparators.OPPOSITE_COMPARATOR);
    private final Long2ObjectRBTreeMap<OrderLevel> asks = new Long2ObjectRBTreeMap<>();
    private final Long2ObjectOpenHashMap<Order> ordersById;
    private final LevelPool levelPool;

    public OrderBook(LevelPool levelPool) {
        this.levelPool = levelPool;
        this.ordersById = new Long2ObjectOpenHashMap<>(OrderPool.DEFAULT_CAPACITY);
    }

    private Long2ObjectRBTreeMap<OrderLevel> tree(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    public Order find(long orderId) {
        return ordersById.get(orderId);
    }

    public boolean contains(long orderId) {
        return ordersById.containsKey(orderId);
    }

    /** Rests an order on the book, creating its price level if needed. */
    public void rest(Order order) {
        Long2ObjectRBTreeMap<OrderLevel> tree = tree(order.side);
        OrderLevel level = tree.get(order.price);
        if (level == null) {
            level = levelPool.borrow();
            level.init(order.price);
            tree.put(order.price, level);
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
        Long2ObjectRBTreeMap<OrderLevel> tree = tree(side);
        return tree.isEmpty() ? null : tree.get(tree.firstLongKey());
    }

    /** Removes a fully drained level from its tree and recycles it. */
    public void removeLevel(Side side, OrderLevel level) {
        tree(side).remove(level.price());
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
        for (OrderLevel level : tree(side).values()) {
            action.accept(level);
        }
    }
}
