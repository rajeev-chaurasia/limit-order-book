package io.github.rajeevchaurasia.orderbook.engine;

/**
 * Receives engine results as they happen, on the engine thread.
 *
 * <p>All parameters are primitives or enum constants so the data plane emits
 * events without allocating. Implementations must be fast and must not block:
 * they run inside the matching loop.
 *
 * <p>Event contract for a NEW order: zero or more {@link #onTrade} calls in
 * match order, then exactly one terminal call, {@link #onOrderAccepted}
 * (restingQuantity is 0 when fully filled) or {@link #onOrderRejected}.
 * A CANCEL produces exactly one of {@link #onOrderCanceled} or
 * {@link #onOrderRejected}.
 */
public interface EngineListener {

    /** A fill. Price is the resting order's price (price improvement goes to the aggressor). */
    void onTrade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp);

    void onOrderAccepted(long orderId, long filledQuantity, long restingQuantity);

    void onOrderCanceled(long orderId);

    void onOrderRejected(long orderId, RejectReason reason);
}
