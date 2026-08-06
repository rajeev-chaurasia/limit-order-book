package io.github.rajeevchaurasia.orderbook.engine;

/** Why the engine refused a command. */
public enum RejectReason {
    INVALID_QUANTITY,
    INVALID_PRICE,
    DUPLICATE_ORDER_ID,
    UNKNOWN_ORDER
}
