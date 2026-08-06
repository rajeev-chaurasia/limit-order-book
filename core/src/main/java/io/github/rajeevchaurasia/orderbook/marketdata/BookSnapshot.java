package io.github.rajeevchaurasia.orderbook.marketdata;

import java.util.List;

/**
 * Immutable L2 depth snapshot. Built by the engine thread on request, so it
 * is always internally consistent: no snapshot ever observes a half-applied
 * match. Control plane: allocation here is deliberate and acceptable.
 */
public record BookSnapshot(List<Level> bids, List<Level> asks) {

    public record Level(long price, long quantity, int orders) {
    }
}
