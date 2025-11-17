package com.hft.clob.core;

import java.util.Comparator;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.locks.StampedLock;

/**
 * High-performance Order Book using ConcurrentSkipListMap for lock-free sorted
 * price levels.
 * 
 * Design Choices:
 * - Bids: Sorted in DESCENDING order (highest price first)
 * - Asks: Sorted in ASCENDING order (lowest price first)
 * - StampedLock: Optimistic reads for accessing best bid/ask (L1 data)
 * - ConcurrentSkipListMap: Thread-safe sorted map without global locking
 * 
 * Memory Layout:
 * - Each price level contains an OrderLevel (doubly-linked list of orders)
 * - OrderIndex provides O(1) lookup by OrderId
 * - OrderPool manages object lifecycle
 */
public class OrderBook {
    // Bids: Highest price first (descending)
    private final ConcurrentSkipListMap<Long, OrderLevel> bids;

    // Asks: Lowest price first (ascending)
    private final ConcurrentSkipListMap<Long, OrderLevel> asks;

    // O(1) order lookup index
    private final OrderIndex index;

    // Object pool for zero-GC
    private final OrderPool pool;

    // StampedLock for optimistic reads of best bid/ask
    private final StampedLock lock;

    public OrderBook(OrderPool pool) {
        this.bids = new ConcurrentSkipListMap<>(Comparator.reverseOrder());
        this.asks = new ConcurrentSkipListMap<>();
        this.index = new OrderIndex();
        this.pool = pool;
        this.lock = new StampedLock();
    }

    /**
     * Get the order book side (bids or asks).
     */
    public ConcurrentSkipListMap<Long, OrderLevel> getSide(byte side) {
        return side == 'B' ? bids : asks;
    }

    /**
     * Get best bid price (optimistic read).
     */
    public Long getBestBid() {
        long stamp = lock.tryOptimisticRead();
        Long bestBid = bids.isEmpty() ? null : bids.firstKey();

        if (!lock.validate(stamp)) {
            // Optimistic read failed, acquire read lock
            stamp = lock.readLock();
            try {
                bestBid = bids.isEmpty() ? null : bids.firstKey();
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return bestBid;
    }

    /**
     * Get best ask price (optimistic read).
     */
    public Long getBestAsk() {
        long stamp = lock.tryOptimisticRead();
        Long bestAsk = asks.isEmpty() ? null : asks.firstKey();

        if (!lock.validate(stamp)) {
            // Optimistic read failed, acquire read lock
            stamp = lock.readLock();
            try {
                bestAsk = asks.isEmpty() ? null : asks.firstKey();
            } finally {
                lock.unlockRead(stamp);
            }
        }

        return bestAsk;
    }

    /**
     * Add order to the book (assumes no immediate match).
     */
    public void addOrder(Order order) {
        ConcurrentSkipListMap<Long, OrderLevel> side = getSide(order.side);

        // Atomic add: compute ensures we handle the level creation/retrieval and
        // addition atomically
        // w.r.t the map entry, preventing the "lost update" race condition.
        side.compute(order.price, (price, level) -> {
            if (level == null || level.isRemoved()) {
                level = new OrderLevel();
            }
            level.addLast(order);
            return level;
        });

        // Add to index for O(1) lookup
        index.put(order.orderId, order);
    }

    /**
     * Remove order from the book (for cancellation or full fill).
     */
    public void removeOrder(Order order) {
        ConcurrentSkipListMap<Long, OrderLevel> side = getSide(order.side);

        // Atomic remove: compute ensures we remove the order and check for emptiness
        // atomically
        // w.r.t the map entry.
        side.compute(order.price, (price, level) -> {
            if (level != null) {
                level.remove(order);
                // If level is empty, return null to remove it from the map
                return level.isEmpty() ? null : level;
            }
            return null;
        });

        // Remove from index
        index.remove(order.orderId);
    }

    /**
     * Find order by ID (O(1) lookup).
     */
    public Order findOrder(long orderId) {
        return index.get(orderId);
    }

    /**
     * Get OrderIndex (for testing/debugging).
     */
    public OrderIndex getIndex() {
        return index;
    }

    /**
     * Get OrderPool.
     */
    public OrderPool getPool() {
        return pool;
    }

    /**
     * Get all bids (for market data L2).
     */
    public ConcurrentSkipListMap<Long, OrderLevel> getBids() {
        return bids;
    }

    /**
     * Get all asks (for market data L2).
     */
    public ConcurrentSkipListMap<Long, OrderLevel> getAsks() {
        return asks;
    }

    /**
     * Check if book is empty.
     */
    public boolean isEmpty() {
        return bids.isEmpty() && asks.isEmpty();
    }
}
