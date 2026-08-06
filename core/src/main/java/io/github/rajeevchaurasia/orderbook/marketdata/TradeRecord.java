package io.github.rajeevchaurasia.orderbook.marketdata;

/** One executed trade, as reported to the outside world. Control plane. */
public record TradeRecord(
        long buyOrderId,
        long sellOrderId,
        long price,
        long quantity,
        long timestamp) {
}
