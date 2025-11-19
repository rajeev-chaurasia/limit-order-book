package com.hft.clob.marketdata;

import java.util.Map;

/**
 * Market data publisher interface for L1/L2 updates.
 * Implementations can send to console, logger, or external feed handler.
 */
public interface MarketDataPublisher {
    /**
     * L1 update: Best Bid and Best Ask changed.
     * 
     * @param bestBid Best bid price (null if no bids)
     * @param bestAsk Best ask price (null if no asks)
     */
    void onBestBidAskUpdate(Long bestBid, Long bestAsk);

    /**
     * L2 update: Full depth aggregated by price.
     * 
     * @param bids Map of price -> total quantity
     * @param asks Map of price -> total quantity
     */
    void onDepthUpdate(Map<Long, Long> bids, Map<Long, Long> asks);

    /**
     * Trade execution notification.
     * 
     * @param buyOrderId  Buy order ID
     * @param sellOrderId Sell order ID
     * @param price       Execution price
     * @param quantity    Execution quantity
     */
    void onTrade(long buyOrderId, long sellOrderId, long price, long quantity);
}
