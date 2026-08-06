package io.github.rajeevchaurasia.orderbook.book;

/**
 * Fixed-capacity stack of preallocated {@link OrderLevel} objects. Price
 * levels churn constantly as the book moves, so recycling them keeps level
 * creation off the allocator. Overflow behavior matches {@link OrderPool}.
 *
 * <p>Owned by the single engine thread: no synchronization.
 */
public final class LevelPool {
    public static final int DEFAULT_CAPACITY = 16_384;

    private final OrderLevel[] stack;
    private int top;
    private long overflowAllocations;

    public LevelPool() {
        this(DEFAULT_CAPACITY);
    }

    public LevelPool(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.stack = new OrderLevel[capacity];
        for (int i = 0; i < capacity; i++) {
            OrderLevel level = new OrderLevel();
            level.pooled = true;
            stack[i] = level;
        }
        this.top = capacity - 1;
    }

    public OrderLevel borrow() {
        if (top < 0) {
            overflowAllocations++;
            return new OrderLevel();
        }
        OrderLevel level = stack[top];
        stack[top] = null;
        top--;
        level.pooled = false;
        return level;
    }

    public void release(OrderLevel level) {
        if (level.pooled) {
            throw new IllegalStateException("Level at price " + level.price() + " released twice");
        }
        level.reset();
        if (top == stack.length - 1) {
            return;
        }
        level.pooled = true;
        stack[++top] = level;
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
