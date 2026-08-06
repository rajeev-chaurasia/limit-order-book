package io.github.rajeevchaurasia.orderbook.book;

/**
 * Pooled, mutable order record. Instances are recycled through
 * {@link OrderPool} so the matching hot path performs no allocation.
 *
 * <p>The next/prev fields make the order its own intrusive linked-list node
 * inside an {@link OrderLevel}, avoiding per-node wrapper allocation. The
 * level back-reference is non-null exactly while the order rests on the book,
 * which makes cancellation O(1) and double-removal structurally impossible.
 *
 * <p>Only the single engine thread ever touches an Order, so the fields need
 * no synchronization.
 */
public final class Order {
    public long orderId;
    public Side side;
    public long price;
    public long quantity;

    Order next;
    Order prev;
    OrderLevel level;

    /** Guards against double release back to the pool. */
    boolean pooled;

    public void init(long orderId, Side side, long price, long quantity) {
        this.orderId = orderId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.next = null;
        this.prev = null;
        this.level = null;
    }

    // Keeps orderId so pool diagnostics can name the order; init overwrites it on reuse.
    void reset() {
        this.side = null;
        this.price = 0;
        this.quantity = 0;
        this.next = null;
        this.prev = null;
        this.level = null;
    }

    public boolean isResting() {
        return level != null;
    }

    @Override
    public String toString() {
        return "Order[id=" + orderId + ", side=" + side + ", price=" + price + ", qty=" + quantity + "]";
    }
}
