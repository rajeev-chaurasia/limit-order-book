package io.github.rajeevchaurasia.orderbook.marketdata;

/** Engine statistics snapshot. Control plane. */
public record StatsSnapshot(
        int activeOrders,
        int poolUtilization,
        int poolCapacity,
        int bidLevels,
        int askLevels,
        long totalTrades) {
}
