package com.hft.clob.test;

import com.hft.clob.core.*;
import com.hft.clob.engine.*;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * Unit tests for core data structures and matching logic.
 */
public class OrderBookTest {

    @Test
    public void testOrderPoolBorrowReturn() {
        OrderPool pool = new OrderPool();

        // Borrow and return
        Order order1 = pool.borrow();
        assertNotNull(order1);
        assertEquals(100_000 - 1, pool.availableOrders());

        pool.returnOrder(order1);
        assertEquals(100_000, pool.availableOrders());
    }

    @Test
    public void testOrderLevelFIFO() {
        OrderLevel level = new OrderLevel();
        OrderPool pool = new OrderPool();

        Order o1 = pool.borrow();
        o1.init(1, (byte) 'B', 10000, 100);

        Order o2 = pool.borrow();
        o2.init(2, (byte) 'B', 10000, 200);

        level.addLast(o1);
        level.addLast(o2);

        // First in, first out
        assertEquals(1, level.peek().orderId);
        assertEquals(2, level.getSize());

        Order polled = level.pollFirst();
        assertEquals(1, polled.orderId);
        assertEquals(1, level.getSize());
    }

    @Test
    public void testOrderBookBidAskSorting() {
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);

        // Add bids at different prices
        Order bid1 = pool.borrow();
        bid1.init(1, (byte) 'B', 10000, 100);
        book.addOrder(bid1);

        Order bid2 = pool.borrow();
        bid2.init(2, (byte) 'B', 10100, 100);
        book.addOrder(bid2);

        // Best bid should be highest price
        assertEquals(10100L, book.getBestBid());

        // Add asks at different prices
        Order ask1 = pool.borrow();
        ask1.init(3, (byte) 'S', 10200, 100);
        book.addOrder(ask1);

        Order ask2 = pool.borrow();
        ask2.init(4, (byte) 'S', 10150, 100);
        book.addOrder(ask2);

        // Best ask should be lowest price
        assertEquals(10150L, book.getBestAsk());
    }

    @Test
    public void testMatchingEngineSimpleMatch() {
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        // Add sell order
        List<Trade> trades1 = engine.processOrder(1, (byte) 'S', 10500, 100);
        assertTrue(trades1.isEmpty(), "First order should not match");

        // Add buy order that crosses
        List<Trade> trades2 = engine.processOrder(2, (byte) 'B', 10500, 50);
        assertEquals(1, trades2.size(), "Should generate 1 trade");
        assertEquals(50, trades2.get(0).quantity);
        assertEquals(10500, trades2.get(0).price);

        // Check remaining book state
        assertNotNull(book.getBestAsk());
        assertEquals(10500L, book.getBestAsk());
    }

    @Test
    public void testMatchingEnginePartialFill() {
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        // Resting sell: 100 shares @ $105
        engine.processOrder(1, (byte) 'S', 10500, 100);

        // Incoming buy: 150 shares @ $105 (partial fill)
        List<Trade> trades = engine.processOrder(2, (byte) 'B', 10500, 150);

        assertEquals(1, trades.size());
        assertEquals(100, trades.get(0).quantity, "Should match full resting quantity");

        // Remaining 50 shares should be in book
        assertNotNull(book.getBestBid());
        assertEquals(10500L, book.getBestBid());
    }

    @Test
    public void testCancelOrder() {
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        // Add order
        engine.processOrder(1, (byte) 'B', 10000, 100);
        assertNotNull(book.findOrder(1));

        // Cancel order
        boolean cancelled = engine.cancelOrder(1);
        assertTrue(cancelled);
        assertNull(book.findOrder(1));
        assertTrue(book.isEmpty());
    }

    @Test
    public void testModifyOrder() {
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        // Add order
        engine.processOrder(1, (byte) 'B', 10000, 100);

        // Modify order (loses time priority)
        List<Trade> trades = engine.modifyOrder(1, (byte) 'B', 10100, 200);
        assertTrue(trades.isEmpty());

        // Verify new order
        Order modified = book.findOrder(1);
        assertNotNull(modified);
        assertEquals(10100, modified.price);
        assertEquals(200, modified.quantity);
    }

    @Test
    public void testPriceTimePriority() {
        OrderPool pool = new OrderPool();
        OrderBook book = new OrderBook(pool);
        MatchingEngine engine = new MatchingEngine(book);

        // Add three sell orders at same price
        engine.processOrder(1, (byte) 'S', 10500, 30);
        engine.processOrder(2, (byte) 'S', 10500, 30);
        engine.processOrder(3, (byte) 'S', 10500, 30);

        // Large buy order
        List<Trade> trades = engine.processOrder(4, (byte) 'B', 10500, 90);

        assertEquals(3, trades.size());

        // Verify FIFO order
        assertEquals(1, trades.get(0).sellOrderId);
        assertEquals(2, trades.get(1).sellOrderId);
        assertEquals(3, trades.get(2).sellOrderId);
    }
}
