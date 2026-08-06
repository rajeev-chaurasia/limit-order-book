package io.github.rajeevchaurasia.orderbook.engine;

/** Outcome of one submit, collapsed from the engine's event stream. */
public record OrderResult(
        long orderId,
        boolean accepted,
        RejectReason reason,
        long filledQuantity,
        long restingQuantity,
        int tradeCount) {
}
