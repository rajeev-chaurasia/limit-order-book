package io.github.rajeevchaurasia.orderbook.marketdata;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring of the most recent trades, stored as parallel primitive arrays so
 * recording a trade allocates nothing. Owned entirely by the engine thread:
 * writes happen during matching, reads happen while serving a snapshot
 * command. No synchronization by design.
 */
public final class TradeTape {

    public static final int DEFAULT_CAPACITY = 4096;

    private final long[] buyOrderIds;
    private final long[] sellOrderIds;
    private final long[] prices;
    private final long[] quantities;
    private final long[] timestamps;
    private final int mask;

    /** Total trades ever recorded; the ring keeps the most recent ones. */
    private long count;

    public TradeTape() {
        this(DEFAULT_CAPACITY);
    }

    public TradeTape(int capacity) {
        if (capacity < 2 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two >= 2: " + capacity);
        }
        this.buyOrderIds = new long[capacity];
        this.sellOrderIds = new long[capacity];
        this.prices = new long[capacity];
        this.quantities = new long[capacity];
        this.timestamps = new long[capacity];
        this.mask = capacity - 1;
    }

    public void record(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
        int index = (int) (count & mask);
        buyOrderIds[index] = buyOrderId;
        sellOrderIds[index] = sellOrderId;
        prices[index] = price;
        quantities[index] = quantity;
        timestamps[index] = timestamp;
        count++;
    }

    public long totalTrades() {
        return count;
    }

    /** The most recent trades, oldest first, at most {@code max}. Control plane. */
    public List<TradeRecord> recent(int max) {
        int available = (int) Math.min(count, mask + 1L);
        int wanted = Math.max(0, Math.min(max, available));
        List<TradeRecord> trades = new ArrayList<>(wanted);
        for (long i = count - wanted; i < count; i++) {
            int index = (int) (i & mask);
            trades.add(new TradeRecord(
                    buyOrderIds[index],
                    sellOrderIds[index],
                    prices[index],
                    quantities[index],
                    timestamps[index]));
        }
        return trades;
    }
}
