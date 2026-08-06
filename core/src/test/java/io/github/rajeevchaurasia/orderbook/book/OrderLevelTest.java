package io.github.rajeevchaurasia.orderbook.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lives in the book package to reach the package-private addLast and init. */
class OrderLevelTest {

    private static final long PRICE = 10_500;

    private static OrderLevel level(long price) {
        OrderLevel level = new OrderLevel();
        level.init(price, price);
        return level;
    }

    private static Order order(long orderId, long quantity) {
        Order order = new Order();
        order.init(orderId, Side.SELL, PRICE, quantity);
        return order;
    }

    @Test
    void addLastMaintainsFifoAndAggregates() {
        OrderLevel level = level(PRICE);
        Order a = order(1, 10);
        Order b = order(2, 20);
        Order c = order(3, 30);
        level.addLast(a);
        level.addLast(b);
        level.addLast(c);

        assertEquals(PRICE, level.price());
        assertFalse(level.isEmpty());
        assertEquals(3, level.size());
        assertEquals(60, level.totalQuantity());
        assertSame(a, level.peekFirst());
        assertSame(b, a.next);
        assertSame(c, b.next);
        assertTrue(a.isResting());
        assertTrue(c.isResting());
    }

    @Test
    void unlinkMiddleRelinksNeighbors() {
        OrderLevel level = level(PRICE);
        Order a = order(1, 10);
        Order b = order(2, 20);
        Order c = order(3, 30);
        level.addLast(a);
        level.addLast(b);
        level.addLast(c);

        level.unlink(b);

        assertEquals(2, level.size());
        assertEquals(40, level.totalQuantity());
        assertSame(a, level.peekFirst());
        assertSame(c, a.next);
        assertSame(a, c.prev);
        assertFalse(b.isResting());
        assertNull(b.next);
        assertNull(b.prev);
    }

    @Test
    void unlinkHeadPromotesNext() {
        OrderLevel level = level(PRICE);
        Order a = order(1, 10);
        Order b = order(2, 20);
        level.addLast(a);
        level.addLast(b);

        level.unlink(a);

        assertSame(b, level.peekFirst());
        assertNull(b.prev);
        assertEquals(1, level.size());
        assertEquals(20, level.totalQuantity());
    }

    @Test
    void unlinkTailUpdatesEnd() {
        OrderLevel level = level(PRICE);
        Order a = order(1, 10);
        Order b = order(2, 20);
        level.addLast(a);
        level.addLast(b);

        level.unlink(b);

        assertSame(a, level.peekFirst());
        assertNull(a.next);
        assertEquals(1, level.size());

        // Appending after a tail removal must link onto the new tail.
        Order c = order(3, 30);
        level.addLast(c);
        assertSame(c, a.next);
        assertSame(a, c.prev);
        assertEquals(2, level.size());
        assertEquals(40, level.totalQuantity());
    }

    @Test
    void unlinkLastOrderEmptiesLevel() {
        OrderLevel level = level(PRICE);
        Order a = order(1, 10);
        level.addLast(a);

        level.unlink(a);

        assertTrue(level.isEmpty());
        assertEquals(0, level.size());
        assertEquals(0, level.totalQuantity());
        assertNull(level.peekFirst());
    }

    @Test
    void reduceLowersOrderAndAggregateQuantity() {
        OrderLevel level = level(PRICE);
        Order a = order(1, 10);
        Order b = order(2, 20);
        level.addLast(a);
        level.addLast(b);

        level.reduce(a, 4);

        assertEquals(6, a.quantity);
        assertEquals(26, level.totalQuantity());
        assertEquals(2, level.size());
        assertSame(a, level.peekFirst());
    }

    @Test
    void unlinkFromWrongLevelThrows() {
        OrderLevel first = level(PRICE);
        OrderLevel second = level(PRICE + 10);
        Order a = order(1, 10);
        first.addLast(a);

        assertThrows(IllegalStateException.class, () -> second.unlink(a));

        // The failed unlink must not disturb the owning level.
        assertSame(a, first.peekFirst());
        assertEquals(1, first.size());
        assertEquals(10, first.totalQuantity());
        assertTrue(a.isResting());
    }
}
