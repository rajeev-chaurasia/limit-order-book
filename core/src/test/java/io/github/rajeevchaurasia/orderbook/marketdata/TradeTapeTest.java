package io.github.rajeevchaurasia.orderbook.marketdata;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeTapeTest {

    @Test
    void recordAndRecentReturnsOldestFirst() {
        TradeTape tape = new TradeTape(8);
        tape.record(1, 2, 100, 5, 1000);
        tape.record(3, 4, 101, 6, 1001);
        tape.record(5, 6, 102, 7, 1002);

        assertEquals(3, tape.totalTrades());

        List<TradeRecord> trades = tape.recent(10);
        assertEquals(3, trades.size());

        TradeRecord first = trades.get(0);
        assertEquals(1, first.buyOrderId());
        assertEquals(2, first.sellOrderId());
        assertEquals(100, first.price());
        assertEquals(5, first.quantity());
        assertEquals(1000, first.timestamp());

        assertEquals(101, trades.get(1).price());
        assertEquals(102, trades.get(2).price());
    }

    @Test
    void wrapKeepsMostRecent() {
        TradeTape tape = new TradeTape(4);
        for (int i = 0; i < 6; i++) {
            tape.record(10 + i, 20 + i, 100 + i, 1 + i, 1000 + i);
        }

        assertEquals(6, tape.totalTrades());

        List<TradeRecord> trades = tape.recent(10);
        assertEquals(4, trades.size());
        // Trades 2..5 survive, oldest first.
        for (int i = 0; i < 4; i++) {
            TradeRecord trade = trades.get(i);
            int recorded = 2 + i;
            assertEquals(10 + recorded, trade.buyOrderId());
            assertEquals(20 + recorded, trade.sellOrderId());
            assertEquals(100 + recorded, trade.price());
            assertEquals(1 + recorded, trade.quantity());
            assertEquals(1000 + recorded, trade.timestamp());
        }
    }

    @Test
    void recentZeroAndEmptyEdgeCases() {
        TradeTape empty = new TradeTape(4);
        assertEquals(0, empty.totalTrades());
        assertTrue(empty.recent(10).isEmpty());
        assertTrue(empty.recent(0).isEmpty());

        TradeTape tape = new TradeTape(4);
        tape.record(1, 2, 100, 5, 1000);
        assertTrue(tape.recent(0).isEmpty());
        assertEquals(1, tape.recent(1).size());
    }
}
