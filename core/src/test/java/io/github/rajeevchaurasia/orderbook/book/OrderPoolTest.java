package io.github.rajeevchaurasia.orderbook.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderPoolTest {

    private static final int CAPACITY = 4;

    @Test
    void borrowReleaseRoundtripRestoresAvailability() {
        OrderPool pool = new OrderPool(CAPACITY);
        assertEquals(CAPACITY, pool.capacity());
        assertEquals(CAPACITY, pool.available());

        Order order = pool.borrow();
        assertNotNull(order);
        assertEquals(CAPACITY - 1, pool.available());

        pool.release(order);
        assertEquals(CAPACITY, pool.available());
        assertEquals(0, pool.overflowAllocations());
    }

    @Test
    void doubleReleaseThrows() {
        OrderPool pool = new OrderPool(CAPACITY);
        Order order = pool.borrow();
        pool.release(order);

        assertThrows(IllegalStateException.class, () -> pool.release(order));
    }

    @Test
    void borrowBeyondCapacityFallsBackToAllocation() {
        OrderPool pool = new OrderPool(CAPACITY);
        Order[] pooled = new Order[CAPACITY];
        for (int i = 0; i < CAPACITY; i++) {
            pooled[i] = pool.borrow();
        }
        assertEquals(0, pool.available());
        assertEquals(0, pool.overflowAllocations());

        Order overflow = pool.borrow();
        assertNotNull(overflow);
        assertEquals(1, pool.overflowAllocations());
        for (Order order : pooled) {
            assertNotSame(order, overflow);
        }
    }

    @Test
    void releaseBeyondCapacitySilentlyDropsSurplus() {
        OrderPool pool = new OrderPool(CAPACITY);
        Order[] borrowed = new Order[CAPACITY + 1];
        for (int i = 0; i < borrowed.length; i++) {
            borrowed[i] = pool.borrow();
        }

        for (Order order : borrowed) {
            pool.release(order);
        }
        assertEquals(CAPACITY, pool.available());
    }
}
