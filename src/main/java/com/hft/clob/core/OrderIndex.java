package com.hft.clob.core;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

/**
 * Thread-safe wrapper around fastutil's Long2ObjectOpenHashMap for O(1) order
 * lookup.
 * Maps OrderId -> Order for fast cancellation and modification operations.
 * 
 * Uses primitive long keys (no boxing overhead) for optimal performance.
 */
public class OrderIndex {
    private final Long2ObjectOpenHashMap<Order> index;

    public OrderIndex() {
        // Initial capacity for 100k orders
        this.index = new Long2ObjectOpenHashMap<>(100_000);
    }

    /**
     * Add order to index.
     */
    public synchronized void put(long orderId, Order order) {
        index.put(orderId, order);
    }

    /**
     * Get order by ID (O(1) lookup).
     */
    public synchronized Order get(long orderId) {
        return index.get(orderId);
    }

    /**
     * Remove order from index.
     */
    public synchronized Order remove(long orderId) {
        return index.remove(orderId);
    }

    /**
     * Check if order exists.
     */
    public synchronized boolean contains(long orderId) {
        return index.containsKey(orderId);
    }

    /**
     * Get current index size.
     */
    public synchronized int size() {
        return index.size();
    }

    /**
     * Clear all entries (for testing).
     */
    public synchronized void clear() {
        index.clear();
    }
}
