package io.github.rajeevchaurasia.orderbook.gateway;

/** The command ring stayed full past the backpressure budget. */
public final class EngineBusyException extends RuntimeException {

    public EngineBusyException(String message) {
        super(message);
    }
}
