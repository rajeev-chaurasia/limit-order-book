package io.github.rajeevchaurasia.orderbook.ring;

import io.github.rajeevchaurasia.orderbook.book.Side;

import java.util.concurrent.CompletableFuture;

/**
 * One preallocated ring slot. Producers write the fields of a claimed slot
 * and then publish; the consumer reads them and releases the slot for reuse.
 * Field visibility across threads is guaranteed by the slot's sequence
 * handoff in {@link MpscCommandRing}, not by the fields themselves.
 *
 * <p>The response future is the optional reply channel. When null the
 * command is fire-and-forget, which keeps benchmark and load-test submission
 * allocation-free.
 */
public final class OrderCommand {

    /** Slot state for the ring protocol. Touched only via the ring's VarHandle. */
    volatile long sequence;

    public CommandType type;
    public long orderId;
    public Side side;
    public long price;
    public long quantity;
    public CompletableFuture<Object> response;

    OrderCommand(long initialSequence) {
        this.sequence = initialSequence;
    }
}
