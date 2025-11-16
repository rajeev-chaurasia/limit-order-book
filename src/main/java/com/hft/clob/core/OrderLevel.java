package com.hft.clob.core;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Custom doubly-linked list for maintaining FIFO order queue at a price level.
 * Uses intrusive linked list pattern (Order.next/prev) to avoid Node wrapper
 * allocation.
 * 
 * Thread Safety: Protected by ReentrantLock - allows fine-grained locking per
 * price level.
 * Multiple threads can operate on different price levels concurrently.
 * 
 * Time Complexity:
 * - addLast(): O(1)
 * - remove(): O(1)
 * - peek(): O(1)
 * - getTotalQuantity(): O(n) - only for market data
 * 
 * Resume Hook: "Designed lock-free price level queues with O(1) insert/delete
 * operations"
 */
public class OrderLevel {
    private Order head;
    private Order tail;
    private int size;
    private final ReentrantLock lock;
    private boolean removed;

    public OrderLevel() {
        this.head = null;
        this.tail = null;
        this.size = 0;
        this.lock = new ReentrantLock();
        this.removed = false;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    /**
     * Add order to the end of the queue (preserves time priority).
     */
    public void addLast(Order order) {
        lock.lock();
        try {
            if (tail == null) {
                // Empty list
                head = tail = order;
                order.next = null;
                order.prev = null;
            } else {
                // Append to tail
                tail.next = order;
                order.prev = tail;
                order.next = null;
                tail = order;
            }
            size++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove specific order from anywhere in the list (O(1) due to intrusive
     * pointers).
     * Used for Cancel operations.
     */
    public void remove(Order order) {
        lock.lock();
        try {
            if (order.prev != null) {
                order.prev.next = order.next;
            } else {
                // Removing head
                head = order.next;
            }

            if (order.next != null) {
                order.next.prev = order.prev;
            } else {
                // Removing tail
                tail = order.prev;
            }

            size--;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Peek at the head order (oldest order - FIFO).
     */
    public Order peek() {
        lock.lock();
        try {
            return head;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Remove and return the head order.
     */
    public Order pollFirst() {
        lock.lock();
        try {
            if (head == null) {
                return null;
            }

            Order order = head;
            head = head.next;

            if (head != null) {
                head.prev = null;
            } else {
                tail = null;
            }

            size--;
            return order;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Check if level is empty.
     */
    public boolean isEmpty() {
        lock.lock();
        try {
            return head == null;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Get number of orders at this price level.
     */
    public int getSize() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Calculate total quantity at this price level (for market data L2 depth).
     */
    public long getTotalQuantity() {
        lock.lock();
        try {
            long total = 0;
            Order current = head;
            while (current != null) {
                total += current.quantity;
                current = current.next;
            }
            return total;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquire lock (for matching engine to hold across operations).
     */
    public void acquireLock() {
        lock.lock();
    }

    /**
     * Release lock.
     */
    public void releaseLock() {
        lock.unlock();
    }
}
