package io.github.rajeevchaurasia.orderbook.book;

/**
 * Fixed-capacity stack of preallocated {@link Order} objects.
 *
 * <p>While demand stays within capacity, borrow and release recycle the same
 * instances and the matching hot path allocates nothing. Beyond capacity the
 * pool degrades gracefully: borrow falls back to allocation and release drops
 * the surplus object for the garbage collector. Steady-state zero allocation
 * is therefore a sizing property, verified by the GC-profiled benchmarks
 * rather than enforced by failure.
 *
 * <p>Owned by the single engine thread: no synchronization.
 */
public final class OrderPool {
    public static final int DEFAULT_CAPACITY = 131_072;

    private final Order[] stack;
    private int top;
    private long overflowAllocations;

    public OrderPool() {
        this(DEFAULT_CAPACITY);
    }

    public OrderPool(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.stack = new Order[capacity];
        for (int i = 0; i < capacity; i++) {
            Order order = new Order();
            order.pooled = true;
            stack[i] = order;
        }
        this.top = capacity - 1;
    }

    public Order borrow() {
        if (top < 0) {
            overflowAllocations++;
            return new Order();
        }
        Order order = stack[top];
        stack[top] = null;
        top--;
        order.pooled = false;
        return order;
    }

    public void release(Order order) {
        if (order.pooled) {
            throw new IllegalStateException("Order " + order.orderId + " released twice");
        }
        order.reset();
        if (top == stack.length - 1) {
            return;
        }
        order.pooled = true;
        stack[++top] = order;
    }

    public int capacity() {
        return stack.length;
    }

    public int available() {
        return top + 1;
    }

    public long overflowAllocations() {
        return overflowAllocations;
    }
}
