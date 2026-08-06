package io.github.rajeevchaurasia.orderbook.engine;

/**
 * Outcome of one cancel. remainingQuantity is the unfilled quantity removed
 * from the book, 0 when the order was unknown.
 */
public record CancelResult(boolean canceled, long remainingQuantity) {
}
