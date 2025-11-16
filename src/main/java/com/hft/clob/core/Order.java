package com.hft.clob.core;

/**
 * Fixed-size Order object using ONLY primitive fields to minimize memory
 * footprint.
 * This object is designed for recycling via ObjectPool to achieve zero-GC in
 * the hot path.
 * 
 * Memory Layout (approx 56 bytes on 64-bit JVM with compressed OOPs):
 * - orderId: 8 bytes
 * - price: 8 bytes (fixed-point: 10500 = $105.00)
 * - quantity: 8 bytes
 * - side: 1 byte ('B' or 'S')
 * - next: 8 bytes (reference)
 * - prev: 8 bytes (reference)
 * - Object header: ~16 bytes
 */
public class Order {
    // Primitive fields - NO String, NO BigInteger, NO boxed types
    public long orderId;
    public long price; // Fixed-point representation (e.g., 10500 = $105.00)
    public long quantity;
    public byte side; // 'B' = Buy, 'S' = Sell

    // Doubly-linked list pointers (intrusive list pattern - avoids Node wrapper
    // allocation)
    public Order next;
    public Order prev;

    /**
     * Reset all fields for object pool reuse.
     * CRITICAL: This method must be called before returning to pool.
     */
    public void reset() {
        this.orderId = 0;
        this.price = 0;
        this.quantity = 0;
        this.side = 0;
        this.next = null;
        this.prev = null;
    }

    /**
     * Initialize order with parameters (used when borrowing from pool).
     */
    public void init(long orderId, byte side, long price, long quantity) {
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.next = null;
        this.prev = null;
    }

    @Override
    public String toString() {
        return String.format("Order[id=%d, side=%c, price=%d, qty=%d]",
                orderId, (char) side, price, quantity);
    }
}
