package io.github.rajeevchaurasia.orderbook.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LevelPoolTest {

    private static final int CAPACITY = 4;

    @Test
    void borrowReleaseRoundtripRestoresAvailability() {
        LevelPool pool = new LevelPool(CAPACITY);
        assertEquals(CAPACITY, pool.capacity());
        assertEquals(CAPACITY, pool.available());

        OrderLevel level = pool.borrow();
        assertNotNull(level);
        assertEquals(CAPACITY - 1, pool.available());

        pool.release(level);
        assertEquals(CAPACITY, pool.available());
        assertEquals(0, pool.overflowAllocations());
    }

    @Test
    void doubleReleaseThrows() {
        LevelPool pool = new LevelPool(CAPACITY);
        OrderLevel level = pool.borrow();
        pool.release(level);

        assertThrows(IllegalStateException.class, () -> pool.release(level));
    }

    @Test
    void borrowBeyondCapacityFallsBackToAllocation() {
        LevelPool pool = new LevelPool(CAPACITY);
        OrderLevel[] pooled = new OrderLevel[CAPACITY];
        for (int i = 0; i < CAPACITY; i++) {
            pooled[i] = pool.borrow();
        }
        assertEquals(0, pool.available());
        assertEquals(0, pool.overflowAllocations());

        OrderLevel overflow = pool.borrow();
        assertNotNull(overflow);
        assertEquals(1, pool.overflowAllocations());
        for (OrderLevel level : pooled) {
            assertNotSame(level, overflow);
        }
    }

    @Test
    void releaseBeyondCapacitySilentlyDropsSurplus() {
        LevelPool pool = new LevelPool(CAPACITY);
        OrderLevel[] borrowed = new OrderLevel[CAPACITY + 1];
        for (int i = 0; i < borrowed.length; i++) {
            borrowed[i] = pool.borrow();
        }

        for (OrderLevel level : borrowed) {
            pool.release(level);
        }
        assertEquals(CAPACITY, pool.available());
    }
}
