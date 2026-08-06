package io.github.rajeevchaurasia.orderbook.support;

import io.github.rajeevchaurasia.orderbook.engine.EngineListener;
import io.github.rajeevchaurasia.orderbook.engine.RejectReason;

import java.util.ArrayList;
import java.util.List;

/**
 * Records every engine callback as a compact string, so tests can assert on
 * exact event sequences and differential tests can compare two engines with a
 * single list equality check.
 */
public final class RecordingListener implements EngineListener {

    private final List<String> events = new ArrayList<>();
    private int tradeCount;

    @Override
    public void onTrade(long buyOrderId, long sellOrderId, long price, long quantity, long timestamp) {
        tradeCount++;
        events.add("TRADE buy=" + buyOrderId + " sell=" + sellOrderId
                + " px=" + price + " qty=" + quantity + " ts=" + timestamp);
    }

    @Override
    public void onOrderAccepted(long orderId, long filledQuantity, long restingQuantity) {
        events.add("ACCEPT id=" + orderId + " filled=" + filledQuantity + " resting=" + restingQuantity);
    }

    @Override
    public void onOrderCanceled(long orderId) {
        events.add("CANCELED id=" + orderId);
    }

    @Override
    public void onOrderRejected(long orderId, RejectReason reason) {
        events.add("REJECT id=" + orderId + " reason=" + reason);
    }

    public List<String> events() {
        return events;
    }

    public void clear() {
        events.clear();
        tradeCount = 0;
    }

    public int tradeCount() {
        return tradeCount;
    }
}
