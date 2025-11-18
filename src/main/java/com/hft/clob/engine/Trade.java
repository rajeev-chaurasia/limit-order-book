package com.hft.clob.engine;

/**
 * Immutable trade execution record with primitive fields.
 * Represents a match between a buy and sell order.
 */
public class Trade {
    public final long buyOrderId;
    public final long sellOrderId;
    public final long price;
    public final long quantity;
    public final long timestamp; // System.nanoTime() for latency measurement

    public Trade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format("Trade[buy=%d, sell=%d, price=%d, qty=%d, time=%d]",
                buyOrderId, sellOrderId, price, quantity, timestamp);
    }
}
