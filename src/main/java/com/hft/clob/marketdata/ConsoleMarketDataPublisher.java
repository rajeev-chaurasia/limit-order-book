package com.hft.clob.marketdata;

import java.util.Map;

/**
 * Simple console-based market data publisher for demonstration.
 */
public class ConsoleMarketDataPublisher implements MarketDataPublisher {

    @Override
    public void onBestBidAskUpdate(Long bestBid, Long bestAsk) {
        System.out.printf("[L1] Best Bid: %s | Best Ask: %s | Spread: %s%n",
                formatPrice(bestBid),
                formatPrice(bestAsk),
                formatSpread(bestBid, bestAsk));
    }

    @Override
    public void onDepthUpdate(Map<Long, Long> bids, Map<Long, Long> asks) {
        System.out.println("\n[L2] Order Book Depth:");
        System.out.println("BIDS:");
        bids.forEach((price, qty) -> System.out.printf("  %s @ %d shares%n", formatPrice(price), qty));

        System.out.println("ASKS:");
        asks.forEach((price, qty) -> System.out.printf("  %s @ %d shares%n", formatPrice(price), qty));
        System.out.println();
    }

    @Override
    public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity) {
        System.out.printf("[TRADE] Buy #%d x Sell #%d | %s @ %d shares%n",
                buyOrderId, sellOrderId, formatPrice(price), quantity);
    }

    private String formatPrice(Long price) {
        if (price == null) {
            return "N/A";
        }
        // Convert fixed-point to decimal (e.g., 10500 -> $105.00)
        return String.format("$%.2f", price / 100.0);
    }

    private String formatSpread(Long bid, Long ask) {
        if (bid == null || ask == null) {
            return "N/A";
        }
        long spread = ask - bid;
        return String.format("$%.2f", spread / 100.0);
    }
}
