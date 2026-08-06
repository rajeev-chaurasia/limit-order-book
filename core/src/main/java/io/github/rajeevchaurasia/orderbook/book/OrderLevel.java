package io.github.rajeevchaurasia.orderbook.book;

/**
 * FIFO queue of resting orders at one price, implemented as an intrusive
 * doubly-linked list over {@link Order} nodes. Insertion order is time
 * priority.
 *
 * <p>Aggregate quantity is maintained incrementally so L2 snapshots cost
 * O(levels) instead of O(orders).
 *
 * <p>Single-threaded by design: only the engine thread mutates levels, so
 * there are no locks. Instances are recycled through {@link LevelPool}.
 */
public final class OrderLevel {
    private long price;
    private Order head;
    private Order tail;
    private int size;
    private long totalQuantity;

    // Intrusive AVL node fields: the level is its own LevelTree node, so
    // creating or dropping a price level never allocates. Owned by LevelTree.
    long sortKey;
    OrderLevel left;
    OrderLevel right;
    OrderLevel parent;
    int height;

    /** Guards against double release back to the pool. */
    boolean pooled;

    void init(long price, long sortKey) {
        this.price = price;
        this.sortKey = sortKey;
    }

    void reset() {
        this.price = 0;
        this.head = null;
        this.tail = null;
        this.size = 0;
        this.totalQuantity = 0;
        this.sortKey = 0;
        this.left = null;
        this.right = null;
        this.parent = null;
        this.height = 0;
    }

    public long price() {
        return price;
    }

    public Order peekFirst() {
        return head;
    }

    public boolean isEmpty() {
        return head == null;
    }

    public int size() {
        return size;
    }

    public long totalQuantity() {
        return totalQuantity;
    }

    void addLast(Order order) {
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
            order.prev = tail;
        }
        tail = order;
        order.level = this;
        size++;
        totalQuantity += order.quantity;
    }

    /**
     * Unlinks an order that rests in this level. The level back-reference is
     * the source of truth: callers must only pass orders whose level is this
     * instance.
     */
    public void unlink(Order order) {
        if (order.level != this) {
            throw new IllegalStateException("Order " + order.orderId + " does not rest in this level");
        }
        if (order.prev != null) {
            order.prev.next = order.next;
        } else {
            head = order.next;
        }
        if (order.next != null) {
            order.next.prev = order.prev;
        } else {
            tail = order.prev;
        }
        order.next = null;
        order.prev = null;
        order.level = null;
        size--;
        totalQuantity -= order.quantity;
    }

    /** Reduces the quantity of a resting order after a partial fill. */
    public void reduce(Order order, long delta) {
        order.quantity -= delta;
        totalQuantity -= delta;
    }
}
