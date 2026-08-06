package io.github.rajeevchaurasia.orderbook.app.api;

import java.util.List;

/**
 * JSON DTOs for the REST API. Field names are the wire contract consumed by
 * the deployed Streamlit UI; do not rename them.
 */
public final class Dtos {

    private Dtos() {
    }

    /** Sides are null when that side of the book is empty; spread requires both. */
    public record QuoteDto(Long bestBid, Long bestAsk, Long spread) {
    }

    /** Boxed types so absent JSON fields arrive as null. */
    public record OrderRequest(Long orderId, String side, Long price, Long quantity) {
    }

    public record OrderResponse(long orderId, String status, int tradesCount, long remainingQuantity) {
    }

    public record PriceLevelDto(long price, long quantity, int orders) {
    }

    public record BookDto(List<PriceLevelDto> bids, List<PriceLevelDto> asks) {
    }

    public record TradeDto(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
    }

    public record StatsDto(
            int activeOrders,
            int poolUtilization,
            int poolCapacity,
            int bidLevels,
            int askLevels,
            long totalTrades) {
    }

    public record CancelResponse(String status, long orderId) {
    }

    public record ErrorResponse(String error) {
    }
}
