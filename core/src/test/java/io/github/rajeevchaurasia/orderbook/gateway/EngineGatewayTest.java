package io.github.rajeevchaurasia.orderbook.gateway;

import io.github.rajeevchaurasia.orderbook.book.Side;
import io.github.rajeevchaurasia.orderbook.engine.CancelResult;
import io.github.rajeevchaurasia.orderbook.engine.EngineLoop;
import io.github.rajeevchaurasia.orderbook.engine.OrderBookEngine;
import io.github.rajeevchaurasia.orderbook.engine.OrderResult;
import io.github.rajeevchaurasia.orderbook.engine.RejectReason;
import io.github.rajeevchaurasia.orderbook.engine.WaitStrategy;
import io.github.rajeevchaurasia.orderbook.marketdata.BookSnapshot;
import io.github.rajeevchaurasia.orderbook.marketdata.L1Quote;
import io.github.rajeevchaurasia.orderbook.marketdata.TradeRecord;
import io.github.rajeevchaurasia.orderbook.ring.MpscCommandRing;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Single caller through the full stack: gateway, ring, engine loop thread,
 * response futures, seqlock quote.
 */
class EngineGatewayTest {

    private MpscCommandRing ring;
    private EngineLoop loop;
    private EngineGateway gateway;

    @BeforeEach
    void setUp() {
        ring = new MpscCommandRing(1024);
        loop = new EngineLoop(ring, WaitStrategy.BUSY_SPIN);
        loop.start();
        gateway = new EngineGateway(ring, loop);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        loop.stop();
        assertFalse(loop.isRunning(), "engine loop still running after stop");
    }

    @Test
    void submitAndMatchRoundtrip() {
        OrderResult sell = gateway.submit(1, Side.SELL, 10_500, 100);
        assertTrue(sell.accepted());
        assertNull(sell.reason());
        assertEquals(0, sell.filledQuantity());
        assertEquals(100, sell.restingQuantity());
        assertEquals(0, sell.tradeCount());

        OrderResult buy = gateway.submit(2, Side.BUY, 10_500, 40);
        assertTrue(buy.accepted());
        assertEquals(40, buy.filledQuantity());
        assertEquals(0, buy.restingQuantity());
        assertEquals(1, buy.tradeCount());

        L1Quote.Quote quote = gateway.quote();
        assertEquals(OrderBookEngine.NO_PRICE, quote.bestBid());
        assertEquals(0, quote.bestBidQuantity());
        assertEquals(10_500, quote.bestAsk());
        assertEquals(60, quote.bestAskQuantity());

        List<TradeRecord> trades = gateway.recentTrades(10);
        assertEquals(1, trades.size());
        TradeRecord trade = trades.get(0);
        assertEquals(2, trade.buyOrderId());
        assertEquals(1, trade.sellOrderId());
        assertEquals(10_500, trade.price());
        assertEquals(40, trade.quantity());

        assertEquals(1, gateway.stats().totalTrades());

        BookSnapshot book = gateway.book();
        assertTrue(book.bids().isEmpty());
        assertEquals(1, book.asks().size());
        assertEquals(10_500, book.asks().get(0).price());
        assertEquals(60, book.asks().get(0).quantity());
    }

    @Test
    void cancelRoundtrip() {
        OrderResult submit = gateway.submit(1, Side.BUY, 10_000, 100);
        assertTrue(submit.accepted());
        assertEquals(100, submit.restingQuantity());

        CancelResult first = gateway.cancel(1);
        assertTrue(first.canceled());
        assertEquals(100, first.remainingQuantity());

        CancelResult second = gateway.cancel(1);
        assertFalse(second.canceled());
        assertEquals(0, second.remainingQuantity());

        BookSnapshot book = gateway.book();
        assertTrue(book.bids().isEmpty());
        assertTrue(book.asks().isEmpty());
    }

    @Test
    void rejectSurfacesInResult() {
        OrderResult result = gateway.submit(1, Side.BUY, 10_000, 0);
        assertFalse(result.accepted());
        assertEquals(RejectReason.INVALID_QUANTITY, result.reason());
        assertEquals(0, result.filledQuantity());
        assertEquals(0, result.restingQuantity());
        assertEquals(0, result.tradeCount());
    }

    @Test
    void quoteUpdatesAfterEachCommand() {
        gateway.submit(1, Side.BUY, 10_000, 50);
        gateway.submit(2, Side.BUY, 9_995, 30);
        gateway.submit(3, Side.SELL, 10_010, 40);

        L1Quote.Quote quote = gateway.quote();
        assertEquals(10_000, quote.bestBid());
        assertEquals(50, quote.bestBidQuantity());
        assertEquals(10_010, quote.bestAsk());
        assertEquals(40, quote.bestAskQuantity());

        CancelResult cancel = gateway.cancel(1);
        assertTrue(cancel.canceled());

        quote = gateway.quote();
        assertEquals(9_995, quote.bestBid());
        assertEquals(30, quote.bestBidQuantity());
        assertEquals(10_010, quote.bestAsk());
        assertEquals(40, quote.bestAskQuantity());
    }
}
